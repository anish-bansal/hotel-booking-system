package com.rupeek.hotelbooking.domain.port;

import com.rupeek.hotelbooking.domain.model.RoomInventory;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port for nightly room counts — the only contended data in the system.
 *
 * <p>The two read methods exist as separate operations on purpose, because they answer the same
 * question under completely different constraints:
 *
 * <ul>
 *   <li>{@link #findForAvailabilityCheck} is the <em>optimistic, lock-free</em> read used by search.
 *       Search touches many room types and must stay cheap; a stale answer there is acceptable
 *       because the result is only ever a suggestion, and booking re-checks under a lock anyway.
 *   <li>{@link #lockNightsForUpdate} is the <em>serialising</em> read used at the moment of booking.
 *       It takes a pessimistic write lock on every night of the stay and returns them in ascending
 *       date order, which is the mechanism that makes double-booking impossible.
 * </ul>
 *
 * <p>Naming them for their purpose rather than their shape ("findByRoomTypeAndDates", twice) is
 * what stops a future caller from reaching for the cheap one where correctness needs the strict one.
 */
public interface RoomInventoryRepository {

    List<RoomInventory> saveAll(Collection<RoomInventory> inventory);

    /**
     * Lock-free read across many room types in one round trip, for search-time availability.
     *
     * @param nightFrom first night, inclusive
     * @param nightTo   last night, inclusive (i.e. checkout minus one day)
     */
    List<RoomInventory> findForAvailabilityCheck(Collection<UUID> roomTypeIds,
                                                LocalDate nightFrom,
                                                LocalDate nightTo);

    /**
     * Acquire a pessimistic write lock on every night in {@code [nightFrom, nightTo]} for one room
     * type, returning the rows sorted by date ascending.
     *
     * <p><b>The ordering is not cosmetic.</b> Two overlapping bookings that grabbed their nights in
     * opposite orders could each hold a row the other needs and deadlock. Because every caller
     * acquires locks in the same ascending-date sequence, a cycle cannot form: whichever
     * transaction reaches the lowest contended night first wins the whole range, and the other
     * simply waits. Global lock ordering is the textbook cure for deadlock, and here the date
     * <em>is</em> the natural global order.
     */
    List<RoomInventory> lockNightsForUpdate(UUID roomTypeId, LocalDate nightFrom, LocalDate nightTo);

    long countByRoomTypeId(UUID roomTypeId);
}
