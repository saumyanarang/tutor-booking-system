package com.saumyanarang.tutorbooking.exception;

/** Thrown at sign-up when the email or username is already taken (HTTP 409). */
public class UserAlreadyExistsException extends BusinessException {
    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
