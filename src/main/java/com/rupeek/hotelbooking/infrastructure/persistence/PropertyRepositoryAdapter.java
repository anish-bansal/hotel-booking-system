package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.port.PropertyRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class PropertyRepositoryAdapter implements PropertyRepository {

    private final PropertyJpaRepository jpa;

    PropertyRepositoryAdapter(PropertyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Property save(Property property) {
        return jpa.save(property);
    }

    @Override
    public Optional<Property> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public List<Property> findByLocation(String city, String locality) {
        // Location normalises to lower case on the way in, so queries must too.
        String normalisedCity = normalise(city);
        return locality == null || locality.isBlank()
                ? jpa.findByLocationCity(normalisedCity)
                : jpa.findByLocationCityAndLocationLocality(normalisedCity, normalise(locality));
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
