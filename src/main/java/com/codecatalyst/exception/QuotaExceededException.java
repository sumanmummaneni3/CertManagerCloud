package com.codecatalyst.exception;
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) { super(message); }
}
