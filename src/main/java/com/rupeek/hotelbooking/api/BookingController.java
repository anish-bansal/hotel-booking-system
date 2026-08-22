package com.rupeek.hotelbooking.api;

import com.rupeek.hotelbooking.api.dto.BookingResponse;
import com.rupeek.hotelbooking.api.dto.CancellationResponse;
import com.rupeek.hotelbooking.api.dto.CreateBookingRequest;
import com.rupeek.hotelbooking.application.BookingService;
import com.rupeek.hotelbooking.application.CancellationService;
import com.rupeek.hotelbooking.application.command.CancelBookingCommand;
import com.rupeek.hotelbooking.application.command.CreateBookingCommand;
import com.rupeek.hotelbooking.domain.model.Booking;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Booking creation, retrieval and cancellation. */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;
    private final CancellationService cancellationService;

    public BookingController(BookingService bookingService,
                             CancellationService cancellationService) {
        this.bookingService = bookingService;
        this.cancellationService = cancellationService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request) {
        Booking booking = bookingService.create(new CreateBookingCommand(
                request.propertyId(), request.roomTypeId(), request.guestName(),
                request.guestEmail(), request.checkIn(), request.checkOut(),
                request.guests(), request.rooms()));

        return ResponseEntity.status(HttpStatus.CREATED).body(BookingResponse.from(booking));
    }

    @GetMapping("/{bookingId}")
    public BookingResponse get(@PathVariable UUID bookingId) {
        return BookingResponse.from(bookingService.require(bookingId));
    }

    @GetMapping
    public List<BookingResponse> byGuest(@RequestParam String guestEmail) {
        return bookingService.findByGuest(guestEmail).stream().map(BookingResponse::from).toList();
    }

    /**
     * Cancellation as a POST to a sub-resource rather than {@code DELETE /bookings/{id}}.
     *
     * <p>A cancellation is not a deletion: the booking survives, changes state, releases rooms and
     * may move money, and the caller needs the refund decision back in the response body. Modelling
     * that as DELETE would both lie about what happened and give up the response.
     */
    @PostMapping("/{bookingId}/cancellation")
    public CancellationResponse cancel(@PathVariable UUID bookingId) {
        return CancellationResponse.from(
                cancellationService.cancel(new CancelBookingCommand(bookingId)));
    }
}
