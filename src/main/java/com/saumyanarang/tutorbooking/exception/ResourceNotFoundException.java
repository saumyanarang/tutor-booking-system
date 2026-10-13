package com.saumyanarang.tutorbooking.exception;

/** Thrown when a slot, booking or user that the caller refers to does not exist (HTTP 404). */
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
