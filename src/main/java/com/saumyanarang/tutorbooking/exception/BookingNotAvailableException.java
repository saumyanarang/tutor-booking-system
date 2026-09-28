package com.saumyanarang.tutorbooking.exception;

/**
 * Exception thrown when a booking is not available for the requested time
 * or when no suitable time slots are available.
 */
public class BookingNotAvailableException extends BusinessException {
    public BookingNotAvailableException(String message) {
        super(message);
    }
}
