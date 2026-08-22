package com.rupeek.hotelbooking.api.error;

import com.rupeek.hotelbooking.domain.exception.IllegalStateTransitionException;
import com.rupeek.hotelbooking.domain.exception.InventoryUnavailableException;
import com.rupeek.hotelbooking.domain.exception.NotFoundException;
import com.rupeek.hotelbooking.domain.exception.PaymentFailedException;
import com.rupeek.hotelbooking.domain.exception.ValidationException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain failures into HTTP, in one place.
 *
 * <p>This class is the reason no service or entity in this codebase imports anything from
 * {@code org.springframework.http}. The domain throws exceptions that describe <em>what went
 * wrong in business terms</em>; deciding that an unavailable room is a 409 and a bad date is a 400
 * is a transport concern, and it lives here. The practical payoff is that the same services could be
 * driven from a message consumer or a CLI without dragging status codes along.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> onValidation(ValidationException e) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage(), null);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> onNotFound(NotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null);
    }

    /**
     * 409 rather than 400: the request was perfectly well formed, it just lost a race for a finite
     * resource. The client's correct response is to try different dates, not to fix its payload.
     */
    @ExceptionHandler(InventoryUnavailableException.class)
    public ResponseEntity<ErrorResponse> onUnavailable(InventoryUnavailableException e) {
        return respond(HttpStatus.CONFLICT, "INVENTORY_UNAVAILABLE", e.getMessage(),
                Map.of("firstUnavailableNight", e.firstUnavailableNight().toString()));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ErrorResponse> onIllegalTransition(IllegalStateTransitionException e) {
        return respond(HttpStatus.CONFLICT, "ILLEGAL_STATE_TRANSITION", e.getMessage(), null);
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ErrorResponse> onPaymentFailed(PaymentFailedException e) {
        return respond(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_FAILED", e.getMessage(), null);
    }

    /**
     * In this service a constraint violation means one specific thing: two concurrent payment
     * requests carried the same idempotency key and this one lost. That is the unique constraint
     * doing its job — exactly one charge happened — so it is reported as a conflict, and a retry
     * will find the winner's record and get the replay.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> onConstraintViolation(DataIntegrityViolationException e) {
        log.warn("Constraint violation, most likely a concurrent duplicate request: {}",
                e.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, "DUPLICATE_REQUEST",
                "A concurrent request with the same idempotency key is already being processed."
                        + " Retry to receive its result.", null);
    }

    /**
     * Two requests tried to change the same booking at once and this one lost the version check.
     * Like an inventory conflict this is a 409, not a 500: the request was valid, it simply raced.
     * Nothing was written, so the client can safely re-read and retry.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> onConcurrentModification(OptimisticLockingFailureException e) {
        log.warn("Concurrent modification rejected by the version check: {}", e.getMessage());
        return respond(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "This booking was modified by another request. Re-read it and try again.", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onInvalidPayload(MethodArgumentNotValidException e) {
        Map<String, Object> fieldErrors = new HashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request payload failed validation", fieldErrors);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> onIllegalArgument(IllegalArgumentException e) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage(), null);
    }

    /**
     * The catch-all deliberately does not echo {@code e.getMessage()} to the caller. An unexpected
     * failure's message can carry SQL fragments or internal identifiers, and leaking those is how
     * an internal error becomes an information disclosure. The stack trace goes to the log, where it
     * is useful, and the client gets a correlation-friendly generic message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong handling this request", null);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message,
                                                  Map<String, Object> details) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(code, message, clock.instant(), details));
    }
}
