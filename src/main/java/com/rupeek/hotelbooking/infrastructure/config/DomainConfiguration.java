package com.rupeek.hotelbooking.infrastructure.config;

import com.rupeek.hotelbooking.domain.policy.CancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.CancellationPolicyRegistry;
import com.rupeek.hotelbooking.domain.policy.FlexibleCancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.ModerateCancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.NonRefundableCancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.PricingStrategy;
import com.rupeek.hotelbooking.domain.policy.StandardPricingStrategy;
import com.rupeek.hotelbooking.domain.port.PaymentGateway;
import com.rupeek.hotelbooking.domain.port.PaymentGatewayRegistry;
import com.rupeek.hotelbooking.domain.search.AmenityFilter;
import com.rupeek.hotelbooking.domain.search.PriceRangeFilter;
import com.rupeek.hotelbooking.domain.search.PropertyFilter;
import com.rupeek.hotelbooking.domain.search.RoomTypeFilter;
import com.rupeek.hotelbooking.domain.search.RoomTypePriceFilter;
import com.rupeek.hotelbooking.domain.search.StarRatingFilter;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the domain's policies, filters and registries.
 *
 * <p><b>Why these are {@code @Bean} methods and not {@code @Component} classes.</b> The entities in
 * this codebase carry JPA annotations — a deliberate, documented trade — but the policies, pricing
 * strategies and search filters carry no framework annotations at all. Declaring them here keeps
 * them plain objects that can be constructed with {@code new} in a unit test, with no Spring context
 * and no reflection, while still being injectable in production. It also puts the entire list of
 * active policies and filters on one screen, which is the fastest way for a reader to learn what
 * behaviour is switched on.
 *
 * <p>Adding a search filter or a cancellation policy means writing the class and adding one line
 * here. Nothing else in the codebase changes — that is the extensibility claim, and this file is
 * where it is cashed in.
 */
@Configuration
public class DomainConfiguration {

    /**
     * Time is injected everywhere rather than read from {@code Instant.now()} inside the code that
     * needs it. This is the difference between a cancellation-policy test that pins the 24-hour
     * boundary exactly and one that hopes.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PricingStrategy pricingStrategy() {
        return new StandardPricingStrategy();
    }

    @Bean
    public CancellationPolicy flexibleCancellationPolicy() {
        return new FlexibleCancellationPolicy();
    }

    @Bean
    public CancellationPolicy moderateCancellationPolicy() {
        return new ModerateCancellationPolicy();
    }

    @Bean
    public CancellationPolicy nonRefundableCancellationPolicy() {
        return new NonRefundableCancellationPolicy();
    }

    @Bean
    public CancellationPolicyRegistry cancellationPolicyRegistry(List<CancellationPolicy> policies) {
        return new CancellationPolicyRegistry(policies);
    }

    @Bean
    public PaymentGatewayRegistry paymentGatewayRegistry(List<PaymentGateway> gateways) {
        return new PaymentGatewayRegistry(gateways);
    }

    @Bean
    public PropertyFilter priceRangeFilter() {
        return new PriceRangeFilter();
    }

    @Bean
    public PropertyFilter amenityFilter() {
        return new AmenityFilter();
    }

    @Bean
    public PropertyFilter starRatingFilter() {
        return new StarRatingFilter();
    }

    /**
     * Room-type-level filtering, applied after a property has qualified. See {@link RoomTypeFilter}
     * for why a price ceiling needs to be enforced at this granularity as well as the property's.
     */
    @Bean
    public RoomTypeFilter roomTypePriceFilter() {
        return new RoomTypePriceFilter();
    }
}
