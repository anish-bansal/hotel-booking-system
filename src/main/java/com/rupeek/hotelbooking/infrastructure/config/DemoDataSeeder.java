package com.rupeek.hotelbooking.infrastructure.config;

import com.rupeek.hotelbooking.application.PropertyOnboardingService;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand.PropertySpec;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand.RoomTypeSpec;
import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import com.rupeek.hotelbooking.domain.policy.FlexibleCancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.ModerateCancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.NonRefundableCancellationPolicy;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds a handful of properties at startup so the API can be explored immediately.
 *
 * <p>Two deliberate choices. It goes through {@link PropertyOnboardingService} rather than an
 * {@code INSERT} script, so the seed data exercises the same validation and inventory-opening path
 * a real onboarding does — if onboarding breaks, the app fails to start rather than starting with
 * data that could not have been created through the API. And it is switched off by a property, so
 * tests get a clean database and are never accidentally coupled to this data.
 *
 * <p>The seed spans the three cancellation policies, a standalone owner and a two-property chain,
 * and one room type with a single room — so that every interesting path, including the contended
 * last-room case, is reachable from a fresh boot.
 */
@Component
@ConditionalOnProperty(name = "hotel-booking.demo-data.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final PropertyOnboardingService onboardingService;

    public DemoDataSeeder(PropertyOnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        PropertyGroup chain = onboardingService.onboard(new OnboardPropertyGroupCommand(
                "Coastline Hotels",
                "owner@coastlinehotels.example",
                List.of(bengaluruFlagship(), goaResort())));

        PropertyGroup standalone = onboardingService.onboard(new OnboardPropertyGroupCommand(
                "Indiranagar Boutique Stay",
                "hello@indiranagarstay.example",
                List.of(boutiqueStandalone())));

        log.info("""

                ==================== demo data ready ====================
                Multi-property owner : {} ({} properties, standalone={})
                Standalone owner     : {} ({} property, standalone={})
                Try: POST /api/v1/properties/search with city=bengaluru
                =========================================================
                """,
                chain.name(), chain.propertyCount(), chain.isStandalone(),
                standalone.name(), standalone.propertyCount(), standalone.isStandalone());
    }

    private static PropertySpec bengaluruFlagship() {
        return new PropertySpec(
                "Coastline Grand Bengaluru",
                "Bengaluru", "Indiranagar", "100 Ft Road, Indiranagar",
                5,
                Set.of(Amenity.WIFI, Amenity.POOL, Amenity.GYM, Amenity.SPA, Amenity.PARKING,
                        Amenity.RESTAURANT, Amenity.AIR_CONDITIONING),
                ModerateCancellationPolicy.CODE,
                List.of(
                        new RoomTypeSpec("Deluxe King", 2, 8, new BigDecimal("6500.00")),
                        new RoomTypeSpec("Executive Suite", 4, 3, new BigDecimal("12000.00")),
                        // One room only - this is the room type to point a concurrency test at.
                        new RoomTypeSpec("Penthouse", 4, 1, new BigDecimal("28000.00"))));
    }

    private static PropertySpec goaResort() {
        return new PropertySpec(
                "Coastline Beach Resort Goa",
                "Goa", "Candolim", "Beach Road, Candolim",
                4,
                Set.of(Amenity.WIFI, Amenity.POOL, Amenity.BAR, Amenity.BREAKFAST_INCLUDED,
                        Amenity.PET_FRIENDLY, Amenity.AIRPORT_SHUTTLE),
                FlexibleCancellationPolicy.CODE,
                List.of(
                        new RoomTypeSpec("Garden View Twin", 2, 12, new BigDecimal("4200.00")),
                        new RoomTypeSpec("Sea Facing Villa", 6, 4, new BigDecimal("15500.00"))));
    }

    private static PropertySpec boutiqueStandalone() {
        return new PropertySpec(
                "The Indiranagar Loft",
                "Bengaluru", "Indiranagar", "12th Main, Indiranagar",
                3,
                Set.of(Amenity.WIFI, Amenity.AIR_CONDITIONING, Amenity.WHEELCHAIR_ACCESSIBLE),
                NonRefundableCancellationPolicy.CODE,
                List.of(new RoomTypeSpec("Studio Double", 2, 5, new BigDecimal("2800.00"))));
    }
}
