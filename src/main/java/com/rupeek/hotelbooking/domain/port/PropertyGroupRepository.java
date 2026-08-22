package com.rupeek.hotelbooking.domain.port;

import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for owner accounts.
 *
 * <p>Every repository in this package is an interface owned by the <em>domain</em>, expressed in the
 * domain's own vocabulary, with its implementation living in {@code infrastructure.persistence}.
 * That inversion is the point: the application layer depends on these interfaces and never on
 * Spring Data, so swapping H2 for Postgres — or for an in-memory map in a unit test — touches one
 * adapter class and nothing else.
 */
public interface PropertyGroupRepository {

    PropertyGroup save(PropertyGroup group);

    Optional<PropertyGroup> findById(UUID id);
}
