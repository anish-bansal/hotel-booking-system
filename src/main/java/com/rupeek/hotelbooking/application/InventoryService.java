package com.rupeek.hotelbooking.application;

import com.rupeek.hotelbooking.domain.exception.InventoryUnavailableException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.model.RoomInventory;
import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.port.RoomInventoryRepository;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guards the one piece of contended state in the system: how many rooms are left on each night.
 *
 * <h2>The double-booking problem</h2>
 *
 * Two guests ask for the last room on the same night at the same instant. Both read
 * "1 available", both decide yes, both write "held = 1", and the hotel has sold a room twice. The
 * read and the write must therefore be one indivisible step, and no amount of care in application
 * code achieves that on its own — the database has to serialise it.
 *
 * <h2>How this class solves it</h2>
 *
 * {@link #reserve} takes a {@code SELECT ... FOR UPDATE} on every night of the stay before looking
 * at a single number. The second transaction blocks on the first row it shares with the first
 * transaction, and by the time it is let through it reads the <em>updated</em> count and correctly
 * concludes there is nothing left. Three details make this work rather than merely look like it
 * works:
 *
 * <ol>
 *   <li><b>Locks are acquired in ascending date order</b> (enforced by the repository query's
 *       {@code ORDER BY}). Overlapping stays therefore contend on their shared nights in the same
 *       sequence, so no two transactions can each hold a row the other is waiting for. Without a
 *       consistent order, a 3rd–5th booking and a 4th–6th booking could deadlock.
 *   <li><b>Check every night, then mutate every night</b> — two passes, not one. A single fused
 *       loop would leave half the stay held when the fifth night turns out to be full; correctness
 *       would then depend on the rollback actually happening. Two passes mean the in-memory state
 *       is never inconsistent in the first place, and the error can name the exact night that
 *       failed.
 *   <li><b>{@code Propagation.MANDATORY}</b>. A lock is only worth anything until its transaction
 *       commits. If this method were ever called outside a transaction — or opened its own — the
 *       lock would be released the moment it returned, leaving a gap before the booking was
 *       persisted in which another request could reserve the same room. Declaring MANDATORY turns
 *       that subtle race into a startup-time wiring error.
 * </ol>
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final RoomInventoryRepository inventoryRepository;
    private final int bookingHorizonDays;

    public InventoryService(RoomInventoryRepository inventoryRepository,
                            @Value("${hotel-booking.inventory.booking-horizon-days:365}")
                            int bookingHorizonDays) {
        this.inventoryRepository = inventoryRepository;
        this.bookingHorizonDays = bookingHorizonDays;
    }

    /**
     * Materialise nightly inventory rows for a newly onboarded room type, one per night out to the
     * booking horizon.
     *
     * <p>Creating rows up front rather than lazily on first booking is a deliberate simplification.
     * Lazy creation would introduce a second race — two transactions both discovering a night has no
     * row and both inserting one — which would need its own unique-constraint-and-retry dance. By
     * guaranteeing the row always exists, {@link #reserve} only ever has to lock, never create, and
     * the whole booking path has exactly one concurrency concern instead of two.
     */
    @Transactional
    public int openInventory(RoomType roomType, LocalDate from) {
        List<RoomInventory> nights = new ArrayList<>(bookingHorizonDays);
        for (int dayOffset = 0; dayOffset < bookingHorizonDays; dayOffset++) {
            nights.add(RoomInventory.forNight(roomType.id(), from.plusDays(dayOffset),
                    roomType.totalRooms()));
        }
        inventoryRepository.saveAll(nights);
        log.debug("Opened {} nights of inventory for room type {}", nights.size(), roomType.id());
        return nights.size();
    }

    /**
     * Hold {@code rooms} rooms across every night of {@code stay}, or fail without holding any.
     *
     * @throws InventoryUnavailableException naming the first night that could not be satisfied
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID roomTypeId, DateRange stay, int rooms) {
        List<RoomInventory> nights = lockStay(roomTypeId, stay);

        // Pass 1 - decide. No state is touched until the whole stay is known to be satisfiable.
        for (RoomInventory night : nights) {
            if (!night.canHold(rooms)) {
                throw new InventoryUnavailableException(night.date(), rooms, night.available());
            }
        }

        // Pass 2 - commit the decision.
        nights.forEach(night -> night.hold(rooms));
        inventoryRepository.saveAll(nights);
        log.debug("Held {} room(s) of type {} across {}", rooms, roomTypeId, stay);
    }

    /**
     * Return rooms to sale after a cancellation or an expired hold.
     *
     * <p>Locks the same rows in the same order as {@link #reserve}. A release that skipped the lock
     * could interleave with a concurrent reservation and lose one of the two updates — releasing is
     * every bit as much a read-modify-write as reserving.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID roomTypeId, DateRange stay, int rooms) {
        List<RoomInventory> nights = lockStay(roomTypeId, stay);
        nights.forEach(night -> night.release(rooms));
        inventoryRepository.saveAll(nights);
        log.debug("Released {} room(s) of type {} across {}", rooms, roomTypeId, stay);
    }

    /**
     * Lowest availability across the stay, per room type, in one lock-free query.
     *
     * <p>Used by search, where the answer is advisory: a room shown as available may be gone by the
     * time the guest clicks book, and that is fine because {@link #reserve} re-checks under a lock.
     * Taking write locks here would be actively harmful — a browsing user would block a paying one.
     *
     * <p>The minimum is the right aggregate because a stay needs the room on <em>every</em> night;
     * an average or a first-night check would advertise stays that cannot actually be completed.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> lowestAvailabilityPerRoomType(Collection<UUID> roomTypeIds,
                                                           DateRange stay) {
        if (roomTypeIds.isEmpty()) {
            return Map.of();
        }
        List<RoomInventory> rows = inventoryRepository.findForAvailabilityCheck(
                roomTypeIds, stay.checkIn(), lastNightOf(stay));

        Map<UUID, Integer> lowest = new HashMap<>();
        Map<UUID, Integer> nightsSeen = new HashMap<>();
        for (RoomInventory row : rows) {
            lowest.merge(row.roomTypeId(), row.available(), Math::min);
            nightsSeen.merge(row.roomTypeId(), 1, Integer::sum);
        }

        // A room type missing rows for part of the stay (e.g. beyond the horizon) is not "available
        // for what we have" - it is unavailable, because the guest asked for the whole stay.
        long requiredNights = stay.nightCount();
        nightsSeen.forEach((roomTypeId, seen) -> {
            if (seen < requiredNights) {
                lowest.put(roomTypeId, 0);
            }
        });
        roomTypeIds.forEach(id -> lowest.putIfAbsent(id, 0));
        return lowest;
    }

    private List<RoomInventory> lockStay(UUID roomTypeId, DateRange stay) {
        List<RoomInventory> nights = inventoryRepository.lockNightsForUpdate(
                roomTypeId, stay.checkIn(), lastNightOf(stay));

        if (nights.size() != stay.nightCount()) {
            throw new ValidationException("Inventory is not open for every night of " + stay
                    + " (found " + nights.size() + " of " + stay.nightCount()
                    + " nights). The booking horizon is " + bookingHorizonDays + " days.");
        }
        return nights;
    }

    /**
     * Check-out day is not a night. Converting the half-open {@code [checkIn, checkOut)} range into
     * an inclusive night range happens here, once, rather than as a {@code minusDays(1)} scattered
     * across every query call site.
     */
    private static LocalDate lastNightOf(DateRange stay) {
        return stay.checkOut().minusDays(1);
    }

    public int bookingHorizonDays() {
        return bookingHorizonDays;
    }
}
