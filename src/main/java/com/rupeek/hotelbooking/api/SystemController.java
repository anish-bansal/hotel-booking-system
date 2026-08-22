package com.rupeek.hotelbooking.api;

import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.policy.CancellationPolicyRegistry;
import com.rupeek.hotelbooking.domain.port.PaymentGatewayRegistry;
import com.rupeek.hotelbooking.domain.search.PropertyFilter;
import com.rupeek.hotelbooking.domain.search.RoomTypeFilter;
import com.rupeek.hotelbooking.infrastructure.lock.SweepLock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports what this running instance actually has wired up.
 *
 * <p>Actuator's {@code /actuator/health} already answers "can I reach Postgres and Redis?" — there is
 * no reason to re-implement that. What it cannot answer is the question this endpoint exists for:
 * <em>which of my own pluggable pieces got discovered at startup?</em> Three payment gateways or two?
 * Did the new filter's bean declaration actually take?
 *
 * <p>That matters because every extension point in this service is wired by discovery rather than by
 * a hard-coded list — the registries collect whatever beans exist. Discovery is wonderful until
 * something silently fails to be discovered, at which point the only symptom is a feature quietly
 * not working. This endpoint turns that class of mistake into something you can see, and gives the
 * launcher script something concrete to assert on beyond "the process is up".
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final PaymentGatewayRegistry gateways;
    private final CancellationPolicyRegistry cancellationPolicies;
    private final List<PropertyFilter> searchFilters;
    private final List<RoomTypeFilter> roomTypeFilters;
    private final SweepLock sweepLock;
    private final Environment environment;
    private final int bookingHorizonDays;
    private final long paymentHoldMinutes;

    public SystemController(PaymentGatewayRegistry gateways,
                            CancellationPolicyRegistry cancellationPolicies,
                            List<PropertyFilter> searchFilters,
                            List<RoomTypeFilter> roomTypeFilters,
                            SweepLock sweepLock,
                            Environment environment,
                            @Value("${hotel-booking.inventory.booking-horizon-days:365}")
                            int bookingHorizonDays,
                            @Value("${hotel-booking.booking.payment-hold-minutes:15}")
                            long paymentHoldMinutes) {
        this.gateways = gateways;
        this.cancellationPolicies = cancellationPolicies;
        this.searchFilters = List.copyOf(searchFilters);
        this.roomTypeFilters = List.copyOf(roomTypeFilters);
        this.sweepLock = sweepLock;
        this.environment = environment;
        this.bookingHorizonDays = bookingHorizonDays;
        this.paymentHoldMinutes = paymentHoldMinutes;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(
                List.of(environment.getActiveProfiles()),
                databaseKind(),
                gateways.supportedMethods(),
                cancellationPolicies.registeredCodes(),
                searchFilters.stream().map(PropertyFilter::name).toList(),
                roomTypeFilters.stream().map(RoomTypeFilter::name).toList(),
                sweepLock.describe(),
                Map.of(
                        "bookingHorizonDays", bookingHorizonDays,
                        "paymentHoldMinutes", paymentHoldMinutes));
    }

    /**
     * Which database is behind the repository ports — the point this endpoint exists to make, since
     * the same code runs on either and only a profile changes.
     *
     * <p>Reports the <em>kind</em>, never the JDBC URL. A connection string routinely carries a host,
     * a port and sometimes credentials, and this endpoint is unauthenticated; "PostgreSQL" answers
     * the question that is actually being asked without handing out any of that.
     */
    private String databaseKind() {
        String url = environment.getProperty("spring.datasource.url", "");
        if (url.startsWith("jdbc:postgresql:")) {
            return "PostgreSQL";
        }
        if (url.startsWith("jdbc:h2:mem:")) {
            return "H2 (in-memory)";
        }
        if (url.startsWith("jdbc:h2:")) {
            return "H2 (file)";
        }
        return "unknown";
    }

    /**
     * @param paymentMethods      one per discovered {@code PaymentGateway} bean — the count is the
     *                            evidence that adding a gateway class is genuinely all it takes
     * @param cancellationPolicies one per discovered {@code CancellationPolicy} bean
     * @param searchFilters        one per discovered {@code PropertyFilter} bean — the chain that
     *                             decides which properties appear at all
     * @param roomTypeFilters      one per discovered {@code RoomTypeFilter} bean — the chain that
     *                             decides which rooms within a matching property may be offered.
     *                             Reported separately because the two operate on different units and
     *                             a filter missing from either chain fails in a different way
     * @param sweepLock            which locking strategy won, so a cluster deployment can confirm it
     *                             is not running the in-process one by accident
     */
    public record Capabilities(
            List<String> activeProfiles,
            String database,
            Set<PaymentMethod> paymentMethods,
            Set<String> cancellationPolicies,
            List<String> searchFilters,
            List<String> roomTypeFilters,
            String sweepLock,
            Map<String, Object> settings) {
    }
}
