package com.saumyanarang.tutorbooking.dto.booking;

import jakarta.validation.constraints.NotNull;

/** A student asking for one specific slot. */
public record BookSlotRequestDto(@NotNull(message = "slotId is required") Long slotId) {
}
