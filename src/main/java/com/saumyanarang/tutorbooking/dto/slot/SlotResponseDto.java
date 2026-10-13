package com.saumyanarang.tutorbooking.dto.slot;

import com.saumyanarang.tutorbooking.entity.Slot;

import java.time.LocalDateTime;

public record SlotResponseDto(
        Long id,
        Long tutorId,
        String tutorName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        boolean booked) {

    public static SlotResponseDto from(Slot slot, String tutorName) {
        return new SlotResponseDto(
                slot.getId(),
                slot.getTutorId(),
                tutorName,
                slot.getStartTime(),
                slot.getEndTime(),
                slot.isReserved());
    }
}
