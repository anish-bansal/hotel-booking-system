package com.rupeek.hotelbooking.domain.port;

import com.rupeek.hotelbooking.domain.model.Property;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound port for properties. */
public interface PropertyRepository {

    Property save(Property property);

    Optional<Property> findById(UUID id);

    /**
     * Location is resolved in the datastore rather than by an in-memory filter, because it is the
     * one criterion that meaningfully shrinks the candidate set. Everything narrower — price,
     * amenities, rating — is a composable {@code PropertyFilter} applied to the result.
     *
     * @param locality optional; when null, the whole city is returned
     */
    List<Property> findByLocation(String city, String locality);
}
