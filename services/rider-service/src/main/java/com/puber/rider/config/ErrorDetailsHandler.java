package com.puber.rider.config;

import com.puber.rider.shared.InvalidRequestException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into RFC 9457 Problem Details carrying the request id, never a stack trace
 * (AD-38). The parent class is what covers 404, 405, 415 and an unreadable body -- see
 * project-context.md, "Boot 4.1 / Java 25", before adding a handler here.
 */
@RestControllerAdvice
class ErrorDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorDetailsHandler.class);

    /** A request this service rejected itself, so the message is ours and is safe to echo. */
    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail handleInvalidRequest(InvalidRequestException rejected) {
        return problem(HttpStatus.BAD_REQUEST, rejected.getMessage());
    }

    @ExceptionHandler(StatusRuntimeException.class)
    ProblemDetail handleUpstreamFailure(StatusRuntimeException rejected) {
        Status status = rejected.getStatus();
        return switch (status.getCode()) {
            // matching-service names the offending field in the description, and that is the only
            // place a caller can learn which of the four coordinates it got wrong.
            case INVALID_ARGUMENT -> problem(HttpStatus.BAD_REQUEST, describe(status));
            case UNAVAILABLE ->
                    problem(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "the quote could not be obtained right now");
            default -> {
                LOGGER.error("the quote call failed with {}", status, rejected);
                // Fixed text, never the upstream description: the detail belongs in this
                // service's log, not on a wire an external caller reads.
                yield problem(
                        HttpStatus.INTERNAL_SERVER_ERROR, "the request could not be answered");
            }
        };
    }

    /**
     * Every body the parent produces passes through here, so the id is attached once. Not {@code
     * handleExceptionInternal} -- see project-context.md, "Boot 4.1 / Java 25".
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            attachRequestId(problem);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        attachRequestId(problem);
        return problem;
    }

    private static void attachRequestId(ProblemDetail problem) {
        String requestId = MDC.get(RequestId.MDC_KEY);
        if (requestId != null) {
            problem.setProperty(RequestId.MDC_KEY, requestId);
        }
    }

    private static String describe(Status status) {
        String description = status.getDescription();
        return description == null || description.isBlank()
                ? "the quote request was rejected"
                : description;
    }
}
