package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.RoomType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RoomTypeJpaRepository extends JpaRepository<RoomType, UUID> {
}
