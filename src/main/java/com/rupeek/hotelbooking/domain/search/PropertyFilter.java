package com.rupeek.hotelbooking.domain.search;

import com.rupeek.hotelbooking.domain.model.Property;

/**
 * One independent narrowing rule applied to search candidates.
 *
 * <p><b>Why search is built this way.</b> The brief asks for filters that can be added later without
 * reworking the search. So {@code PropertySearchService} does not know what filters exist: it is
 * handed a {@code List<PropertyFilter>} and folds it over the candidates. A new filter is a new
 * class implementing this interface and declared as a bean — the search service, the criteria
 * plumbing and every existing filter stay untouched. Conversely, deleting a filter cannot break
 * another, because none of them can see each other.
 *
 * <p>The two-method split matters. {@link #isApplicable} answers "did the guest ask about this at
 * all?" and {@link #matches} answers "does this property satisfy it?". Folding those together into
 * one method that returns {@code true} when the criterion is absent works, but it hides the
 * distinction between <em>passed</em> and <em>not asked</em> — which is exactly what you want to
 * report when explaining why a search returned nothing.
 */
public interface PropertyFilter {

    /** Whether the guest supplied the criteria this filter acts on. */
    boolean isApplicable(PropertySearchCriteria criteria);

    /** Whether this property satisfies the criteria. Only called when {@link #isApplicable}. */
    boolean matches(Property property, PropertySearchCriteria criteria);

    /** Used in diagnostics so a zero-result search can say which filter eliminated what. */
    default String name() {
        return getClass().getSimpleName();
    }
}
