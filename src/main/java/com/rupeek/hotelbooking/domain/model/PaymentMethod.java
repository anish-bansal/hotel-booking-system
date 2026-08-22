package com.rupeek.hotelbooking.domain.model;

/**
 * How a guest pays.
 *
 * <p>Deliberately just a tag. It carries no behaviour, no fee logic, no routing rules — all of that
 * lives in the {@code PaymentGateway} implementation registered against this value. Adding
 * NET_BANKING to this enum plus one new gateway class is the entire cost of supporting a new
 * payment method; no existing class changes.
 */
public enum PaymentMethod {
    CARD,
    UPI,
    WALLET
}
