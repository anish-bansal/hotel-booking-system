package com.rupeek.hotelbooking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rupeek.hotelbooking.application.command.CreateBookingCommand;
import com.rupeek.hotelbooking.application.command.PayBookingCommand;
import com.rupeek.hotelbooking.application.result.PaymentResult;
import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.BookingStatus;
import com.rupeek.hotelbooking.domain.model.Payment;
import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.model.PaymentStatus;
import com.rupeek.hotelbooking.infrastructure.gateway.MockWalletGateway;
import com.rupeek.hotelbooking.domain.vo.Money;
import com.rupeek.hotelbooking.support.TestClockConfiguration;
import com.rupeek.hotelbooking.support.TestFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the guest cannot be charged twice.
 *
 * <p>Two shapes of duplicate, tested separately because they are stopped by different mechanisms:
 * a sequential retry is caught by the idempotency lookup, and a concurrent duplicate is caught by
 * the unique constraint. A test of only the first would leave the harder case unproven, and the
 * harder case is the one that actually happens — clients retry on timeout, and a timeout means the
 * first request may still be in flight.
 *
 * <p>The wallet gateway is used deliberately: it holds a balance, so "was the money taken twice?"
 * can be answered by looking at the money rather than by trusting our own records.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
class PaymentIdempotencyIntegrationTest {

    private static final int CONCURRENT_RETRIES = 8;

    @Autowired
    private PropertyOnboardingService onboardingService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private MockWalletGateway wallet;

    @Test
    @DisplayName("the same key twice returns the first outcome and never calls the gateway again")
    void sequentialRetryIsReplayed() {
        Booking booking = bookingFor("Idempotent Inn", 30);
        String key = "retry-" + UUID.randomUUID();

        Money balanceBefore = wallet.balance();
        PaymentResult first = paymentService.pay(
                new PayBookingCommand(booking.id(), PaymentMethod.WALLET, key, "wallet-1"));
        Money balanceAfterFirst = wallet.balance();

        PaymentResult second = paymentService.pay(
                new PayBookingCommand(booking.id(), PaymentMethod.WALLET, key, "wallet-1"));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).as("the second call must announce itself as a replay").isTrue();
        assertThat(second.payment().id()).isEqualTo(first.payment().id());
        assertThat(second.payment().gatewayReference()).isEqualTo(first.payment().gatewayReference());

        assertThat(balanceBefore.minus(balanceAfterFirst))
                .as("the first charge moved the money")
                .isEqualTo(booking.totalAmount());
        assertThat(wallet.balance())
                .as("the replay moved no money at all")
                .isEqualTo(balanceAfterFirst);

        assertThat(paymentService.paymentsFor(booking.id()))
                .as("one payment record, not two")
                .hasSize(1);
    }

    @Test
    @DisplayName("a different key on an already-confirmed booking is refused, not charged again")
    void payingAConfirmedBookingAgainIsRefused() {
        Booking booking = bookingFor("Second Charge Inn", 31);

        paymentService.pay(new PayBookingCommand(booking.id(), PaymentMethod.UPI,
                "first-" + UUID.randomUUID(), "upi"));
        Money balanceAfterConfirm = wallet.balance();

        // A fresh key means the idempotency lookup cannot help here. What stops the second charge is
        // the booking's own state machine: CONFIRMED cannot transition to CONFIRMED.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> paymentService.pay(
                        new PayBookingCommand(booking.id(), PaymentMethod.WALLET,
                                "second-" + UUID.randomUUID(), "wallet")))
                .isInstanceOf(com.rupeek.hotelbooking.domain.exception.IllegalStateTransitionException.class);

        assertThat(wallet.balance()).isEqualTo(balanceAfterConfirm);
        assertThat(bookingService.require(booking.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("8 concurrent requests with one key produce exactly one successful charge")
    void concurrentDuplicatesChargeOnce() throws Exception {
        Booking booking = bookingFor("Race Inn", 32);
        String sharedKey = "concurrent-" + UUID.randomUUID();

        Money balanceBefore = wallet.balance();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_RETRIES);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            List<Callable<Void>> attempts = java.util.stream.IntStream.range(0, CONCURRENT_RETRIES)
                    .<Callable<Void>>mapToObj(index -> () -> {
                        startGate.await();
                        try {
                            paymentService.pay(new PayBookingCommand(booking.id(),
                                    PaymentMethod.WALLET, sharedKey, "wallet-" + index));
                            accepted.incrementAndGet();
                        } catch (RuntimeException rejectedByConstraintOrState) {
                            rejected.incrementAndGet();
                        }
                        return null;
                    })
                    .toList();

            List<Future<Void>> futures = attempts.stream().map(pool::submit).toList();
            startGate.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Some attempts may be accepted as replays of the winner rather than rejected outright -
        // both are correct. The invariant that matters is that the money moved exactly once.
        assertThat(accepted.get() + rejected.get()).isEqualTo(CONCURRENT_RETRIES);
        assertThat(balanceBefore.minus(wallet.balance()))
                .as("exactly one charge reached the wallet")
                .isEqualTo(booking.totalAmount());

        List<Payment> payments = paymentService.paymentsFor(booking.id());
        assertThat(payments).as("one payment row, guaranteed by the unique key").hasSize(1);
        assertThat(payments.get(0).status()).isEqualTo(PaymentStatus.SUCCESSFUL);
        assertThat(bookingService.require(booking.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("a blank idempotency key is rejected before anything happens")
    void blankKeyIsRejected() {
        Booking booking = bookingFor("No Key Inn", 33);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> paymentService.pay(
                        new PayBookingCommand(booking.id(), PaymentMethod.CARD, "  ", "card")))
                .isInstanceOf(com.rupeek.hotelbooking.domain.exception.ValidationException.class)
                .hasMessageContaining("Idempotency-Key");

        assertThat(paymentService.paymentsFor(booking.id())).isEmpty();
    }

    private Booking bookingFor(String hotelName, int daysAhead) {
        TestFixtures.Onboarded hotel = TestFixtures.flexibleProperty(
                onboardingService, hotelName + " " + System.nanoTime(), 2);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(daysAhead);

        return bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Asha Menon", "asha@example.com",
                checkIn, checkIn.plusDays(2), 2, 1));
    }
}
