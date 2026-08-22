package com.rupeek.hotelbooking.domain.policy;

import com.rupeek.hotelbooking.domain.vo.Money;

/**
 * What a cancellation policy decided, and why.
 *
 * <p>The {@code reason} is not decoration: it is what a support agent reads back to a guest asking
 * why they got 50% instead of everything. Returning an amount without its justification would push
 * that explanation into logs, or nowhere.
 */
public record RefundDecision(Money refundAmount, String reason) {

    public boolean isRefundDue() {
        return !refundAmount.isZero();
    }

    public static RefundDecision full(Money amount, String reason) {
        return new RefundDecision(amount, reason);
    }

    public static RefundDecision none(Money zeroInSameCurrency, String reason) {
        return new RefundDecision(zeroInSameCurrency, reason);
    }
}
