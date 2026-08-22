package com.rupeek.hotelbooking.domain.search;

import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.util.Optional;

/**
 * Keeps properties whose cheapest nightly rate falls inside the guest's budget.
 *
 * <p>Judged on the cheapest room type rather than an average, because a guest who sets a ceiling of
 * ₹5,000 wants to see the hotel that has one ₹4,000 room even if its suites cost ₹20,000. Filtering
 * on nightly rate rather than trip total also keeps the meaning stable when the stay length changes.
 */
public class PriceRangeFilter implements PropertyFilter {

    @Override
    public boolean isApplicable(PropertySearchCriteria criteria) {
        return criteria.minNightlyPrice() != null || criteria.maxNightlyPrice() != null;
    }

    @Override
    public boolean matches(Property property, PropertySearchCriteria criteria) {
        Optional<Money> cheapest = property.cheapestNightlyRate();
        if (cheapest.isEmpty()) {
            return false;
        }
        Money rate = cheapest.get();
        if (criteria.minNightlyPrice() != null && rate.isLessThan(criteria.minNightlyPrice())) {
            return false;
        }
        return criteria.maxNightlyPrice() == null || !rate.isGreaterThan(criteria.maxNightlyPrice());
    }
}
