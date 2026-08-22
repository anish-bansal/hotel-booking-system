package com.rupeek.hotelbooking.domain.policy;

import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Money;

/**
 * Works out what a stay costs.
 *
 * <p>Pricing is a single implementation today ({@link StandardPricingStrategy}: rate x nights x
 * rooms), but it sits behind an interface because pricing is the part of a booking platform most
 * certain to grow — weekend surge, length-of-stay discounts, occupancy-based yield management,
 * promo codes. Every one of those is a new implementation, or a decorator wrapping this one, and
 * none of them require {@code BookingService} to change: it asks for a quote and is told a number.
 *
 * <p>The request is a parameter object rather than a long argument list so that adding an input
 * (say, current occupancy for yield pricing) does not break every implementation's signature.
 */
public interface PricingStrategy {

    Money quote(PricingRequest request);

    record PricingRequest(RoomType roomType, DateRange stay, int rooms, int guests) {
    }
}
