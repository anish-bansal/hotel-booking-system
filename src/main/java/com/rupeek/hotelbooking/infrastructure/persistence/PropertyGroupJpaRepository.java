package com.rupeek.hotelbooking.infrastructure.persistence;

import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PropertyGroupJpaRepository extends JpaRepository<PropertyGroup, UUID> {
}
