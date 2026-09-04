package com.pigfox.springboot.domain;

/**
 * Raised when the token endpoint is called with credentials that do not match the
 * configured client. The message is deliberately generic so the response cannot be
 * used to distinguish an unknown client from a wrong secret.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid client credentials");
    }
}
