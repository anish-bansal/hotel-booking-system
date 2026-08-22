package com.rupeek.hotelbooking.domain.port;

import com.rupeek.hotelbooking.domain.model.RoomType;
import java.util.Optional;
import java.util.UUID;

/** Outbound port for direct room-type lookup during booking. */
public interface RoomTypeRepository {

    Optional<RoomType> findById(UUID id);
}
