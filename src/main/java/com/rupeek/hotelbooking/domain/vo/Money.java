package com.rupeek.hotelbooking.domain.vo;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary amount. Exists so that no arithmetic on money ever happens on a bare
 * {@code BigDecimal} or (worse) a {@code double}, and so that currency mismatches fail loudly
 * instead of silently producing a wrong total.
 *
 * <p>Scale is normalised to the currency's default fraction digits on construction, which keeps
 * equality well-behaved: {@code INR 100} and {@code INR 100.00} are the same money.
 */
@Embeddable
public final class Money implements Comparable<Money> {

    public static final Currency INR = Currency.getInstance("INR");

    private BigDecimal amount;
    private String currencyCode;

    protected Money() {
        // for JPA
    }

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
        this.currencyCode = currency.getCurrencyCode();
    }

    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + amount);
        }
        return new Money(amount, currency);
    }

    public static Money inr(String amount) {
        return of(new BigDecimal(amount), INR);
    }

    public static Money inr(long amount) {
        return of(BigDecimal.valueOf(amount), INR);
    }

    public static Money zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(amount.add(other.amount), currency());
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency());
    }

    public Money times(long multiplier) {
        return new Money(amount.multiply(BigDecimal.valueOf(multiplier)), currency());
    }

    /** Used by refund policies that express themselves as a percentage of the amount paid. */
    public Money percentage(int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be within [0, 100] but was " + percent);
        }
        return new Money(
                amount.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP),
                currency());
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return Currency.getInstance(currencyCode);
    }

    public String currencyCode() {
        return currencyCode;
    }

    private void assertSameCurrency(Money other) {
        if (!currencyCode.equals(other.currencyCode)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currencyCode + " vs " + other.currencyCode);
        }
    }

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return amount.compareTo(other.amount) == 0 && currencyCode.equals(other.currencyCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currencyCode);
    }

    @Override
    public String toString() {
        return currencyCode + " " + amount.toPlainString();
    }
}
