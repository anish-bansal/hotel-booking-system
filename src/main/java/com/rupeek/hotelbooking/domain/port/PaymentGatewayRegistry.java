package com.rupeek.hotelbooking.domain.port;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dispatches to the gateway that handles a given payment method.
 *
 * <p>This is the replacement for the {@code switch (method)} that would otherwise sit in
 * {@code PaymentService} and grow a case every time the business signs a new provider. The map is
 * built once from whatever {@link PaymentGateway} beans exist, so the set of supported methods is
 * determined by what is deployed rather than by a hard-coded list — and two gateways claiming the
 * same method is caught at startup rather than by whichever one Spring happened to inject last.
 */
public class PaymentGatewayRegistry {

    private final Map<PaymentMethod, PaymentGateway> gatewaysByMethod;

    public PaymentGatewayRegistry(List<PaymentGateway> gateways) {
        Map<PaymentMethod, PaymentGateway> byMethod = new EnumMap<>(PaymentMethod.class);
        for (PaymentGateway gateway : gateways) {
            PaymentGateway clash = byMethod.put(gateway.supports(), gateway);
            if (clash != null) {
                throw new IllegalStateException("Two gateways claim " + gateway.supports() + ": "
                        + clash.getClass().getSimpleName() + " and "
                        + gateway.getClass().getSimpleName());
            }
        }
        this.gatewaysByMethod = Map.copyOf(byMethod);
    }

    public PaymentGateway forMethod(PaymentMethod method) {
        PaymentGateway gateway = gatewaysByMethod.get(method);
        if (gateway == null) {
            throw new ValidationException("Payment method " + method
                    + " is not supported. Available methods: " + gatewaysByMethod.keySet());
        }
        return gateway;
    }

    public Set<PaymentMethod> supportedMethods() {
        return gatewaysByMethod.keySet();
    }
}
