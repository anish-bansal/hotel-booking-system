package com.rupeek.hotelbooking.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rupeek.hotelbooking.domain.exception.InventoryUnavailableException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The last line of defence against overselling. Even with a bug in the locking layer, held rooms
 * must never exceed the hotel's room count - these tests assert that guard directly.
 */
class RoomInventoryTest {

    private static final LocalDate NIGHT = LocalDate.of(2026, 3, 10);

    @Test
    void tracksAvailability() {
        RoomInventory inventory = RoomInventory.forNight(UUID.randomUUID(), NIGHT, 5);
        assertThat(inventory.available()).isEqualTo(5);

        inventory.hold(2);
        assertThat(inventory.available()).isEqualTo(3);
        assertThat(inventory.heldRooms()).isEqualTo(2);

        inventory.release(1);
        assertThat(inventory.available()).isEqualTo(4);
    }

    @Test
    @DisplayName("cannot hold more rooms than the hotel has, even by one")
    void refusesToOversell() {
        RoomInventory inventory = RoomInventory.forNight(UUID.randomUUID(), NIGHT, 1);
        inventory.hold(1);

        assertThatThrownBy(() -> inventory.hold(1))
                .isInstanceOf(InventoryUnavailableException.class)
                .hasMessageContaining("Only 0 room(s) available");
        assertThat(inventory.heldRooms()).isEqualTo(1);
    }

    @Test
    void refusesToReleaseMoreThanIsHeld() {
        RoomInventory inventory = RoomInventory.forNight(UUID.randomUUID(), NIGHT, 5);
        inventory.hold(2);

        assertThatThrownBy(() -> inventory.release(3))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only 2 held");
    }

    @Test
    void rejectsNonPositiveQuantities() {
        RoomInventory inventory = RoomInventory.forNight(UUID.randomUUID(), NIGHT, 5);

        assertThatThrownBy(() -> inventory.hold(0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> inventory.release(0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RoomInventory.forNight(UUID.randomUUID(), NIGHT, 0))
                .isInstanceOf(ValidationException.class);
    }
}
