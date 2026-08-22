package com.rupeek.hotelbooking.domain.search;

import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.vo.Money;

/**
 * Keeps a room type only if its nightly rate is inside the price range the guest asked for.
 *
 * <p>The counterpart to {@link PriceRangeFilter}. That one asks whether a property has
 * <em>anything</em> in budget and so decides whether the hotel is worth showing; this one decides
 * which of its rooms are honest to quote. Both are needed: without the property-level pass, search
 * would load and price room types for hotels that could never match; without this one, a property
 * that qualifies on its cheapest room would still advertise its most expensive.
 */
public class RoomTypePriceFilter implements RoomTypeFilter {

    @Override
    public boolean isApplicable(PropertySearchCriteria criteria) {
        return criteria.minNightlyPrice() != null || criteria.maxNightlyPrice() != null;
    }

    @Override
    public boolean matches(RoomType roomType, PropertySearchCriteria criteria) {
        Money rate = roomType.basePricePerNight();
        if (criteria.minNightlyPrice() != null && rate.isLessThan(criteria.minNightlyPrice())) {
            return false;
        }
        return criteria.maxNightlyPrice() == null || !rate.isGreaterThan(criteria.maxNightlyPrice());
    }
}
