package com.puber.matching.config;

import io.grpc.Status;
import io.grpc.StatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * The last word on any failure no feature claimed, so a broken deployment is loud instead of blank.
 *
 * <p>Without this, spring-grpc's own fallback calls {@code Status.fromThrowable}, which returns
 * {@code UNKNOWN.withCause(t)} -- and the cause is not serialized, so the caller gets UNKNOWN with
 * a null description. That fallback's only log call is guarded by {@code isDebugEnabled()}, so at
 * the default INFO level nothing reaches the log either. An empty {@code fare_rules} table reaches
 * this path today: {@code FareRuleRepository} throws {@code IllegalStateException}.
 *
 * <p>The description is fixed text, never the exception's own message: the detail belongs in this
 * service's log, not on a wire an external caller reads.
 */
@Component
public class UnexpectedGrpcFailureStatusMapper implements GrpcExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(UnexpectedGrpcFailureStatusMapper.class);

    @Override
    public StatusException handleException(Throwable exception) {
        LOGGER.error("a gRPC call failed for a reason no handler claimed", exception);
        return Status.INTERNAL
                .withDescription("the service could not answer this call")
                .withCause(exception)
                .asException();
    }
}
