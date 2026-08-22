package com.rupeek.hotelbooking.api.dto;

import com.rupeek.hotelbooking.application.result.CancellationResult;

public record CancellationResponse(
        BookingResponse booking,
        String appliedPolicy,
        MoneyDto refundAmount,
        String refundReason,
        int roomsReleased) {

    public static CancellationResponse from(CancellationResult result) {
        return new CancellationResponse(
                BookingResponse.from(result.booking()),
                result.appliedPolicy(),
                MoneyDto.from(result.refundDecision().refundAmount()),
                result.refundDecision().reason(),
                result.roomsReleased());
    }
}
