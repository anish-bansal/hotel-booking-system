package com.rupeek.hotelbooking.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("normalises scale so INR 100 and INR 100.00 are the same money")
    void normalisesScale() {
        assertThat(Money.inr("100")).isEqualTo(Money.inr("100.00"));
        assertThat(Money.inr("100").hashCode()).isEqualTo(Money.inr("100.00").hashCode());
    }

    @Test
    void addsAndSubtracts() {
        assertThat(Money.inr(500).plus(Money.inr(250))).isEqualTo(Money.inr(750));
        assertThat(Money.inr(500).minus(Money.inr(200))).isEqualTo(Money.inr(300));
    }

    @Test
    void multipliesForNightsAndRooms() {
        assertThat(Money.inr("6500.00").times(6)).isEqualTo(Money.inr("39000.00"));
    }

    @Test
    @DisplayName("percentage rounds half-up, so a 50% refund of an odd amount does not lose a paisa")
    void percentageRoundsHalfUp() {
        assertThat(Money.inr("1001.01").percentage(50)).isEqualTo(Money.inr("500.51"));
        assertThat(Money.inr("1000.00").percentage(0)).isEqualTo(Money.inr("0.00"));
        assertThat(Money.inr("1000.00").percentage(100)).isEqualTo(Money.inr("1000.00"));
    }

    @Test
    @DisplayName("refuses to mix currencies rather than silently producing a wrong total")
    void refusesCurrencyMismatch() {
        Money usd = Money.of(new BigDecimal("10.00"), Currency.getInstance("USD"));
        assertThatThrownBy(() -> Money.inr(100).plus(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.inr(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    void rejectsPercentagesOutsideZeroToHundred() {
        assertThatThrownBy(() -> Money.inr(100).percentage(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.inr(100).percentage(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesAmounts() {
        assertThat(Money.inr(500).isGreaterThan(Money.inr(499))).isTrue();
        assertThat(Money.inr(500).isLessThan(Money.inr(501))).isTrue();
        assertThat(Money.zero(Money.INR).isZero()).isTrue();
    }
}
