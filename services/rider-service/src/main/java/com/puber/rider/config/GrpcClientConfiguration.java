package com.puber.rider.config;

import com.puber.contracts.quote.v1.QuoteServiceGrpc;
import com.puber.rider.shared.RequestIdClientInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.grpc.client.ImportGrpcClients;

/**
 * The whole gRPC-client setup: the channel named by {@code
 * spring.grpc.client.channel.matching.target}, the stub types injected from it, and the interceptor
 * chain that hangs off it.
 *
 * <p>{@code types} rather than a package scan, so the contract growing a second service does not
 * silently import stubs nothing asked for.
 */
@Configuration
@ImportGrpcClients(target = "matching", types = QuoteServiceGrpc.QuoteServiceBlockingStub.class)
class GrpcClientConfiguration {

    /**
     * Without {@code @GlobalClientInterceptor} the bean is created and never attached to a channel:
     * spring-grpc collects client interceptors by that annotation, not by type. Nothing is logged
     * and nothing turns red except the test that asserts the id crossed the hop.
     */
    @Bean
    @GlobalClientInterceptor
    RequestIdClientInterceptor requestIdClientInterceptor() {
        return new RequestIdClientInterceptor();
    }
}
