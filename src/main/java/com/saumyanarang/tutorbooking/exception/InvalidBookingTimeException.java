package com.saumyanarang.tutorbooking.exception;

/**
 * Exception thrown when a booking request violates timing constraints
 * (e.g., booking in the past, booking outside business hours, or requesting times that exceed the maximum allowed duration).
 */
public class InvalidBookingTimeException extends BusinessException {
    public InvalidBookingTimeException(String message) {
        super(message);
    }
}
