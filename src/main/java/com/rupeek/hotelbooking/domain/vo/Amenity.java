package com.rupeek.hotelbooking.domain.vo;

/**
 * Facilities a property offers.
 *
 * <p>An enum rather than free text so that filtering is exact and typo-free. The trade-off is that
 * adding an amenity is a code change; a production system would move this to a reference table so
 * that operations can add one without a deploy. For this exercise the type safety is worth more —
 * see DESIGN.md.
 */
public enum Amenity {
    WIFI,
    POOL,
    GYM,
    SPA,
    PARKING,
    RESTAURANT,
    BAR,
    AIR_CONDITIONING,
    PET_FRIENDLY,
    AIRPORT_SHUTTLE,
    BREAKFAST_INCLUDED,
    WHEELCHAIR_ACCESSIBLE
}
