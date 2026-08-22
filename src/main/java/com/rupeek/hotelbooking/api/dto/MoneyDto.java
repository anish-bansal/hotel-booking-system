package com.rupeek.hotelbooking.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.rupeek.hotelbooking.domain.vo.Money;
import java.math.BigDecimal;

/**
 * Money on the wire.
 *
 * <p>An explicit DTO rather than letting Jackson reflect over {@link Money}. Serialising the domain
 * type directly would mean the JSON contract changed whenever the value object's internals did, and
 * it would tempt someone into adding a no-arg constructor and setters to a class whose entire value
 * is being immutable.
 *
 * <p><b>The amount is a JSON string, not a JSON number.</b> The domain is careful to keep money in
 * {@code BigDecimal} precisely so no rounding creeps in — and emitting it as a bare number hands it
 * straight back: JSON numbers have no defined precision, and every JavaScript client parses them
 * into an IEEE-754 double. A string crosses the wire exactly as scaled, and callers that want
 * arithmetic have to reach for a decimal type deliberately, which is the correct nudge.
 */
public record MoneyDto(
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency) {

    public static MoneyDto from(Money money) {
        return money == null ? null : new MoneyDto(money.amount(), money.currencyCode());
    }
}
