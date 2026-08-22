package com.rupeek.hotelbooking.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * One error shape for every failure the API can return.
 *
 * <p>{@code code} is a stable machine-readable token; {@code message} is for a human;
 * {@code details} carries whatever structured extras a specific failure has — the field errors of a
 * rejected payload, or the first night of a stay that could not be satisfied. Clients branch on the
 * code and never have to parse the message, which means the message can be improved without
 * breaking anyone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        Map<String, Object> details) {

    public static ErrorResponse of(String code, String message, Instant timestamp) {
        return new ErrorResponse(code, message, timestamp, null);
    }

    public static ErrorResponse of(String code, String message, Instant timestamp,
                                   Map<String, Object> details) {
        return new ErrorResponse(code, message, timestamp, details);
    }
}
