package com.puber.rider.config;

import java.util.function.Predicate;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every public path is {@code /<service>/<version>/<resource>}, applied here rather than repeated
 * in every mapping.
 *
 * <p>Not {@code server.servlet.context-path}: that would prefix {@code /actuator} too, and health
 * and metrics are neither versioned nor service-scoped (AD-54 fixes them at {@code /actuator/**}).
 *
 * <p>The predicate names one package, so a v2 is one more line here and no edit to v1. Keep the
 * predicates disjoint -- a class matching two entries resolves by map iteration order.
 */
@Configuration
class ApiVersionConfiguration implements WebMvcConfigurer {

    private static final String CONTROLLERS = "com.puber.rider.controller.";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/rider/v1", inPackage(CONTROLLERS + "v1"));
    }

    private static Predicate<Class<?>> inPackage(String name) {
        return type -> type.getPackageName().equals(name);
    }
}
