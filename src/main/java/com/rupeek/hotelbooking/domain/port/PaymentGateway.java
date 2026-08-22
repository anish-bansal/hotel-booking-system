package com.rupeek.hotelbooking.domain.port;

import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.vo.Money;

/**
 * The seam between this service and whoever actually moves the money.
 *
 * <p>One implementation per {@link PaymentMethod}, each declaring the method it serves via
 * {@link #supports()}. A registry collects them at startup and dispatches on the method the guest
 * chose, so:
 *
 * <ul>
 *   <li>adding net banking means adding one class — no {@code switch} anywhere grows a branch;
 *   <li>the application layer knows only this interface, so nothing above it can accidentally
 *       depend on a card-specific concept;
 *   <li>tests substitute a stub gateway without a mocking framework or a network.
 * </ul>
 *
 * <p>The commands and results are deliberately narrow records rather than PSP-shaped payloads. This
 * interface is <em>our</em> vocabulary that a real gateway adapter would translate into, not a
 * leaked copy of some provider's API — which is what keeps a provider migration from rippling
 * inward.
 */
public interface PaymentGateway {

    PaymentMethod supports();

    ChargeResult charge(ChargeCommand command);

    RefundResult refund(RefundCommand command);

    /**
     * @param idempotencyKey passed through to the provider so a retry cannot double-charge even if
     *                       our own request never reached us twice
     */
    record ChargeCommand(String idempotencyKey, Money amount, String payerReference) {
    }

    record ChargeResult(boolean successful, String gatewayReference, String failureReason) {

        public static ChargeResult success(String gatewayReference) {
            return new ChargeResult(true, gatewayReference, null);
        }

        public static ChargeResult failure(String reason) {
            return new ChargeResult(false, null, reason);
        }
    }

    record RefundCommand(String originalGatewayReference, Money amount) {
    }

    record RefundResult(boolean successful, String gatewayReference, String failureReason) {

        public static RefundResult success(String gatewayReference) {
            return new RefundResult(true, gatewayReference, null);
        }

        public static RefundResult failure(String reason) {
            return new RefundResult(false, null, reason);
        }
    }
}
