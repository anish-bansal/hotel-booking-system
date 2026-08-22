package com.rupeek.hotelbooking.domain.model;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The owner account that properties hang off — one hotel, or a chain of fifty.
 *
 * <p><b>Why this type exists at all.</b> The brief asks for a single standalone property to be a
 * natural special case of the multi-property structure rather than a separate hard-coded path. The
 * way to get that is to refuse to model "single" and "chain" as two things. There is exactly one
 * ownership shape in this system — a group holding one or more properties — and a standalone hotel
 * is a group whose list happens to have one element. {@link #isStandalone()} is a <em>derived
 * question</em>, not stored state, so no code can branch on a flag that drifts out of sync with
 * reality, and onboarding the fiftieth property to a chain runs the identical code path as
 * onboarding the first property of a solo hotel.
 */
@Entity
@Table(name = "property_group")
public class PropertyGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    /**
     * Cascade is deliberate: the group is the aggregate root for ownership, so persisting a group
     * persists the properties and room types beneath it as one atomic act of onboarding.
     *
     * <p><b>Why a {@code Set} and not a {@code List}.</b> Hibernate treats an unordered {@code List}
     * as a "bag", and refuses to fetch-join two bags in one query
     * ({@code MultipleBagFetchException}). Since a group eagerly fetches its properties and each
     * property eagerly fetches its room types, a {@code List} at both levels would be exactly that
     * forbidden pair. Using {@code Set} with {@code @OrderBy} sidesteps the whole problem and keeps
     * a deterministic order in API responses; the accessor still hands callers a {@code List} so
     * nothing downstream has to care.
     *
     * <p>Eager rather than lazy because {@code open-in-view} is off: the object graph is mapped to
     * DTOs after the service transaction has closed, so anything left lazy would throw there. For a
     * graph this small — an owner, its properties, their room types — that is the right trade; a
     * production system with thousand-property chains would page instead.
     */
    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("name")
    private Set<Property> properties = new LinkedHashSet<>();

    protected PropertyGroup() {
        // for JPA
    }

    private PropertyGroup(String name, String contactEmail) {
        this.name = name;
        this.contactEmail = contactEmail;
    }

    public static PropertyGroup named(String name, String contactEmail) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("property group name is required");
        }
        if (contactEmail == null || !contactEmail.contains("@")) {
            throw new ValidationException("a valid contact email is required");
        }
        return new PropertyGroup(name.trim(), contactEmail.trim());
    }

    /**
     * The only way a property joins a group. Keeping the back-reference assignment inside the
     * aggregate root means callers cannot create a half-wired bidirectional relationship.
     */
    public Property addProperty(Property property) {
        if (property == null) {
            throw new ValidationException("property is required");
        }
        properties.add(property);
        property.assignTo(this);
        return property;
    }

    /** Derived, never stored — see the class comment. */
    public boolean isStandalone() {
        return properties.size() == 1;
    }

    public int propertyCount() {
        return properties.size();
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String contactEmail() {
        return contactEmail;
    }

    /**
     * An immutable, deterministically ordered snapshot. Returning a {@code List} rather than the
     * underlying {@code Set} keeps indexed access convenient for callers, and returning a copy means
     * no caller can mutate the aggregate's collection behind {@link #addProperty}'s back.
     */
    public List<Property> properties() {
        return List.copyOf(properties);
    }
}
