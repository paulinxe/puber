package com.puber.rider.config;

import io.grpc.Metadata;
import java.util.UUID;

/** The only place this service spells the request id. See project-context.md, AD-54. */
public final class RequestId {

    public static final String HEADER = "X-Request-Id";

    private static final String METADATA_NAME = "x-request-id";

    public static final String MDC_KEY = "requestId";

    public static final Metadata.Key<String> METADATA_KEY =
            Metadata.Key.of(METADATA_NAME, Metadata.ASCII_STRING_MARSHALLER);

    private RequestId() {}

    public static String mint() {
        return UUID.randomUUID().toString();
    }
}
