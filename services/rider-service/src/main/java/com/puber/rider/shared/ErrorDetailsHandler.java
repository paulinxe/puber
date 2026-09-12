package com.puber.rider.shared;

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
 * AD-38's one error vocabulary, mapped at the facade: every failure leaves this service as RFC 9457
 * Problem Details carrying the request id, never as a stack trace.
 *
 * <p>Extends {@code ResponseEntityExceptionHandler} so the statuses Spring resolves before any
 * handler runs -- 404, 405, 415, an unreadable body -- are Problem Details too. Without it those
 * four fall through to Boot's default {@code /error} rendering, which is plain JSON with no request
 * id, and only the paths this class names explicitly satisfy AC4b. Do not add an
 * {@code @ExceptionHandler} for a type that parent already lists: two mappings for one type in one
 * class is an {@code IllegalStateException} at startup, not a silent override.
 *
 * <p>Only the gRPC statuses something can actually produce today are mapped. {@code NOT_FOUND},
 * {@code ALREADY_EXISTS} and {@code FAILED_PRECONDITION} arrive with the stories that create rides;
 * a mapping with no producer is a guard guarding nothing.
 */
@RestControllerAdvice
public class ErrorDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorDetailsHandler.class);

    /** A request this service rejected itself, so the message is ours and is safe to echo. */
    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException rejected) {
        return problem(HttpStatus.BAD_REQUEST, rejected.getMessage());
    }

    @ExceptionHandler(StatusRuntimeException.class)
    public ProblemDetail handleUpstreamFailure(StatusRuntimeException rejected) {
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
     * The one place every Problem Details body the parent produces passes through, so the request
     * id is attached once rather than per overridden handler.
     *
     * <p>Not {@code handleExceptionInternal}: it is called with a null body, and the parent builds
     * the {@code ProblemDetail} from the {@code ErrorResponse} only afterwards. Measured on
     * 2026-09-12 -- overriding that one left the 400, 405 and 415 bodies with no id while still
     * looking correct.
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
