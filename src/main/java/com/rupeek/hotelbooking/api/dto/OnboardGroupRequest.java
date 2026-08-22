package com.rupeek.hotelbooking.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Onboard an owner with one or many properties.
 *
 * <p>Note there is no {@code type: SINGLE | CHAIN} field. The shape of the request tells you which
 * it is — a one-element list is a standalone hotel — and asking the client to also declare it would
 * create a second source of truth that can contradict the first.
 */
public record OnboardGroupRequest(
        @NotBlank String groupName,
        @NotBlank @Email String contactEmail,
        @NotEmpty @Valid List<PropertyRequest> properties) {
}
