package com.rupeek.hotelbooking.domain.model;

import com.rupeek.hotelbooking.domain.exception.InventoryUnavailableException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;

/**
 * How many rooms of one room type are already committed on one night.
 *
 * <p>This is the row every concurrent booking fights over, so it is deliberately the smallest,
 * dumbest thing that can express the contention: a ceiling, a running count, and two methods that
 * move the count. All the interesting concurrency control lives one layer up in
 * {@code InventoryService}, which locks these rows in a deterministic order; this class's only
 * job is to make an over-hold impossible even if that locking were removed.
 *
 * <p>Note {@code roomTypeId} is a bare id rather than a {@code @ManyToOne}. Inventory is its own
 * aggregate — hundreds of rows per room type — and referencing across aggregates by identity keeps
 * a lock on one night's inventory from dragging the whole property object graph into the session.
 */
@Entity
@Table(name = "room_inventory",
        uniqueConstraints = @UniqueConstraint(name = "uk_inventory_room_type_date",
                columnNames = {"room_type_id", "stay_date"}))
public class RoomInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "room_type_id", nullable = false, updatable = false)
    private UUID roomTypeId;

    @Column(name = "stay_date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "total_rooms", nullable = false)
    private int totalRooms;

    /**
     * Rooms committed for this night, whether by a confirmed booking or by a booking still holding
     * inventory while it awaits payment. One counter covers both because availability does not care
     * <em>why</em> a room is unavailable.
     */
    @Column(name = "held_rooms", nullable = false)
    private int heldRooms;

    protected RoomInventory() {
        // for JPA
    }

    private RoomInventory(UUID roomTypeId, LocalDate date, int totalRooms) {
        this.roomTypeId = roomTypeId;
        this.date = date;
        this.totalRooms = totalRooms;
        this.heldRooms = 0;
    }

    public static RoomInventory forNight(UUID roomTypeId, LocalDate date, int totalRooms) {
        if (roomTypeId == null || date == null) {
            throw new ValidationException("roomTypeId and date are required");
        }
        if (totalRooms < 1) {
            throw new ValidationException("totalRooms must be at least 1 but was " + totalRooms);
        }
        return new RoomInventory(roomTypeId, date, totalRooms);
    }

    public int available() {
        return totalRooms - heldRooms;
    }

    public boolean canHold(int rooms) {
        return rooms > 0 && available() >= rooms;
    }

    /**
     * Commit {@code rooms} for this night, or refuse. The guard is the last line of defence: even
     * with a bug in the locking layer, {@code heldRooms} can never exceed {@code totalRooms}.
     */
    public void hold(int rooms) {
        if (rooms < 1) {
            throw new ValidationException("rooms to hold must be at least 1 but was " + rooms);
        }
        if (!canHold(rooms)) {
            throw new InventoryUnavailableException(date, rooms, available());
        }
        heldRooms += rooms;
    }

    /** Give rooms back on cancellation or hold expiry, making them discoverable again. */
    public void release(int rooms) {
        if (rooms < 1) {
            throw new ValidationException("rooms to release must be at least 1 but was " + rooms);
        }
        if (rooms > heldRooms) {
            throw new ValidationException("cannot release " + rooms + " room(s) on " + date
                    + "; only " + heldRooms + " held");
        }
        heldRooms -= rooms;
    }

    public UUID id() {
        return id;
    }

    public UUID roomTypeId() {
        return roomTypeId;
    }

    public LocalDate date() {
        return date;
    }

    public int totalRooms() {
        return totalRooms;
    }

    public int heldRooms() {
        return heldRooms;
    }
}
