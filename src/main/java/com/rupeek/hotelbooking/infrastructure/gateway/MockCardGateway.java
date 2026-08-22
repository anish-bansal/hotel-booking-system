package com.rupeek.hotelbooking.infrastructure.gateway;

import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.port.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a card acquirer.
 *
 * <p>The brief says to mock third-party dependencies behind our own abstraction, so this is a mock —
 * but a <em>deterministic</em> one. Any idempotency key beginning with {@link #DECLINE_PREFIX} is
 * declined; everything else succeeds. Deterministic beats random because a test for "a declined
 * card leaves the booking unconfirmed and the rooms held only until the hold lapses" has to be able
 * to actually cause a decline, and a flaky gateway would make that test flaky too.
 */
@Component
public class MockCardGateway implements PaymentGateway {

    /** Prefix a payment's idempotency key with this to force a decline. */
    public static final String DECLINE_PREFIX = "DECLINE";

    private static final Logger log = LoggerFactory.getLogger(MockCardGateway.class);

    @Override
    public PaymentMethod supports() {
        return PaymentMethod.CARD;
    }

    @Override
    public ChargeResult charge(ChargeCommand command) {
        if (command.idempotencyKey().toUpperCase().startsWith(DECLINE_PREFIX)) {
            log.info("Mock card gateway declining charge for key {}", command.idempotencyKey());
            return ChargeResult.failure("Card declined by issuer");
        }
        String reference = "CARD-" + command.idempotencyKey();
        log.info("Mock card gateway charged {} -> {}", command.amount(), reference);
        return ChargeResult.success(reference);
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        return RefundResult.success("CARD-RFND-" + command.originalGatewayReference());
    }
}
