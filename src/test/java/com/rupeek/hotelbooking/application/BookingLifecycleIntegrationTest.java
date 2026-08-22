package com.rupeek.hotelbooking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rupeek.hotelbooking.application.command.CancelBookingCommand;
import com.rupeek.hotelbooking.application.command.CreateBookingCommand;
import com.rupeek.hotelbooking.application.command.OnboardPropertyGroupCommand;
import com.rupeek.hotelbooking.application.command.PayBookingCommand;
import com.rupeek.hotelbooking.application.result.CancellationResult;
import com.rupeek.hotelbooking.application.result.PaymentResult;
import com.rupeek.hotelbooking.application.result.PropertySearchResult;
import com.rupeek.hotelbooking.domain.exception.IllegalStateTransitionException;
import com.rupeek.hotelbooking.domain.exception.InventoryUnavailableException;
import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.model.BookingStatus;
import com.rupeek.hotelbooking.domain.model.PaymentMethod;
import com.rupeek.hotelbooking.domain.model.PaymentStatus;
import com.rupeek.hotelbooking.domain.model.PropertyGroup;
import com.rupeek.hotelbooking.domain.policy.FlexibleCancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.NonRefundableCancellationPolicy;
import com.rupeek.hotelbooking.domain.search.PropertySearchCriteria;
import com.rupeek.hotelbooking.domain.vo.Amenity;
import com.rupeek.hotelbooking.domain.vo.DateRange;
import com.rupeek.hotelbooking.domain.vo.Money;
import com.rupeek.hotelbooking.infrastructure.gateway.MockCardGateway;
import com.rupeek.hotelbooking.support.MutableClock;
import com.rupeek.hotelbooking.support.TestClockConfiguration;
import com.rupeek.hotelbooking.support.TestFixtures;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The core flows, end to end: discover, book, pay, cancel.
 *
 * <p>These run against the real H2 database and the real service wiring, because the interesting
 * behaviour here is precisely the part that mocks would paper over — that reserving inventory,
 * confirming a booking and releasing rooms all actually land in the same transaction and are visible
 * to the next query.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
class BookingLifecycleIntegrationTest {

    @Autowired
    private PropertyOnboardingService onboardingService;

    @Autowired
    private PropertySearchService searchService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private CancellationService cancellationService;

    @Autowired
    private MutableClock clock;

    /**
     * The Spring context — and therefore the clock — is shared across every test in this class. One
     * test walks time forward to lapse a hold; without this reset the next test would inherit that
     * shift and its date arithmetic would quietly mean something different.
     */
    @AfterEach
    void rewindClock() {
        clock.setTo(TestClockConfiguration.FIXED_NOW);
    }

    @Test
    @DisplayName("search -> book -> pay confirms the booking and consumes the room")
    void happyPath() {
        TestFixtures.Onboarded hotel = uniqueHotel("Lifecycle Inn", 2);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(5);
        LocalDate checkOut = checkIn.plusDays(3);

        // Discover: the property shows up with both rooms free.
        List<PropertySearchResult> before = search(checkIn, checkOut, hotel.propertyId());
        assertThat(before).hasSize(1);
        assertThat(before.get(0).availableRoomTypes()).hasSize(1);
        assertThat(before.get(0).availableRoomTypes().get(0).roomsAvailable()).isEqualTo(2);
        // Three nights at 5000 for one room.
        assertThat(before.get(0).availableRoomTypes().get(0).totalForStay())
                .isEqualTo(Money.inr("15000.00"));

        // Book: holds inventory, awaits payment.
        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Asha Menon", "asha@example.com",
                checkIn, checkOut, 2, 1));

        assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(booking.totalAmount()).isEqualTo(Money.inr("15000.00"));

        // The held room is immediately invisible to the next search.
        assertThat(search(checkIn, checkOut, hotel.propertyId()).get(0)
                .availableRoomTypes().get(0).roomsAvailable())
                .as("an unpaid hold must still remove the room from sale")
                .isEqualTo(1);

        // Pay: the gateway outcome drives the booking state.
        PaymentResult payment = paymentService.pay(new PayBookingCommand(
                booking.id(), PaymentMethod.CARD, key(), "asha-card"));

        assertThat(payment.payment().status()).isEqualTo(PaymentStatus.SUCCESSFUL);
        assertThat(payment.replayed()).isFalse();
        assertThat(payment.booking().status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookingService.require(booking.id()).confirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("a fully booked property disappears from search for those dates only")
    void soldOutPropertyIsNotReturned() {
        TestFixtures.Onboarded hotel = uniqueHotel("Sold Out Lodge", 1);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(7);
        LocalDate checkOut = checkIn.plusDays(2);

        bookingService.create(new CreateBookingCommand(hotel.propertyId(), hotel.roomTypeId(),
                "Only Guest", "only@example.com", checkIn, checkOut, 2, 1));

        assertThat(search(checkIn, checkOut, hotel.propertyId()))
                .as("no rooms left on those nights, so the property is not a result")
                .isEmpty();
        assertThat(search(checkOut, checkOut.plusDays(2), hotel.propertyId()))
                .as("later dates are unaffected")
                .hasSize(1);
    }

    @Test
    @DisplayName("a stay that is only partly available is refused, and nothing is half-held")
    void partialAvailabilityIsRefusedAtomically() {
        TestFixtures.Onboarded hotel = uniqueHotel("Partial Inn", 1);
        LocalDate blocked = TestClockConfiguration.today().plusDays(15);

        // Take the only room on one night in the middle of the range the second guest will want.
        bookingService.create(new CreateBookingCommand(hotel.propertyId(), hotel.roomTypeId(),
                "First Guest", "first@example.com", blocked, blocked.plusDays(1), 2, 1));

        assertThatThrownBy(() -> bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Second Guest", "second@example.com",
                blocked.minusDays(2), blocked.plusDays(2), 2, 1)))
                .isInstanceOf(InventoryUnavailableException.class)
                .hasMessageContaining(blocked.toString());

        // The nights either side must be untouched - the refusal held nothing at all.
        assertThat(search(blocked.minusDays(2), blocked, hotel.propertyId()).get(0)
                .availableRoomTypes().get(0).roomsAvailable()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelling a confirmed booking refunds per policy and returns the room to sale")
    void cancellationRefundsAndReleases() {
        TestFixtures.Onboarded hotel = uniqueHotel("Flexible Retreat", 1);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(30);
        LocalDate checkOut = checkIn.plusDays(2);

        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Asha Menon", "asha@example.com",
                checkIn, checkOut, 2, 1));
        paymentService.pay(new PayBookingCommand(booking.id(), PaymentMethod.UPI, key(), "asha@upi"));

        assertThat(search(checkIn, checkOut, hotel.propertyId())).isEmpty();

        CancellationResult result = cancellationService.cancel(new CancelBookingCommand(booking.id()));

        // FLEXIBLE policy, 30 days' notice: full refund.
        assertThat(result.booking().status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(result.refundDecision().refundAmount()).isEqualTo(Money.inr("10000.00"));
        assertThat(result.roomsReleased()).isEqualTo(1);
        assertThat(result.appliedPolicy()).isEqualTo("FLEXIBLE");

        assertThat(paymentService.paymentsFor(booking.id()).get(0).status())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(search(checkIn, checkOut, hotel.propertyId()))
                .as("the released room is discoverable and bookable again")
                .hasSize(1);
    }

    @Test
    @DisplayName("a non-refundable booking still releases its room, it just returns no money")
    void nonRefundableStillReleasesInventory() {
        TestFixtures.Onboarded hotel = TestFixtures.propertyWithRooms(onboardingService,
                "Budget Stay " + System.nanoTime(), 1, 2, "3000.00",
                NonRefundableCancellationPolicy.CODE);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(40);
        LocalDate checkOut = checkIn.plusDays(1);

        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Ravi K", "ravi@example.com",
                checkIn, checkOut, 2, 1));
        paymentService.pay(new PayBookingCommand(booking.id(), PaymentMethod.WALLET, key(), "wallet"));

        CancellationResult result = cancellationService.cancel(new CancelBookingCommand(booking.id()));

        assertThat(result.refundDecision().isRefundDue()).isFalse();
        assertThat(result.roomsReleased()).isEqualTo(1);
        assertThat(search(checkIn, checkOut, hotel.propertyId())).hasSize(1);
    }

    @Test
    @DisplayName("cancelling an unpaid booking releases the hold and refunds nothing")
    void cancellingUnpaidBookingRefundsNothing() {
        TestFixtures.Onboarded hotel = uniqueHotel("Unpaid Inn", 1);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(50);

        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Never Paid", "np@example.com",
                checkIn, checkIn.plusDays(1), 2, 1));

        CancellationResult result = cancellationService.cancel(new CancelBookingCommand(booking.id()));

        assertThat(result.refundDecision().isRefundDue()).isFalse();
        assertThat(result.refundDecision().reason()).contains("No payment");
        assertThat(result.roomsReleased()).isEqualTo(1);
    }

    @Test
    @DisplayName("cancelling twice is refused, so a room cannot be released or refunded twice")
    void doubleCancellationIsRefused() {
        TestFixtures.Onboarded hotel = uniqueHotel("Double Cancel Inn", 1);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(60);

        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Twice", "twice@example.com",
                checkIn, checkIn.plusDays(1), 2, 1));
        cancellationService.cancel(new CancelBookingCommand(booking.id()));

        assertThatThrownBy(() -> cancellationService.cancel(new CancelBookingCommand(booking.id())))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    @DisplayName("a declined card leaves the booking unpaid and still holding its room")
    void declinedPaymentDoesNotConfirm() {
        TestFixtures.Onboarded hotel = uniqueHotel("Decline Inn", 1);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(11);

        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Declined", "dec@example.com",
                checkIn, checkIn.plusDays(1), 2, 1));

        PaymentResult result = paymentService.pay(new PayBookingCommand(booking.id(),
                PaymentMethod.CARD, MockCardGateway.DECLINE_PREFIX + "-" + key(), "bad-card"));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).contains("declined");
        assertThat(result.booking().status())
                .as("the guest keeps the hold and can retry with another method")
                .isEqualTo(BookingStatus.PENDING_PAYMENT);

        // Proving the retry actually works is the point of leaving it PENDING_PAYMENT.
        PaymentResult retry = paymentService.pay(new PayBookingCommand(booking.id(),
                PaymentMethod.UPI, key(), "good@upi"));
        assertThat(retry.booking().status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("an expired hold releases its room and refuses a late payment")
    void expiredHoldReleasesInventory() {
        TestFixtures.Onboarded hotel = uniqueHotel("Lapsed Inn", 1);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(12);
        LocalDate checkOut = checkIn.plusDays(1);

        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Slow Payer", "slow@example.com",
                checkIn, checkOut, 2, 1));
        assertThat(search(checkIn, checkOut, hotel.propertyId())).isEmpty();

        // Walk past the 15-minute hold. No sleeping, no flakiness.
        clock.advanceBy(Duration.ofMinutes(16));

        // At least one, not exactly one: the sweep is global and earlier tests in this class leave
        // their own unpaid bookings behind, which advancing the clock also lapses. Asserting on this
        // booking's status is the precise check; the count is only a sanity signal.
        assertThat(bookingService.expireLapsedHolds()).isGreaterThanOrEqualTo(1);
        assertThat(bookingService.require(booking.id()).status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(search(checkIn, checkOut, hotel.propertyId()))
                .as("the abandoned hold's room is back on sale")
                .hasSize(1);

        assertThatThrownBy(() -> paymentService.pay(new PayBookingCommand(
                booking.id(), PaymentMethod.CARD, key(), "late")))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    /**
     * The gap the sweeper cannot cover: a payment that arrives after the hold lapsed but before the
     * next sweep. Rejecting it is only half the job — the rooms have to actually come back.
     *
     * <p>This pins the transaction boundary. The rejection is a thrown exception, so it rolls the
     * payment transaction back; if the expiry and release rode in that same transaction they would
     * be silently undone, and the room would stay held by a booking reporting itself EXPIRED. The
     * assertions below fail if that separate committed transaction is ever removed.
     */
    @Test
    @DisplayName("paying after the hold lapsed releases the room even though the payment is rejected")
    void lapsedHoldIsReleasedEvenWhenThePaymentIsRejected() {
        TestFixtures.Onboarded hotel = uniqueHotel("Too Late Inn", 1);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(40);
        LocalDate checkOut = checkIn.plusDays(1);

        Booking booking = bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Slow Payer", "slow@example.com",
                checkIn, checkOut, 2, 1));
        assertThat(search(checkIn, checkOut, hotel.propertyId())).isEmpty();

        clock.advanceBy(Duration.ofMinutes(16));

        // Deliberately no sweep here - this is the window the sweeper has not reached yet.
        assertThatThrownBy(() -> paymentService.pay(new PayBookingCommand(
                booking.id(), PaymentMethod.CARD, key(), "too-late")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("expired");

        assertThat(bookingService.require(booking.id()).status())
                .as("the expiry must survive the rejected payment's rollback")
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(search(checkIn, checkOut, hotel.propertyId()))
                .as("and so must the inventory release")
                .hasSize(1);
    }

    @Test
    @DisplayName("a party too large for the rooms requested is refused before any inventory is taken")
    void partySizeIsValidated() {
        TestFixtures.Onboarded hotel = uniqueHotel("Occupancy Inn", 4);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(13);

        assertThatThrownBy(() -> bookingService.create(new CreateBookingCommand(
                hotel.propertyId(), hotel.roomTypeId(), "Big Family", "big@example.com",
                checkIn, checkIn.plusDays(1), 5, 1)))
                .isInstanceOf(com.rupeek.hotelbooking.domain.exception.ValidationException.class)
                .hasMessageContaining("do not fit");

        assertThat(search(checkIn, checkIn.plusDays(1), hotel.propertyId()).get(0)
                .availableRoomTypes().get(0).roomsAvailable())
                .as("a rejected booking must not have consumed anything")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("a room type from another property cannot be booked through this one")
    void mismatchedPropertyAndRoomTypeIsRefused() {
        TestFixtures.Onboarded first = uniqueHotel("Mismatch A", 2);
        TestFixtures.Onboarded second = uniqueHotel("Mismatch B", 2);
        LocalDate checkIn = TestClockConfiguration.today().plusDays(14);

        assertThatThrownBy(() -> bookingService.create(new CreateBookingCommand(
                first.propertyId(), second.roomTypeId(), "Confused", "c@example.com",
                checkIn, checkIn.plusDays(1), 2, 1)))
                .isInstanceOf(com.rupeek.hotelbooking.domain.exception.ValidationException.class)
                .hasMessageContaining("does not belong to");
    }

    /**
     * A price ceiling must narrow the rooms offered, not just the hotels listed.
     *
     * <p>The property qualifies on its cheap room, which is correct — it does have something in
     * budget. The bug this pins is what happened next: every other room type rode in on that
     * qualification, so a guest who capped their budget at 10,000 was quoted a 25,000 suite. The
     * property-level filter cannot express this, because the thing being filtered is a room.
     */
    @Test
    @DisplayName("a price ceiling excludes over-budget room types, not just over-budget properties")
    void priceCeilingAppliesToRoomTypesNotOnlyProperties() {
        String name = "Two Tier Hotel " + System.nanoTime();
        PropertyGroup group = onboardingService.onboard(new OnboardPropertyGroupCommand(
                name + " Group", "owner+" + UUID.randomUUID() + "@example.com",
                List.of(new OnboardPropertyGroupCommand.PropertySpec(
                        name, "Bengaluru", "Indiranagar", "100 Ft Road", 4,
                        Set.of(Amenity.WIFI), FlexibleCancellationPolicy.CODE,
                        List.of(
                                new OnboardPropertyGroupCommand.RoomTypeSpec(
                                        "Deluxe", 2, 3, new BigDecimal("6000.00")),
                                new OnboardPropertyGroupCommand.RoomTypeSpec(
                                        "Penthouse", 2, 1, new BigDecimal("25000.00")))))));
        UUID propertyId = group.properties().get(0).id();

        LocalDate checkIn = TestClockConfiguration.today().plusDays(50);
        LocalDate checkOut = checkIn.plusDays(3);

        List<PropertySearchResult> capped = searchService.search(
                        PropertySearchCriteria.in("Bengaluru")
                                .stay(DateRange.of(checkIn, checkOut))
                                .guests(2)
                                .priceBetween(null, Money.inr(10_000))
                                .build())
                .stream().filter(r -> r.propertyId().equals(propertyId)).toList();

        assertThat(capped).as("the property still qualifies on its cheap room").hasSize(1);
        assertThat(capped.get(0).availableRoomTypes())
                .as("but only the in-budget room may be offered")
                .extracting(PropertySearchResult.RoomTypeOption::name)
                .containsExactly("Deluxe");

        List<PropertySearchResult> uncapped = search(checkIn, checkOut, propertyId);
        assertThat(uncapped.get(0).availableRoomTypes())
                .as("with no budget given, nothing is filtered out")
                .hasSize(2);
    }

    private TestFixtures.Onboarded uniqueHotel(String name, int rooms) {
        return TestFixtures.flexibleProperty(onboardingService, name + " " + System.nanoTime(), rooms);
    }

    /**
     * Search, then narrow to the property under test. Other tests share the database, so filtering
     * by id keeps each test independent of what the others onboarded.
     */
    private List<PropertySearchResult> search(LocalDate checkIn, LocalDate checkOut, UUID propertyId) {
        return searchService.search(PropertySearchCriteria.in("Bengaluru")
                        .locality("Indiranagar")
                        .stay(DateRange.of(checkIn, checkOut))
                        .guests(2)
                        .build())
                .stream()
                .filter(result -> result.propertyId().equals(propertyId))
                .toList();
    }

    private static String key() {
        return "test-" + UUID.randomUUID();
    }
}
