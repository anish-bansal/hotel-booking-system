package com.rupeek.hotelbooking.api;

import com.rupeek.hotelbooking.api.dto.MoneyDto;
import com.rupeek.hotelbooking.api.dto.PaymentRequest;
import com.rupeek.hotelbooking.api.dto.PaymentResponse;
import com.rupeek.hotelbooking.application.PaymentService;
import com.rupeek.hotelbooking.application.command.PayBookingCommand;
import com.rupeek.hotelbooking.application.result.PaymentResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment for a booking. */
@RestController
@RequestMapping("/api/v1/bookings/{bookingId}/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Take payment.
     *
     * <p>{@code Idempotency-Key} is a required header. Making it required rather than optional is a
     * deliberate bit of friction: a caller that has not thought about retries is a caller that will
     * eventually double-charge someone, and a 400 at integration time is a much cheaper way to learn
     * that than a duplicate charge in production.
     *
     * <p>A replayed result returns 200 while a freshly processed one returns 201, so a client can
     * tell from the status line alone whether its retry created something.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        PaymentResult result = paymentService.pay(new PayBookingCommand(
                bookingId, request.method(), idempotencyKey, request.payerReference()));

        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(PaymentResponse.from(result));
    }

    @GetMapping
    public List<PaymentAttempt> attempts(@PathVariable UUID bookingId) {
        return paymentService.paymentsFor(bookingId).stream()
                .map(p -> new PaymentAttempt(p.id(), p.method().name(), MoneyDto.from(p.amount()),
                        p.status().name(), p.gatewayReference(), p.failureReason()))
                .toList();
    }

    /** Deliberately flatter than {@link PaymentResponse}: a history list does not need the booking. */
    public record PaymentAttempt(UUID id, String method, MoneyDto amount, String status,
                                 String gatewayReference, String failureReason) {
    }
}
