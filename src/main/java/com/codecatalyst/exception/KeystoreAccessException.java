package com.codecatalyst.exception;

public class KeystoreAccessException extends RuntimeException {

    public KeystoreAccessException(String message) {
        super(message);
    }

    public KeystoreAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
