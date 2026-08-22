package com.rupeek.hotelbooking.domain.policy;

import com.rupeek.hotelbooking.domain.vo.Money;

/**
 * The flat baseline: nightly rate x nights x rooms.
 *
 * <p>Intentionally boring. Its value is that it proves the seam works and gives anything more
 * elaborate a base to decorate.
 */
public class StandardPricingStrategy implements PricingStrategy {

    @Override
    public Money quote(PricingRequest request) {
        long units = request.stay().nightCount() * request.rooms();
        return request.roomType().basePricePerNight().times(units);
    }
}
