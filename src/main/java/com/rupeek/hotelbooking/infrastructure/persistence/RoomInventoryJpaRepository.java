package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.RoomInventory;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RoomInventoryJpaRepository extends JpaRepository<RoomInventory, UUID> {

    /** Lock-free read for search. No {@code @Lock}, deliberately - browsing must not block booking. */
    @Query("""
            select i from RoomInventory i
            where i.roomTypeId in :roomTypeIds
              and i.date between :from and :to
            """)
    List<RoomInventory> findInRange(@Param("roomTypeIds") Collection<UUID> roomTypeIds,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);

    /**
     * The serialising read: {@code SELECT ... FOR UPDATE} over every night of a stay.
     *
     * <p>{@code PESSIMISTIC_WRITE} is what Hibernate turns into {@code FOR UPDATE}. The
     * {@code order by i.date asc} is load-bearing rather than presentational: it fixes a single
     * global order in which contending transactions acquire these row locks, which is what makes a
     * deadlock between two overlapping stays impossible. Remove the ordering and a 3rd-5th booking
     * racing a 4th-6th booking can each end up holding a night the other needs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select i from RoomInventory i
            where i.roomTypeId = :roomTypeId
              and i.date between :from and :to
            order by i.date asc
            """)
    List<RoomInventory> lockRangeForUpdate(@Param("roomTypeId") UUID roomTypeId,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);

    long countByRoomTypeId(UUID roomTypeId);
}
