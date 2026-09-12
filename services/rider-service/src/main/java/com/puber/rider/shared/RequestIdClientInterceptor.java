package com.puber.rider.shared;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.slf4j.MDC;

/**
 * AD-54: the request id crosses the gRPC hop, so one id traces a rider's request through both
 * services.
 *
 * <p>Carries no stereotype on purpose. A {@code ClientInterceptor} attaches to a channel, and the
 * channel is what {@code GrpcClientConfiguration} configures, so it is declared as a {@code @Bean}
 * there -- with {@code @GlobalClientInterceptor}, without which the bean is built and never
 * attached.
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
