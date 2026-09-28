package com.saumyanarang.tutorbooking.exception;

/**
 * Exception thrown when the system has reached capacity limits
 * (e.g., no more slots available for a given day or resource).
 */
public class BookingCapacityExceededException extends BusinessException {
    public BookingCapacityExceededException(String message) {
        super(message);
    }
}
