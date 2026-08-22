package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.RoomInventory;
import com.rupeek.hotelbooking.domain.port.RoomInventoryRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class RoomInventoryRepositoryAdapter implements RoomInventoryRepository {

    private final RoomInventoryJpaRepository jpa;

    RoomInventoryRepositoryAdapter(RoomInventoryJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<RoomInventory> saveAll(Collection<RoomInventory> inventory) {
        return jpa.saveAll(inventory);
    }

    @Override
    public List<RoomInventory> findForAvailabilityCheck(Collection<UUID> roomTypeIds,
                                                       LocalDate nightFrom, LocalDate nightTo) {
        return jpa.findInRange(roomTypeIds, nightFrom, nightTo);
    }

    @Override
    public List<RoomInventory> lockNightsForUpdate(UUID roomTypeId, LocalDate nightFrom,
                                                   LocalDate nightTo) {
        return jpa.lockRangeForUpdate(roomTypeId, nightFrom, nightTo);
    }

    @Override
    public long countByRoomTypeId(UUID roomTypeId) {
        return jpa.countByRoomTypeId(roomTypeId);
    }
}
