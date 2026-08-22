package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.Property;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface PropertyJpaRepository extends JpaRepository<Property, UUID> {

    /**
     * Two methods rather than one with a nullable locality parameter. A single query carrying
     * {@code (:locality is null or ...)} works, but it hands the database a predicate it cannot use
     * an index for and makes the intent harder to read than simply having the caller pick.
     *
     * <p>The entity graph pulls room types and amenities in with the properties. Search needs both
     * for every candidate, so fetching them eagerly here turns what would be 2N follow-up queries
     * into part of the original one.
     */
    @EntityGraph(attributePaths = {"roomTypes", "amenities"})
    List<Property> findByLocationCity(String city);

    @EntityGraph(attributePaths = {"roomTypes", "amenities"})
    List<Property> findByLocationCityAndLocationLocality(String city, String locality);
}
