package com.puber.matching.config;

import io.grpc.Status;
import io.grpc.StatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * The last word on any failure no feature claimed, so a broken deployment is loud instead of blank.
 * Without it the caller gets UNKNOWN with no description and nothing is logged -- see
 * project-context.md, "The gRPC server". The description is fixed text: the detail belongs in this
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
