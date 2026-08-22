package com.rupeek.hotelbooking.application;

import com.rupeek.hotelbooking.application.command.CreateBookingCommand;
import com.rupeek.hotelbooking.domain.exception.NotFoundException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.BookingStatus;
import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.policy.PricingStrategy;
import com.rupeek.hotelbooking.domain.port.BookingRepository;
import com.rupeek.hotelbooking.domain.port.RoomTypeRepository;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, reading and expiring bookings.
 *
 * <h2>Why a booking starts unpaid rather than being created by payment</h2>
 *
 * {@link #create} takes the inventory <em>before</em> any money moves, and the booking begins life
 * in {@code PENDING_PAYMENT}. The alternative — charge first, then try to grab a room — is worse in
 * the case that matters: if the rooms are gone you have taken money for a stay that cannot happen
 * and now owe a refund. Holding first means the failure lands where it is cheap, on a guest who has
 * not yet been charged.
 *
 * <p>The cost of holding first is that an abandoned checkout would sterilise saleable rooms
 * indefinitely, so every hold carries an expiry and {@link #expireLapsedHolds} returns the rooms to
 * sale. That is the trade this design accepts: a small, bounded window in which rooms are held for
 * someone who may never pay, in exchange for never charging for a room we cannot deliver.
 *
 * <p><b>One transaction, start to finish.</b> Reserving inventory and persisting the booking are a
 * single transaction, so the row locks {@code InventoryService} takes are still held when the
 * booking row is written. Splitting them would open a window between "rooms held" and "booking
 * exists" — and a crash inside that window leaks inventory that nothing owns.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    /** Longer stays exist, but they are negotiated with the property rather than self-served. */
    private static final int MAX_BOOKABLE_NIGHTS = 30;

    private final BookingRepository bookingRepository;
    private final RoomTypeRepository roomTypeRepository;
    private final InventoryService inventoryService;
    private final PricingStrategy pricingStrategy;
    private final Clock clock;
    private final Duration paymentHoldDuration;

    public BookingService(BookingRepository bookingRepository,
                          RoomTypeRepository roomTypeRepository,
                          InventoryService inventoryService,
                          PricingStrategy pricingStrategy,
                          Clock clock,
                          @Value("${hotel-booking.booking.payment-hold-minutes:15}")
                          long paymentHoldMinutes) {
        this.bookingRepository = bookingRepository;
        this.roomTypeRepository = roomTypeRepository;
        this.inventoryService = inventoryService;
        this.pricingStrategy = pricingStrategy;
        this.clock = clock;
        this.paymentHoldDuration = Duration.ofMinutes(paymentHoldMinutes);
    }

    @Transactional
    public Booking create(CreateBookingCommand command) {
        RoomType roomType = roomTypeRepository.findById(command.roomTypeId())
                .orElseThrow(() -> new NotFoundException("RoomType", command.roomTypeId()));

        // The room type must belong to the property the caller named. Without this, a caller could
        // book a cheap room type from hotel A "at" hotel B by pairing mismatched ids.
        if (!roomType.property().id().equals(command.propertyId())) {
            throw new ValidationException("Room type " + command.roomTypeId()
                    + " does not belong to property " + command.propertyId());
        }

        DateRange stay = DateRange.of(command.checkIn(), command.checkOut());
        Instant now = clock.instant();
        rejectStayInThePast(stay, now);
        rejectOverlongStay(stay);

        if (!roomType.canHost(command.guests(), command.rooms())) {
            throw new ValidationException(command.guests() + " guest(s) do not fit in "
                    + command.rooms() + " x " + roomType.name()
                    + " (sleeps " + roomType.maxOccupancy() + " per room)");
        }

        Money total = pricingStrategy.quote(new PricingStrategy.PricingRequest(
                roomType, stay, command.rooms(), command.guests()));

        Booking booking = Booking.request(command.propertyId(), command.roomTypeId(),
                command.guestName(), command.guestEmail(), stay, command.guests(), command.rooms(),
                total, now, paymentHoldDuration);

        // Serialising point: this either wins the rooms for the whole stay or throws.
        inventoryService.reserve(command.roomTypeId(), stay, command.rooms());

        Booking saved = bookingRepository.save(booking);
        log.info("Created booking {} for {} ({} room(s), {}) holding inventory until {}",
                saved.id(), saved.guestEmail(), saved.roomCount(), stay, saved.holdExpiresAt());
        return saved;
    }

    @Transactional(readOnly = true)
    public Booking require(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
    }

    @Transactional(readOnly = true)
    public List<Booking> findByGuest(String guestEmail) {
        return bookingRepository.findByGuestEmail(guestEmail);
    }

    /**
     * Expire unpaid bookings whose hold has lapsed and return their rooms to sale.
     *
     * <p>Runs in one transaction per sweep rather than one per booking: the set is small, and doing
     * it atomically means a partially-swept state is never visible. Both the state change and the
     * inventory release happen together, so a room can never be released while its booking still
     * claims to hold it.
     */
    @Transactional
    public int expireLapsedHolds() {
        Instant now = clock.instant();
        List<Booking> lapsed = bookingRepository.findExpiredHolds(now);

        for (Booking booking : lapsed) {
            booking.expire();
            inventoryService.release(booking.roomTypeId(), booking.stay(), booking.roomCount());
            bookingRepository.save(booking);
            log.info("Expired unpaid booking {} and released {} room(s)",
                    booking.id(), booking.roomCount());
        }
        return lapsed.size();
    }

    /**
     * Expire one lapsed hold immediately, in its own committed transaction.
     *
     * <p>{@code REQUIRES_NEW} is the entire point. The caller is {@code PaymentService}, which
     * discovers the lapse and then rejects the payment by throwing — and that throw rolls its own
     * transaction back. Releasing the rooms in the caller's transaction would therefore undo itself,
     * leaving rooms held by a booking that was supposed to have expired. A suspended, separately
     * committed transaction survives the caller's rollback, which is what makes the rooms genuinely
     * return to sale rather than waiting for the next sweep.
     *
     * <p>Re-reads and re-checks the status rather than trusting the caller's copy: the sweeper may
     * have reached this booking first, and expiring an already-expired booking would fail the
     * lifecycle guard.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireHoldNow(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));

        if (booking.status() != BookingStatus.PENDING_PAYMENT) {
            return;
        }

        booking.expire();
        inventoryService.release(booking.roomTypeId(), booking.stay(), booking.roomCount());
        bookingRepository.save(booking);
        log.info("Expired lapsed hold on booking {} and released {} room(s)",
                booking.id(), booking.roomCount());
    }

    private void rejectStayInThePast(DateRange stay, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (stay.checkIn().isBefore(today)) {
            throw new ValidationException("checkIn " + stay.checkIn() + " is in the past");
        }
    }

    private void rejectOverlongStay(DateRange stay) {
        if (stay.nightCount() > MAX_BOOKABLE_NIGHTS) {
            throw new ValidationException("stays longer than " + MAX_BOOKABLE_NIGHTS
                    + " nights are not bookable online");
        }
    }
}
