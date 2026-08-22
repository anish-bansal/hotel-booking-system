package com.rupeek.hotelbooking.domain.policy;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a property's stored policy code to the object that implements it.
 *
 * <p>This tiny class is what makes the open/closed claim real. The registry is built from whatever
 * {@link CancellationPolicy} beans exist at startup, so a new policy becomes reachable by being
 * declared — nothing here, and nothing in the services above, needs editing. It also fails fast on
 * two mistakes that would otherwise surface much later: two policies claiming the same code (caught
 * at startup) and a property referencing a code nobody implements (caught on resolution, with the
 * list of codes that do exist).
 */
public class CancellationPolicyRegistry {

    private final Map<String, CancellationPolicy> policiesByCode;

    public CancellationPolicyRegistry(List<CancellationPolicy> policies) {
        Map<String, CancellationPolicy> byCode = new LinkedHashMap<>();
        for (CancellationPolicy policy : policies) {
            CancellationPolicy clash = byCode.put(policy.code(), policy);
            if (clash != null) {
                throw new IllegalStateException("Duplicate cancellation policy code '"
                        + policy.code() + "' declared by " + clash.getClass().getSimpleName()
                        + " and " + policy.getClass().getSimpleName());
            }
        }
        this.policiesByCode = Map.copyOf(byCode);
    }

    public CancellationPolicy resolve(String code) {
        CancellationPolicy policy = policiesByCode.get(code);
        if (policy == null) {
            throw new ValidationException("Unknown cancellation policy '" + code
                    + "'. Registered policies: " + policiesByCode.keySet());
        }
        return policy;
    }

    public boolean isRegistered(String code) {
        return policiesByCode.containsKey(code);
    }

    public Set<String> registeredCodes() {
        return policiesByCode.keySet();
    }
}
