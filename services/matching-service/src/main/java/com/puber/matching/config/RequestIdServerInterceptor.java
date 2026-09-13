package com.puber.matching.config;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.slf4j.MDC;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** Mints or forwards the request id for every gRPC call. See project-context.md, AD-54. */
@Component
@GlobalServerInterceptor
public class RequestIdServerInterceptor implements ServerInterceptor {

    @Override
    public <R, S> ServerCall.Listener<R> interceptCall(
            ServerCall<R, S> call, Metadata headers, ServerCallHandler<R, S> next) {
        String incoming = headers.get(RequestId.METADATA_KEY);
        String requestId = incoming == null || incoming.isBlank() ? RequestId.mint() : incoming;

        // Wrap the listener, not startCall: startCall only builds the listener, and the service
        // method itself runs later, from onHalfClose.
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

    /** Around each callback, because gRPC can deliver them on different pooled threads. */
    private static void carrying(String requestId, Runnable work) {
        MDC.put(RequestId.MDC_KEY, requestId);
        try {
            work.run();
        } finally {
            MDC.remove(RequestId.MDC_KEY);
        }
    }
}
