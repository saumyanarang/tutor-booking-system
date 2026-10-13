package com.saumyanarang.tutorbooking.exception;

/**
 * Thrown when a specific slot cannot be taken: it is already booked, another request is booking it
 * right now, or a new slot would overlap one the tutor already published (HTTP 409).
 */
public class SlotUnavailableException extends BusinessException {
    public SlotUnavailableException(String message) {
        super(message);
    }
}
