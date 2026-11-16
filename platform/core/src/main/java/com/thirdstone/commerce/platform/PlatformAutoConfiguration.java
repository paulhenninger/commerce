package com.thirdstone.commerce.platform;

import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.ops.TestToolingGuard;
import com.thirdstone.commerce.platform.web.ApiErrorHandler;
import com.thirdstone.commerce.platform.web.CallerArgumentResolver;
import com.thirdstone.commerce.platform.web.RequestContextFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Wires what every Commerce service shares. Picked up automatically by any
 * service with platform:core on its classpath.
 */
@AutoConfiguration
@Import(ApiErrorHandler.class)
public class PlatformAutoConfiguration {

    @Bean
    TestToolingGuard testToolingGuard(Environment env) {
        return new TestToolingGuard(env);
    }

    @Bean
    EventCodec eventCodec(JsonMapper mapper) {
        return new EventCodec(mapper);
    }

    @Bean
    FilterRegistrationBean<RequestContextFilter> requestContextFilter(ObjectProvider<Tracer> tracer) {
        var registration = new FilterRegistrationBean<>(new RequestContextFilter(tracer));
        // After the tracing filter (which opens the span), before everything else.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    WebMvcConfigurer callerResolverConfigurer(Environment env) {
        String secret = env.getProperty("commerce.edge.shared-secret", "");
        // A service that takes callers from the edge must not start without the
        // secret that proves a request came through it. Services with no
        // edge-facing API (the dev simulators) opt out explicitly.
        if (secret.isBlank() && env.getProperty("commerce.edge.required", Boolean.class, true)) {
            throw new IllegalStateException("commerce.edge.shared-secret (EDGE_SHARED_SECRET) is not set: "
                    + "without it this service could not tell the edge from any other client");
        }
        if (!secret.isBlank() && secret.length() < 32) {
            throw new IllegalStateException("commerce.edge.shared-secret must be at least 32 characters");
        }
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(new CallerArgumentResolver(secret));
            }
        };
    }
}
