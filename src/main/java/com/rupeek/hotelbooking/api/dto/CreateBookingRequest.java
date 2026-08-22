package com.rupeek.hotelbooking.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record CreateBookingRequest(
        @NotNull UUID propertyId,
        @NotNull UUID roomTypeId,
        @NotBlank String guestName,
        @NotBlank @Email String guestEmail,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @Min(1) int guests,
        @Min(1) int rooms) {
}
