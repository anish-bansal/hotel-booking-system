package com.rupeek.hotelbooking.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CancellationPolicyRegistryTest {

    private final CancellationPolicyRegistry registry = new CancellationPolicyRegistry(List.of(
            new FlexibleCancellationPolicy(),
            new ModerateCancellationPolicy(),
            new NonRefundableCancellationPolicy()));

    @Test
    void resolvesByCode() {
        assertThat(registry.resolve(FlexibleCancellationPolicy.CODE))
                .isInstanceOf(FlexibleCancellationPolicy.class);
        assertThat(registry.registeredCodes())
                .containsExactlyInAnyOrder("FLEXIBLE", "MODERATE", "NON_REFUNDABLE");
    }

    @Test
    @DisplayName("an unknown code fails with the list of codes that do exist")
    void unknownCodeFailsHelpfully() {
        assertThatThrownBy(() -> registry.resolve("SUPER_FLEXIBLE"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("SUPER_FLEXIBLE")
                .hasMessageContaining("FLEXIBLE");
    }

    @Test
    @DisplayName("two policies claiming one code is caught at construction, not at cancellation time")
    void duplicateCodesAreRejectedEagerly() {
        assertThatThrownBy(() -> new CancellationPolicyRegistry(List.of(
                new FlexibleCancellationPolicy(), new ImpostorPolicy())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate cancellation policy code");
    }

    /** Deliberately claims a code that is already taken. */
    private static final class ImpostorPolicy implements CancellationPolicy {

        @Override
        public String code() {
            return FlexibleCancellationPolicy.CODE;
        }

        @Override
        public String description() {
            return "impostor";
        }

        @Override
        public RefundDecision evaluate(Booking booking, Instant now) {
            return RefundDecision.none(Money.zero(Money.INR), "impostor");
        }
    }
}
