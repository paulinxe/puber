package com.puber.rider.config;

import com.puber.contracts.quote.v1.QuoteServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.client.ImportGrpcClients;

/**
 * The gRPC client side: the {@code matching} channel, the stubs imported from it -- named one by
 * one, never package-scanned -- and the interceptors attached to it.
 */
@Configuration
@ImportGrpcClients(target = "matching", types = QuoteServiceGrpc.QuoteServiceBlockingStub.class)
class GrpcClientConfiguration {

    /** The annotation is what attaches it. See project-context.md, "The gRPC client". */
    @Bean
    @GlobalClientInterceptor
    RequestIdClientInterceptor requestIdClientInterceptor() {
        return new RequestIdClientInterceptor();
    }
}
