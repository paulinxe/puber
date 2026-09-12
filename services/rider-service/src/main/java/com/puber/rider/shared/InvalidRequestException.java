package com.puber.rider.shared;

/**
 * A request this service rejected itself, carrying a message written here rather than by a
 * framework.
 *
 * <p>A dedicated type so {@link ErrorDetailsHandler} can echo the message: Spring's own binding
 * exceptions are handled by {@code ResponseEntityExceptionHandler} and get a fixed detail instead,
 * because their messages name Java parameter types an external caller has no business reading.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
