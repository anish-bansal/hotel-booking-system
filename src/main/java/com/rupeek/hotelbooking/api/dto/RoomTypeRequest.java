package com.rupeek.hotelbooking.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RoomTypeRequest(
        @NotBlank String name,
        @Min(1) int maxOccupancy,
        @Min(1) int totalRooms,
        @NotNull @DecimalMin(value = "0.01") BigDecimal basePricePerNight) {
}
