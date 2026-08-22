package com.rupeek.hotelbooking.application;

import com.rupeek.hotelbooking.application.command.PayBookingCommand;
import com.rupeek.hotelbooking.application.result.PaymentResult;
import com.rupeek.hotelbooking.domain.exception.IllegalStateTransitionException;
import com.rupeek.hotelbooking.domain.exception.NotFoundException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.BookingStatus;
import com.rupeek.hotelbooking.domain.model.Payment;
import com.rupeek.hotelbooking.domain.port.BookingRepository;
import com.rupeek.hotelbooking.domain.port.PaymentGateway;
import com.rupeek.hotelbooking.domain.port.PaymentGatewayRegistry;
import com.rupeek.hotelbooking.domain.port.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes payment for a booking and lets the outcome drive the booking's state.
 *
 * <h2>The payment outcome owns the booking state</h2>
 *
 * Nothing outside this service can confirm a booking. A successful charge calls
 * {@code booking.confirm(now)}; a declined one leaves the booking in {@code PENDING_PAYMENT} so the
 * guest can try a different method while the hold lasts. The brief asks for the payment outcome to
 * drive the booking state, and the way to guarantee that is to leave exactly one caller of
 * {@code confirm()} in the codebase.
 *
 * <h2>Idempotency</h2>
 *
 * Payment is the one operation where a retry can cost the customer real money, and it is also the
 * operation most likely to be retried — a client that times out mid-charge genuinely does not know
 * whether the money moved. Two mechanisms cover the two shapes that takes:
 *
 * <ul>
 *   <li><b>Sequential retry</b> (the common case): the key is already on record, so
 *       {@link #pay} returns the stored outcome untouched and flags it as a replay. The gateway is
 *       never called a second time.
 *   <li><b>Concurrent duplicate</b> (double-click, parallel retry): both requests find no record
 *       and both attempt an insert. The unique constraint on {@code idempotency_key} lets exactly
 *       one commit; the loser's transaction is rejected and surfaces as HTTP 409, and its retry then
 *       takes the replay path above. Correctness here rests on the database constraint rather than
 *       on a check-then-act in application code, because a check-then-act has an unavoidable window
 *       between the check and the act.
 * </ul>
 *
 * <p>Ordering is the whole trick, and it is easy to get subtly wrong: the key must be claimed in the
 * database <em>before</em> the gateway is called, and claimed in a way that reaches the database
 * immediately rather than at commit. Insert-then-charge with a deferred insert looks correct and is
 * not — both requests would pass the lookup, both would charge, and only one would leave a record.
 *
 * <p>One consequence worth being explicit about: a declined charge also consumes its key. Retrying
 * with the same key replays the decline rather than attempting a fresh charge, which is the correct
 * reading of idempotency — the same request yields the same outcome. A guest who wants to genuinely
 * try again gets a new key, which is what the API's clients do naturally per checkout attempt.
 *
 * <p>The gateway call carries the key onward too, so even a duplicate that somehow reached the
 * provider twice would be collapsed at their end. Defence in depth, since the money is real.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final PaymentGatewayRegistry gateways;
    private final BookingService bookingService;
    private final Clock clock;

    public PaymentService(PaymentRepository paymentRepository,
                          BookingRepository bookingRepository,
                          PaymentGatewayRegistry gateways,
                          BookingService bookingService,
                          Clock clock) {
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
        this.gateways = gateways;
        this.bookingService = bookingService;
        this.clock = clock;
    }

    @Transactional
    public PaymentResult pay(PayBookingCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new ValidationException("an Idempotency-Key is required to take payment");
        }

        Optional<Payment> alreadySeen = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (alreadySeen.isPresent()) {
            Payment payment = alreadySeen.get();
            log.info("Replaying payment {} for idempotency key {} - gateway not called again",
                    payment.id(), command.idempotencyKey());
            return PaymentResult.replay(payment, requireBooking(payment.bookingId()));
        }

        Booking booking = requireBooking(command.bookingId());
        assertPayable(booking);

        Payment payment = Payment.initiate(booking.id(), command.idempotencyKey(), command.method(),
                booking.totalAmount(), clock.instant());

        // Claim the key BEFORE calling the gateway, and claim it in a way that actually reaches the
        // database now rather than at commit. A deferred insert would let two concurrent requests
        // both pass the lookup above, both charge the card, and only one keep a record of it. If
        // this line loses the race it throws here, and no money has moved.
        payment = paymentRepository.saveAndClaimIdempotencyKey(payment);

        PaymentGateway gateway = gateways.forMethod(command.method());
        PaymentGateway.ChargeResult result = gateway.charge(new PaymentGateway.ChargeCommand(
                command.idempotencyKey(), booking.totalAmount(), command.payerReference()));

        Instant now = clock.instant();
        if (result.successful()) {
            payment.markSuccessful(result.gatewayReference(), now);
            booking.confirm(now);
            log.info("Booking {} confirmed by payment {} via {}", booking.id(), payment.id(),
                    command.method());
        } else {
            payment.markFailed(result.failureReason(), now);
            log.warn("Payment {} for booking {} declined: {}", payment.id(), booking.id(),
                    result.failureReason());
        }

        paymentRepository.save(payment);
        bookingRepository.save(booking);
        return PaymentResult.processed(payment, booking);
    }

    @Transactional(readOnly = true)
    public List<Payment> paymentsFor(UUID bookingId) {
        return paymentRepository.findByBookingId(bookingId);
    }

    /**
     * A booking is payable only from {@code PENDING_PAYMENT}, and only while its hold is alive.
     *
     * <p>Expiring the lapsed hold here rather than just rejecting the payment matters: the sweeper
     * runs on a schedule, so a payment can legitimately arrive after the hold lapsed but before the
     * sweep. Releasing the rooms on the spot keeps the guest from paying for a hold that no longer
     * exists, and returns the inventory a sweep interval earlier than it otherwise would.
     *
     * <p><b>The expiry must not happen in this transaction.</b> Rejecting the payment means throwing,
     * and a throw rolls this transaction back — which would silently undo the very release it just
     * performed, leaving the rooms held by a booking that reported itself expired. Delegating to
     * {@link BookingService#expireHoldNow} runs it in a suspended transaction that commits on its
     * own, so the release survives the rejection.
     */
    private void assertPayable(Booking booking) {
        if (booking.status() != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateTransitionException("Booking", booking.status(),
                    BookingStatus.CONFIRMED);
        }
        if (booking.isHoldExpired(clock.instant())) {
            bookingService.expireHoldNow(booking.id());
            throw new ValidationException("The payment hold on booking " + booking.id()
                    + " expired at " + booking.holdExpiresAt() + "; the rooms have been released."
                    + " Please create a new booking.");
        }
    }

    private Booking requireBooking(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
    }
}
