package com.rupeek.hotelbooking.infrastructure.gateway;

import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.port.PaymentGateway;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a stored-value wallet.
 *
 * <p>Unlike the other two mocks this one holds a balance, so it can decline for a reason that is not
 * "the test asked it to" — insufficient funds. That gives the failure path a second, more realistic
 * shape to exercise, and demonstrates that a gateway may legitimately carry state of its own behind
 * the interface without any caller knowing.
 */
@Component
public class MockWalletGateway implements PaymentGateway {

    private final AtomicReference<Money> balance = new AtomicReference<>(Money.inr(1_000_000));

    @Override
    public PaymentMethod supports() {
        return PaymentMethod.WALLET;
    }

    @Override
    public ChargeResult charge(ChargeCommand command) {
        Money current = balance.get();
        if (command.amount().isGreaterThan(current)) {
            return ChargeResult.failure("Insufficient wallet balance: have " + current
                    + ", need " + command.amount());
        }
        balance.updateAndGet(existing -> existing.minus(command.amount()));
        return ChargeResult.success("WALLET-" + command.idempotencyKey());
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        balance.updateAndGet(existing -> existing.plus(command.amount()));
        return RefundResult.success("WALLET-RFND-" + command.originalGatewayReference());
    }

    /** Exposed for tests that want to drive the insufficient-funds path. */
    public void setBalance(Money newBalance) {
        balance.set(newBalance);
    }

    public Money balance() {
        return balance.get();
    }
}
