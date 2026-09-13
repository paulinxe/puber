package com.puber.rider.config;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.MDC;

/**
 * Carries the request id across the gRPC hop. See project-context.md, AD-54.
 *
 * <p>No stereotype here on purpose: it is declared as a {@code @Bean} in {@code
 * GrpcClientConfiguration}, where the channel it attaches to is configured.
 */
public class RequestIdClientInterceptor implements ClientInterceptor {

    @Override
    public <R, S> ClientCall<R, S> interceptCall(
            MethodDescriptor<R, S> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<S> responseListener, Metadata headers) {
                String requestId = MDC.get(RequestId.MDC_KEY);
                if (requestId != null) {
                    headers.put(RequestId.METADATA_KEY, requestId);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
