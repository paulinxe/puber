package com.puber.rider.config;

import java.util.function.Predicate;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Prefixes every controller in a version package with {@code /rider/v1}, so no mapping repeats it.
 * See project-context.md, "The HTTP edge".
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
