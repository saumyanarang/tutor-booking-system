package com.saumyanarang.tutorbooking.dto.slot;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** A tutor publishing one bookable time slot. Times are local date-times, e.g. 2026-11-02T16:00:00. */
public record CreateSlotRequestDto(
        @NotNull(message = "startTime is required") LocalDateTime startTime,
        @NotNull(message = "endTime is required") LocalDateTime endTime) {
}
