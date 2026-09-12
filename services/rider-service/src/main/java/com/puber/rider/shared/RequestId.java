package com.puber.rider.shared;

import io.grpc.Metadata;
import java.util.UUID;

/**
 * The four names the request id is spelled with: the HTTP header, the gRPC metadata key, the MDC
 * key, and the {@code %X{...}} the log pattern reads. They have to agree and a mismatch is silent
 * -- the filter writes MDC under one name, the pattern reads another, and every log line shows a
 * blank id while the tests still pass.
 *
 * <p>Copied into each service rather than shared as a library. The copies agreeing on these
 * literals is what makes the two services agree on the wire.
 */
public final class RequestId {

    public static final String HEADER = "X-Request-Id";

    /**
     * gRPC rejects an uppercase metadata key at construction, so the lowercase spelling matters.
     */
    public static final String METADATA_NAME = "x-request-id";

    public static final String MDC_KEY = "requestId";

    public static final Metadata.Key<String> METADATA_KEY =
            Metadata.Key.of(METADATA_NAME, Metadata.ASCII_STRING_MARSHALLER);

    private RequestId() {}

    public static String mint() {
        return UUID.randomUUID().toString();
    }
}
