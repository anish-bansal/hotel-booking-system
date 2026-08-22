package com.rupeek.hotelbooking.domain.vo;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Locale;
import java.util.Objects;

/**
 * Where a property physically is.
 *
 * <p>City and locality are normalised to lower case at construction so that search never has to
 * think about casing. Normalising on the way in beats normalising at every query site.
 */
@Embeddable
public final class Location {

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "locality", nullable = false)
    private String locality;

    @Column(name = "address_line", nullable = false)
    private String addressLine;

    protected Location() {
        // for JPA
    }

    private Location(String city, String locality, String addressLine) {
        this.city = city;
        this.locality = locality;
        this.addressLine = addressLine;
    }

    public static Location of(String city, String locality, String addressLine) {
        if (isBlank(city) || isBlank(locality) || isBlank(addressLine)) {
            throw new ValidationException("city, locality and addressLine are all required");
        }
        return new Location(normalise(city), normalise(locality), addressLine.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive match used by the location stage of search. */
    public boolean matchesCity(String candidate) {
        return candidate != null && city.equals(normalise(candidate));
    }

    public boolean matchesLocality(String candidate) {
        return candidate != null && locality.equals(normalise(candidate));
    }

    public String city() {
        return city;
    }

    public String locality() {
        return locality;
    }

    public String addressLine() {
        return addressLine;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Location other)) {
            return false;
        }
        return city.equals(other.city)
                && locality.equals(other.locality)
                && addressLine.equals(other.addressLine);
    }

    @Override
    public int hashCode() {
        return Objects.hash(city, locality, addressLine);
    }

    @Override
    public String toString() {
        return addressLine + ", " + locality + ", " + city;
    }
}
