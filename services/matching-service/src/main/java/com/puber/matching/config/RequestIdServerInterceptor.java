package com.puber.matching.config;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * AD-54: every log line a call produces carries the caller's request id. AD-5: a surface the
 * gateway does not front mints its own when none arrives, so no call is ever untraceable.
 */
@Component
@GlobalServerInterceptor
public class RequestIdServerInterceptor implements ServerInterceptor {

    public static final String REQUEST_ID_HEADER = "x-request-id";

    private static final String MDC_KEY = "requestId";

    /**
     * gRPC rejects an uppercase metadata key at construction, so the lowercase spelling matters.
     */
    private static final Metadata.Key<String> REQUEST_ID =
            Metadata.Key.of(REQUEST_ID_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(
            ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {
        String incoming = headers.get(REQUEST_ID);
        String requestId =
                incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;

        // The wrapping goes on the LISTENER, not around startCall. startCall only builds the
        // listener; the service method itself runs later, from onHalfClose -- so setting the id
        // around startCall would leave it unset exactly where the logging happens.
        return new SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
            @Override
            public void onMessage(R message) {
                carrying(requestId, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                carrying(requestId, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                carrying(requestId, super::onCancel);
            }

            @Override
            public void onComplete() {
                carrying(requestId, super::onComplete);
            }

            @Override
            public void onReady() {
                carrying(requestId, super::onReady);
            }
        };
    }

    /**
     * Set and cleared around each callback, because gRPC can deliver them on different pooled
     * threads. Cleared in a finally: a pooled thread otherwise carries this id into the next call's
     * logs, which is worse than no id at all.
     */
    private static void carrying(String requestId, Runnable work) {
        MDC.put(MDC_KEY, requestId);
        try {
            work.run();
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
