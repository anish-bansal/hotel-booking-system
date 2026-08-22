package com.rupeek.hotelbooking.application.command;

import java.util.UUID;

/** Add a property to an owner account that already exists — how a chain grows. */
public record AddPropertyCommand(
        UUID groupId,
        OnboardPropertyGroupCommand.PropertySpec property) {
}
