package com.rupeek.hotelbooking.domain.model;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.vo.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A category of interchangeable rooms — "Deluxe King", "Standard Twin" — not a physical room.
 *
 * <p><b>The central modelling decision of this service.</b> Inventory is tracked as a count per
 * (room type, night), never as an assignment of a named room. Guests do not care which of the
 * eight identical deluxe rooms they get, and hotels reassign rooms at the front desk anyway. The
 * payoff is that availability becomes integer arithmetic over a date range instead of a matching
 * problem over individual rooms, which makes the concurrency-critical section small and provably
 * correct. The cost is that "give me room 402" cannot be expressed — see the trade-off table in
 * DESIGN.md.
 */
@Entity
@Table(name = "room_type")
public class RoomType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    /** Guests one room of this type sleeps. */
    @Column(name = "max_occupancy", nullable = false)
    private int maxOccupancy;

    /** How many rooms of this type the hotel has. Becomes the nightly inventory ceiling. */
    @Column(name = "total_rooms", nullable = false)
    private int totalRooms;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "base_price_amount", nullable = false)),
            @AttributeOverride(name = "currencyCode", column = @Column(name = "base_price_currency", nullable = false))
    })
    private Money basePricePerNight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    protected RoomType() {
        // for JPA
    }

    private RoomType(String name, int maxOccupancy, int totalRooms, Money basePricePerNight) {
        this.name = name;
        this.maxOccupancy = maxOccupancy;
        this.totalRooms = totalRooms;
        this.basePricePerNight = basePricePerNight;
    }

    public static RoomType create(String name, int maxOccupancy, int totalRooms, Money basePricePerNight) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("room type name is required");
        }
        if (maxOccupancy < 1) {
            throw new ValidationException("maxOccupancy must be at least 1 but was " + maxOccupancy);
        }
        if (totalRooms < 1) {
            throw new ValidationException("totalRooms must be at least 1 but was " + totalRooms);
        }
        if (basePricePerNight == null || basePricePerNight.isZero()) {
            throw new ValidationException("basePricePerNight must be a positive amount");
        }
        return new RoomType(name.trim(), maxOccupancy, totalRooms, basePricePerNight);
    }

    /** Whether {@code guests} fit into {@code rooms} rooms of this type. */
    public boolean canHost(int guests, int rooms) {
        return guests >= 1 && rooms >= 1 && guests <= (long) maxOccupancy * rooms;
    }

    /** Fewest rooms of this type needed to sleep {@code guests}. */
    public int roomsNeededFor(int guests) {
        return (guests + maxOccupancy - 1) / maxOccupancy;
    }

    void assignTo(Property property) {
        this.property = property;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int maxOccupancy() {
        return maxOccupancy;
    }

    public int totalRooms() {
        return totalRooms;
    }

    public Money basePricePerNight() {
        return basePricePerNight;
    }

    public Property property() {
        return property;
    }
}
