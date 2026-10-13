package com.saumyanarang.tutorbooking.dto.booking;

import com.saumyanarang.tutorbooking.entity.Booking;

import java.time.LocalDateTime;

public record BookingDetailsDto(
        Long bookingId,
        Long slotId,
        Long tutorId,
        String studentName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        LocalDateTime reservedAt) {

    public static BookingDetailsDto from(Booking booking) {
        return new BookingDetailsDto(
                booking.getId(),
                booking.getSlot().getId(),
                booking.getSlot().getTutorId(),
                booking.getUser().getUserName(),
                booking.getSlot().getStartTime(),
                booking.getSlot().getEndTime(),
                booking.getReservedAt());
    }
}
