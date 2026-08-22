package com.rupeek.hotelbooking.domain.search;

import com.rupeek.hotelbooking.domain.model.RoomType;

/**
 * One independent narrowing rule applied to the room types <em>within</em> a matching property.
 *
 * <p><b>Why this exists alongside {@link PropertyFilter}.</b> The two answer different questions.
 * {@code PropertyFilter} decides "should this hotel appear at all?" — it is the cheap pass that
 * eliminates whole properties. This one decides "which of its rooms may I actually be shown?"
 *
 * <p>Collapsing them loses correctness, not just precision. A price ceiling evaluated only against a
 * property's cheapest room lets that property through — correctly, it has something affordable — and
 * then every other room type rides in with it, including the ones far above the ceiling. A guest who
 * asked for rooms under ten thousand a night should not be quoted twenty-five; a filter that decides
 * per property cannot express that, because the unit being filtered is the wrong size.
 *
 * <p>Same open/closed contract as {@link PropertyFilter}: {@code PropertySearchService} holds a
 * {@code List<RoomTypeFilter>} and never names an implementation, so a new rule — "only rooms with a
 * balcony", "only rooms sleeping four or more" — is a new class and a bean declaration.
 */
public interface RoomTypeFilter {

    /** Whether the guest supplied the criteria this filter acts on. */
    boolean isApplicable(PropertySearchCriteria criteria);

    /** Whether this room type satisfies the criteria. Only called when {@link #isApplicable}. */
    boolean matches(RoomType roomType, PropertySearchCriteria criteria);

    /** Used in diagnostics so a zero-result search can say which filter eliminated what. */
    default String name() {
        return getClass().getSimpleName();
    }
}
