package com.thirdstone.commerce.platform.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Makes every request findable afterwards.
 *
 * <ul>
 *   <li>{@code X-Request-Id}: the id the edge assigned (or a fresh one), in the log
 *       context of every line this request writes and echoed on the response.</li>
 *   <li>{@code X-Trace-Id}: the distributed trace this request belongs to, on the
 *       response — paste it into Grafana (Tempo) to see every service and Kafka hop
 *       the request caused, or into Loki to see all their log lines.</li>
 * </ul>
 */
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "X-Request-Id";
    public static final String TRACE_ID = "X-Trace-Id";
    /** Anything else is replaced: the value lands in logs, so no injection material. */
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private final ObjectProvider<Tracer> tracer;

    public RequestContextFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String inbound = request.getHeader(REQUEST_ID);
        String id = inbound != null && SAFE.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        MDC.put("requestId", id);
        response.setHeader(REQUEST_ID, id);
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span != null) {
            response.setHeader(TRACE_ID, span.context().traceId());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("userId");
        }
    }
}
