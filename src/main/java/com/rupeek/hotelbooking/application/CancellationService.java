package com.rupeek.hotelbooking.application;

import com.rupeek.hotelbooking.application.command.CancelBookingCommand;
import com.rupeek.hotelbooking.application.result.CancellationResult;
import com.rupeek.hotelbooking.domain.exception.NotFoundException;
import com.rupeek.hotelbooking.domain.exception.PaymentFailedException;
import com.rupeek.hotelbooking.domain.model.Booking;
import com.rupeek.hotelbooking.domain.model.Payment;
import com.rupeek.hotelbooking.domain.model.Property;
import com.rupeek.hotelbooking.domain.policy.CancellationPolicy;
import com.rupeek.hotelbooking.domain.policy.CancellationPolicyRegistry;
import com.rupeek.hotelbooking.domain.policy.RefundDecision;
import com.rupeek.hotelbooking.domain.port.BookingRepository;
import com.rupeek.hotelbooking.domain.port.PaymentGateway;
import com.rupeek.hotelbooking.domain.port.PaymentGatewayRegistry;
import com.rupeek.hotelbooking.domain.port.PaymentRepository;
import com.rupeek.hotelbooking.domain.port.PropertyRepository;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels a booking: releases the rooms, then settles whatever refund the property's policy allows.
 *
 * <h2>Releasing rooms and refunding money are separate concerns</h2>
 *
 * A non-refundable booking still gives its rooms back. Tying the two together would mean a
 * non-refundable cancellation left rooms locked up for a stay nobody was going to turn up for —
 * punishing the hotel for the guest's cheap rate. So the inventory release is unconditional and the
 * refund is a policy decision, and the two never consult each other.
 *
 * <h2>Order of operations</h2>
 *
 * State change, then inventory, then money. The booking transitions first because
 * {@link Booking#cancel} is what rejects a double cancellation — doing it first means a second
 * cancellation request cannot get as far as releasing the same rooms twice or issuing a second
 * refund. The gateway call comes last, so an illegal-state or inventory failure aborts before any
 * money moves.
 *
 * <p>The one honest caveat: the refund goes through a network call inside the database transaction.
 * If the gateway succeeds but the commit then fails, our records would disagree with the provider's.
 * A production system resolves this with an outbox — persist the intent, settle it asynchronously,
 * reconcile — which is called out in the "what I would do next" section of the README rather than
 * silently pretended away here.
 */
@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    private final BookingRepository bookingRepository;
    private final PropertyRepository propertyRepository;
    private final PaymentRepository paymentRepository;
    private final CancellationPolicyRegistry cancellationPolicies;
    private final PaymentGatewayRegistry gateways;
    private final InventoryService inventoryService;
    private final Clock clock;

    public CancellationService(BookingRepository bookingRepository,
                               PropertyRepository propertyRepository,
                               PaymentRepository paymentRepository,
                               CancellationPolicyRegistry cancellationPolicies,
                               PaymentGatewayRegistry gateways,
                               InventoryService inventoryService,
                               Clock clock) {
        this.bookingRepository = bookingRepository;
        this.propertyRepository = propertyRepository;
        this.paymentRepository = paymentRepository;
        this.cancellationPolicies = cancellationPolicies;
        this.gateways = gateways;
        this.inventoryService = inventoryService;
        this.clock = clock;
    }

    @Transactional
    public CancellationResult cancel(CancelBookingCommand command) {
        Booking booking = bookingRepository.findById(command.bookingId())
                .orElseThrow(() -> new NotFoundException("Booking", command.bookingId()));

        Property property = propertyRepository.findById(booking.propertyId())
                .orElseThrow(() -> new NotFoundException("Property", booking.propertyId()));
        CancellationPolicy policy = cancellationPolicies.resolve(property.cancellationPolicyCode());

        Instant now = clock.instant();
        boolean wasHoldingInventory = booking.holdsInventory();

        // First, because this is what rejects cancelling an already-cancelled booking.
        booking.cancel(now);

        int roomsReleased = 0;
        if (wasHoldingInventory) {
            inventoryService.release(booking.roomTypeId(), booking.stay(), booking.roomCount());
            roomsReleased = booking.roomCount();
        }

        RefundDecision decision = settleRefund(booking, policy, now);
        bookingRepository.save(booking);

        log.info("Cancelled booking {} under policy {}: {} room(s) released, refund {}",
                booking.id(), policy.code(), roomsReleased, decision.refundAmount());
        return new CancellationResult(booking, decision, policy.code(), roomsReleased);
    }

    /**
     * Applies the policy — but only if there is money to give back.
     *
     * <p>An unpaid booking is cancelled with no refund regardless of how generous the policy is.
     * Asking the policy first and then discovering there was no payment would produce a decision
     * saying "full refund" against a booking that was never charged, which is exactly the kind of
     * record that later reads as a missing payout.
     */
    private RefundDecision settleRefund(Booking booking, CancellationPolicy policy, Instant now) {
        Optional<Payment> settledPayment = paymentRepository.findSuccessfulByBookingId(booking.id());
        if (settledPayment.isEmpty()) {
            return RefundDecision.none(Money.zero(booking.totalAmount().currency()),
                    "No payment had been taken, so there is nothing to refund");
        }

        RefundDecision decision = policy.evaluate(booking, now);
        if (!decision.isRefundDue()) {
            return decision;
        }

        Payment payment = settledPayment.get();
        PaymentGateway gateway = gateways.forMethod(payment.method());
        PaymentGateway.RefundResult result = gateway.refund(new PaymentGateway.RefundCommand(
                payment.gatewayReference(), decision.refundAmount()));

        if (!result.successful()) {
            // Surfaced, not swallowed: rolling back keeps the booking active rather than leaving a
            // guest cancelled and out of pocket. They can retry, and support can see why.
            //
            // A domain exception rather than a raw IllegalStateException, because the difference is
            // visible to the caller: this maps to 402 with the gateway's reason attached, whereas an
            // IllegalStateException falls through to the catch-all and becomes an opaque 500 that
            // tells the guest nothing about why their refund did not happen.
            throw new PaymentFailedException("refund for booking " + booking.id()
                    + " was declined by the gateway: " + result.failureReason());
        }

        payment.markRefunded(decision.refundAmount(), result.gatewayReference(), now);
        paymentRepository.save(payment);
        booking.recordRefund(decision.refundAmount());
        return decision;
    }
}
