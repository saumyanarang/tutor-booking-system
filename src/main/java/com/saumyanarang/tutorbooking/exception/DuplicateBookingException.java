package com.saumyanarang.tutorbooking.exception;

/**
 * Exception thrown when a user attempts to make a duplicate booking
 * (e.g., booking the same slot twice).
 */
public class DuplicateBookingException extends BusinessException {
    public DuplicateBookingException(String message) {
        super(message);
    }
}
