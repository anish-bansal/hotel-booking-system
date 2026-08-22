package com.rupeek.hotelbooking.domain.model;

import com.rupeek.hotelbooking.domain.exception.NotFoundException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import com.rupeek.hotelbooking.domain.vo.Location;
import com.rupeek.hotelbooking.domain.vo.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A single bookable hotel, owned by exactly one {@link PropertyGroup}. */
@Entity
@Table(name = "property")
public class Property {

    private static final int MIN_STAR_RATING = 1;
    private static final int MAX_STAR_RATING = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Embedded
    private Location location;

    @Column(name = "star_rating", nullable = false)
    private int starRating;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "property_amenity", joinColumns = @JoinColumn(name = "property_id"))
    @Column(name = "amenity", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<Amenity> amenities = EnumSet.noneOf(Amenity.class);

    /** A {@code Set} with {@code @OrderBy} for the same reason as {@code PropertyGroup.properties}. */
    @OneToMany(mappedBy = "property", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("name")
    private Set<RoomType> roomTypes = new LinkedHashSet<>();

    /**
     * Which cancellation policy applies to bookings at this property, stored as the policy's code
     * rather than a Java type. The property row therefore has no dependency on the class that
     * implements the rule, which is what lets a new policy be added without touching this entity.
     */
    @Column(name = "cancellation_policy_code", nullable = false)
    private String cancellationPolicyCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private PropertyGroup group;

    protected Property() {
        // for JPA
    }

    private Property(String name, Location location, int starRating, Set<Amenity> amenities,
                     String cancellationPolicyCode) {
        this.name = name;
        this.location = location;
        this.starRating = starRating;
        this.amenities = amenities.isEmpty() ? EnumSet.noneOf(Amenity.class) : EnumSet.copyOf(amenities);
        this.cancellationPolicyCode = cancellationPolicyCode;
    }

    public static Property create(String name, Location location, int starRating,
                                  Set<Amenity> amenities, String cancellationPolicyCode) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("property name is required");
        }
        if (location == null) {
            throw new ValidationException("property location is required");
        }
        if (starRating < MIN_STAR_RATING || starRating > MAX_STAR_RATING) {
            throw new ValidationException("starRating must be within ["
                    + MIN_STAR_RATING + ", " + MAX_STAR_RATING + "] but was " + starRating);
        }
        if (cancellationPolicyCode == null || cancellationPolicyCode.isBlank()) {
            throw new ValidationException("cancellationPolicyCode is required");
        }
        return new Property(name.trim(), location, starRating,
                amenities == null ? Set.of() : amenities, cancellationPolicyCode);
    }

    public RoomType addRoomType(RoomType roomType) {
        if (roomType == null) {
            throw new ValidationException("roomType is required");
        }
        boolean duplicate = roomTypes.stream().anyMatch(rt -> rt.name().equalsIgnoreCase(roomType.name()));
        if (duplicate) {
            throw new ValidationException("duplicate room type '" + roomType.name()
                    + "' for property " + name);
        }
        roomTypes.add(roomType);
        roomType.assignTo(this);
        return roomType;
    }

    public RoomType requireRoomType(UUID roomTypeId) {
        return findRoomType(roomTypeId)
                .orElseThrow(() -> new NotFoundException("RoomType", roomTypeId));
    }

    public Optional<RoomType> findRoomType(UUID roomTypeId) {
        return roomTypes.stream().filter(rt -> rt.id().equals(roomTypeId)).findFirst();
    }

    public boolean hasAllAmenities(Set<Amenity> required) {
        return amenities.containsAll(required);
    }

    /** Cheapest nightly rate across room types — the "from ₹X" figure search results show. */
    public Optional<Money> cheapestNightlyRate() {
        return roomTypes.stream().map(RoomType::basePricePerNight).min(Money::compareTo);
    }

    void assignTo(PropertyGroup group) {
        this.group = group;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Location location() {
        return location;
    }

    public int starRating() {
        return starRating;
    }

    public Set<Amenity> amenities() {
        return Collections.unmodifiableSet(amenities);
    }

    /** Immutable, name-ordered snapshot — see {@code PropertyGroup.properties()}. */
    public List<RoomType> roomTypes() {
        return List.copyOf(roomTypes);
    }

    public String cancellationPolicyCode() {
        return cancellationPolicyCode;
    }

    public PropertyGroup group() {
        return group;
    }
}
