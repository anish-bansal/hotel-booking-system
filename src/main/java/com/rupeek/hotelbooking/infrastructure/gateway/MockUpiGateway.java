package com.rupeek.hotelbooking.infrastructure.gateway;

import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.port.PaymentGateway;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a UPI PSP.
 *
 * <p>This class is the evidence for the extensibility claim: supporting UPI required adding this
 * file and nothing else. No service, controller, entity or enum-driven branch was edited, because
 * the registry discovers gateways rather than being told about them.
 */
@Component
public class MockUpiGateway implements PaymentGateway {

    @Override
    public PaymentMethod supports() {
        return PaymentMethod.UPI;
    }

    @Override
    public ChargeResult charge(ChargeCommand command) {
        return ChargeResult.success("UPI-" + command.idempotencyKey());
    }

    @Override
    public RefundResult refund(RefundCommand command) {
        return RefundResult.success("UPI-RFND-" + command.originalGatewayReference());
    }
}
