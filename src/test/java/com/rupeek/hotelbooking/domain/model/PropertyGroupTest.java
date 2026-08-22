package com.rupeek.hotelbooking.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.policy.FlexibleCancellationPolicy;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import com.rupeek.hotelbooking.domain.vo.Location;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The single-property-as-special-case claim, tested. A standalone hotel and a chain differ only in
 * list length - and a standalone becomes a chain by being handed a second property, with no
 * conversion step, because there was never a "standalone" representation to convert away from.
 */
class PropertyGroupTest {

    @Test
    @DisplayName("a group with one property reports itself standalone")
    void onePropertyIsStandalone() {
        PropertyGroup group = PropertyGroup.named("Solo Stay", "owner@example.com");
        group.addProperty(property("The Loft"));

        assertThat(group.isStandalone()).isTrue();
        assertThat(group.propertyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("adding a second property turns a standalone owner into a chain, no migration")
    void standaloneBecomesChainByGrowing() {
        PropertyGroup group = PropertyGroup.named("Coastline", "owner@example.com");
        group.addProperty(property("Coastline Bengaluru"));
        assertThat(group.isStandalone()).isTrue();

        group.addProperty(property("Coastline Goa"));

        assertThat(group.isStandalone()).isFalse();
        assertThat(group.propertyCount()).isEqualTo(2);
    }

    @Test
    void wiresTheBackReferenceSoTheGraphIsNeverHalfConnected() {
        PropertyGroup group = PropertyGroup.named("Coastline", "owner@example.com");
        Property added = group.addProperty(property("Coastline Bengaluru"));

        assertThat(added.group()).isSameAs(group);
        assertThat(group.properties()).containsExactly(added);
    }

    @Test
    void exposesPropertiesAsAnUnmodifiableView() {
        PropertyGroup group = PropertyGroup.named("Coastline", "owner@example.com");
        group.addProperty(property("Coastline Bengaluru"));

        assertThatThrownBy(() -> group.properties().add(property("Sneaky")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesItsOwnFields() {
        assertThatThrownBy(() -> PropertyGroup.named(" ", "owner@example.com"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PropertyGroup.named("Coastline", "not-an-email"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsDuplicateRoomTypeNamesWithinAProperty() {
        Property property = property("Coastline Bengaluru");
        property.addRoomType(RoomType.create("Deluxe King", 2, 4, Money.inr(5000)));

        assertThatThrownBy(() -> property.addRoomType(
                RoomType.create("deluxe king", 2, 2, Money.inr(6000))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate room type");
    }

    private static Property property(String name) {
        return Property.create(name,
                Location.of("Bengaluru", "Indiranagar", "100 Ft Road"),
                4, Set.of(Amenity.WIFI), FlexibleCancellationPolicy.CODE);
    }
}
