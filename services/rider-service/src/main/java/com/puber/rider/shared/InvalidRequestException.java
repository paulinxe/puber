package com.puber.rider.shared;

/**
 * A request this service rejected itself, so its message was written here and is safe to echo. Its
 * own type for that reason -- see project-context.md, "Boot 4.1 / Java 25".
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
