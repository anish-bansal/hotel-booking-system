package com.rupeek.hotelbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.rupeek.hotelbooking.application.command.CancelBookingCommand;
import com.rupeek.hotelbooking.application.command.CreateBookingCommand;
import com.rupeek.hotelbooking.application.command.PayBookingCommand;
import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.BookingStatus;
import com.rupeek.hotelbooking.domain.model.Payment;
import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.model.PaymentStatus;
import com.rupeek.hotelbooking.domain.port.RoomInventoryRepository;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.support.TestClockConfiguration;
import com.rupeek.hotelbooking.support.TestFixtures;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The test this whole design exists to pass.
 *
 * <h2>What is being proven</h2>
 *
 * A hotel has one room. Twenty guests request it simultaneously, for overlapping dates, from twenty
 * threads. Exactly one must succeed, nineteen must be told no, and the room must end up held exactly
 * once. Anything else is a real hotel with two families at the same door.
 *
 * <h2>Why the test is shaped like this</h2>
 *
 * <ul>
 *   <li><b>No {@code @Transactional} on the test.</b> A transactional test would enrol every thread
 *       in one shared transaction, there would be no contention to observe, and the test would pass
 *       while proving nothing. This is the single easiest way to write a concurrency test that is
 *       secretly vacuous.
 *   <li><b>A {@link CountDownLatch} start gate.</b> Threads submitted to a pool start staggered by
 *       however long submission takes, which can be long enough for each booking to finish before
 *       the next begins. The latch makes all twenty arrive at the critical section together, which
 *       is the only way the race is actually run.
 *   <li><b>Overlapping but non-identical dates.</b> Identical ranges would only prove that identical
 *       requests serialise. Staggered overlaps mean the transactions contend on <em>different
 *       subsets</em> of nights in different orders — which is precisely the arrangement that
 *       deadlocks if the lock ordering is wrong. That this test terminates at all is the evidence
 *       for the ascending-date lock order.
 *   <li><b>The final assertion is on the database, not on the return values.</b> Counting successes
 *       proves the API behaved; reading {@code heldRooms} back proves the inventory did.
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
class ConcurrentBookingIntegrationTest {

    private static final int CONTENDERS = 20;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PropertyOnboardingService onboardingService;

    @Autowired
    private RoomInventoryRepository inventoryRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CancellationService cancellationService;

    @Test
    @DisplayName("20 threads race for 1 room on the same night: exactly one wins")
    void exactlyOneBookingWinsTheLastRoom() throws Exception {
        TestFixtures.Onboarded hotel = TestFixtures.flexibleProperty(
                onboardingService, "Single Room Inn " + System.nanoTime(), 1);

        LocalDate checkIn = TestClockConfiguration.today().plusDays(10);
        LocalDate checkOut = checkIn.plusDays(2);

        Outcome outcome = raceFor(CONTENDERS, index -> bookingService.create(
                new CreateBookingCommand(hotel.propertyId(), hotel.roomTypeId(),
                        "Guest " + index, "guest" + index + "@example.com",
                        checkIn, checkOut, 2, 1)));

        assertThat(outcome.successes())
                .as("exactly one guest may hold the only room")
                .isEqualTo(1);
        assertThat(outcome.failures()).isEqualTo(CONTENDERS - 1);

        assertHeldRooms(hotel.roomTypeId(), DateRange.of(checkIn, checkOut), 1);
    }

    @Test
    @DisplayName("staggered overlapping stays contend without deadlocking, and never oversell")
    void overlappingStaysNeverOversellAndNeverDeadlock() throws Exception {
        // Three rooms, and every contender wants two of them. At most one booking can succeed on
        // any given night, but the overlaps differ per thread, so the transactions acquire
        // different, partially-shared sets of night locks. Without a consistent acquisition order
        // this is the classic deadlock shape.
        TestFixtures.Onboarded hotel = TestFixtures.flexibleProperty(
                onboardingService, "Overlap Lodge " + System.nanoTime(), 3);

        LocalDate base = TestClockConfiguration.today().plusDays(20);

        Outcome outcome = raceFor(CONTENDERS, index -> {
            LocalDate checkIn = base.plusDays(index % 4);
            return bookingService.create(new CreateBookingCommand(
                    hotel.propertyId(), hotel.roomTypeId(),
                    "Guest " + index, "guest" + index + "@example.com",
                    checkIn, checkIn.plusDays(3), 4, 2));
        });

        assertThat(outcome.successes()).isPositive();

        // The real assertion: the inventory counter must agree exactly with the bookings that were
        // actually granted, night by night.
        //
        // Asserting only `heldRooms <= totalRooms` would be worthless here, for two reasons. It is
        // already guaranteed unconditionally by RoomInventory.hold()'s own guard, so it holds even
        // with the locking removed entirely. And it points the wrong way: under a lost update two
        // transactions each read 0, each hold 2, and each write 2 - the stored value ends up *lower*
        // than what was committed, so corruption makes a night look more available, never less.
        // Cross-checking against the granted bookings is what actually detects a lost update.
        List<LocalDate> window = base.minusDays(1).datesUntil(base.plusDays(8)).toList();
        Map<LocalDate, Integer> expected = expectedHoldsPerNight(outcome.bookings());

        inventoryRepository.findForAvailabilityCheck(
                        List.of(hotel.roomTypeId()), window.get(0), window.get(window.size() - 1))
                .forEach(night -> {
                    assertThat(night.heldRooms())
                            .as("night %s: held count must equal the rooms actually booked", night.date())
                            .isEqualTo(expected.getOrDefault(night.date(), 0));
                    assertThat(night.heldRooms())
                            .as("night %s must never be oversold", night.date())
                            .isLessThanOrEqualTo(night.totalRooms());
                });
    }

    /** Rooms committed per night according to the bookings the race actually granted. */
    private static Map<LocalDate, Integer> expectedHoldsPerNight(List<Booking> granted) {
        Map<LocalDate, Integer> perNight = new HashMap<>();
        for (Booking booking : granted) {
            booking.stay().nights()
                    .forEach(night -> perNight.merge(night, booking.roomCount(), Integer::sum));
        }
        return perNight;
    }

    /**
     * Paying and cancelling the same booking at the same instant must not lose money.
     *
     * <h2>The interleaving being defended against</h2>
     *
     * Inventory contention is serialised by row locks, but paying and cancelling touch no common
     * inventory row, so nothing serialised <em>them</em>. Both read {@code PENDING_PAYMENT}, both
     * pass the lifecycle guard, and the later write wins. The dangerous ordering is not the obvious
     * one: cancellation evaluates the refund, correctly finds no payment has settled yet and records
     * "nothing to refund" — and only then does the payment commit. The guest ends up charged,
     * cancelled, and never refunded, with records that look perfectly consistent afterwards.
     *
     * <p>{@code @Version} on {@code Booking} is what makes the second writer fail instead of
     * silently overwriting. This test asserts the invariant rather than the mechanism: whatever
     * order the two land in, a cancelled booking that took money must show that money going back.
     *
     * <p>Repeated because the outcome is a genuine race — a single run could take the benign path.
     */
    @RepeatedTest(8)
    @DisplayName("racing a payment against a cancellation never leaves money taken but unrefunded")
    void concurrentPayAndCancelNeverStrandsAGuestsMoney() throws Exception {
        TestFixtures.Onboarded hotel = TestFixtures.flexibleProperty(
                onboardingService, "Race Condition Inn " + System.nanoTime(), 1);

        LocalDate checkIn = TestClockConfiguration.today().plusDays(60);
        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Racer", "racer@example.com",
                checkIn, checkIn.plusDays(2), 2, 1));

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> paying = pool.submit(() -> {
                startGate.await();
                return paymentService.pay(new PayBookingCommand(
                        booking.id(), PaymentMethod.CARD, "race-" + booking.id(), "4111"));
            });
            Future<?> cancelling = pool.submit(() -> {
                startGate.await();
                return cancellationService.cancel(new CancelBookingCommand(booking.id()));
            });

            startGate.countDown();
            // Either side may legitimately lose - to the lifecycle guard or to the version check.
            // What matters is the state they leave behind, asserted below.
            swallow(paying);
            swallow(cancelling);
        } finally {
            pool.shutdownNow();
        }

        Booking settled = bookingService.require(booking.id());
        List<Payment> payments = paymentService.paymentsFor(booking.id());
        boolean moneyWasTaken = payments.stream()
                .anyMatch(payment -> payment.status() == PaymentStatus.SUCCESSFUL);

        if (settled.status() == BookingStatus.CANCELLED && moneyWasTaken) {
            fail("Booking %s is CANCELLED with a SUCCESSFUL (unrefunded) payment still standing. "
                    + "The guest was charged for a cancelled stay.", booking.id());
        }

        if (settled.status() == BookingStatus.CANCELLED) {
            assertThat(payments)
                    .as("a cancelled booking must hold no payment still in flight")
                    .noneMatch(payment -> payment.status() == PaymentStatus.SUCCESSFUL);
        }
    }

    /** A loser is expected here; only the surviving state is under test. */
    private static void swallow(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException expectedForOneOfThem) {
            // Lifecycle guard or optimistic lock - both are correct refusals.
        }
    }

    /**
     * Runs {@code threads} copies of {@code work} that all begin at the same instant.
     *
     * <p>Failures are counted rather than inspected on purpose. A loser may be rejected because the
     * rooms were gone ({@code InventoryUnavailableException}) or because it waited too long for the
     * row lock (a Spring {@code CannotAcquireLockException}). Both are correct refusals; asserting
     * on which one occurred would make the test sensitive to timing rather than to behaviour.
     */
    private Outcome raceFor(int threads, ThrowingIntFunction work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        // Thread-safe because every contending thread appends to it concurrently.
        List<Booking> granted = new CopyOnWriteArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        try {
            List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, threads)
                    .<Callable<Void>>mapToObj(index -> () -> {
                        startGate.await();
                        try {
                            Booking booking = work.apply(index);
                            if (booking != null) {
                                granted.add(booking);
                            }
                        } catch (RuntimeException expectedForLosers) {
                            failures.incrementAndGet();
                        }
                        return null;
                    })
                    .toList();

            List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
            startGate.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(List.copyOf(granted), failures.get());
    }

    private void assertHeldRooms(java.util.UUID roomTypeId, DateRange stay, int expected) {
        inventoryRepository
                .findForAvailabilityCheck(List.of(roomTypeId), stay.checkIn(),
                        stay.checkOut().minusDays(1))
                .forEach(night -> assertThat(night.heldRooms())
                        .as("held rooms on %s", night.date())
                        .isEqualTo(expected));
    }

    private interface ThrowingIntFunction {
        Booking apply(int index);
    }

    private record Outcome(List<Booking> bookings, int failures) {

        int successes() {
            return bookings.size();
        }
    }
}
