package com.rupeek.hotelbooking.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.rupeek.hotelbooking.domain.model.RoomType;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StandardPricingStrategyTest {

    private final PricingStrategy pricing = new StandardPricingStrategy();

    @Test
    @DisplayName("price is rate x nights x rooms, and check-out day is not charged")
    void multipliesRateByNightsAndRooms() {
        RoomType deluxe = RoomType.create("Deluxe King", 2, 8, Money.inr("6500.00"));
        DateRange threeNights = DateRange.of(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 13));

        Money quote = pricing.quote(new PricingStrategy.PricingRequest(deluxe, threeNights, 2, 4));

        assertThat(quote).isEqualTo(Money.inr("39000.00"));
    }

    @Test
    void singleNightSingleRoomIsJustTheRate() {
        RoomType studio = RoomType.create("Studio Double", 2, 5, Money.inr("2800.00"));
        DateRange oneNight = DateRange.of(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 11));

        assertThat(pricing.quote(new PricingStrategy.PricingRequest(studio, oneNight, 1, 1)))
                .isEqualTo(Money.inr("2800.00"));
    }
}
