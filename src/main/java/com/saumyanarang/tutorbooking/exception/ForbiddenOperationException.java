package com.saumyanarang.tutorbooking.exception;

/** Thrown when an authenticated user tries to do something their role or ownership does not allow (HTTP 403). */
public class ForbiddenOperationException extends BusinessException {
    public ForbiddenOperationException(String message) {
        super(message);
    }
}
