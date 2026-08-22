package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import com.rupeek.hotelbooking.domain.port.PropertyGroupRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class PropertyGroupRepositoryAdapter implements PropertyGroupRepository {

    private final PropertyGroupJpaRepository jpa;

    PropertyGroupRepositoryAdapter(PropertyGroupJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PropertyGroup save(PropertyGroup group) {
        return jpa.save(group);
    }

    @Override
    public Optional<PropertyGroup> findById(UUID id) {
        return jpa.findById(id);
    }
}
