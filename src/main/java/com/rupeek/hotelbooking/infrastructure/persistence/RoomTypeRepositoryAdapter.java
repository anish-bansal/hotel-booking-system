package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.port.RoomTypeRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class RoomTypeRepositoryAdapter implements RoomTypeRepository {

    private final RoomTypeJpaRepository jpa;

    RoomTypeRepositoryAdapter(RoomTypeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RoomType> findById(UUID id) {
        return jpa.findById(id);
    }
}
