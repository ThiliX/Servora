package com.servora.common.web;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request one ID, exposed in the {@code X-Request-Id} response header, error bodies and
 * log lines.
 *
 * <p>The ID is the request's trace ID (ADR-022), so a support report quoting it leads straight to
 * the trace and to every log line of every service that handled the request. This filter runs just
 * after the one that starts the request's span. If tracing is switched off, it falls back to a
 * client-supplied ID in the expected format, or a random one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private final ObjectProvider<Tracer> tracer;

    public CorrelationIdFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = currentTraceId();
        if (traceId == null) {
            String incoming = request.getHeader(TraceIds.HEADER);
            traceId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        }
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    private String currentTraceId() {
        Tracer active = tracer.getIfAvailable();
        Span span = active == null ? null : active.currentSpan();
        if (span == null || span.isNoop()) {
            return null;
        }
        String id = span.context().traceId();
        return id != null && SAFE_ID.matcher(id).matches() ? id : null;
    }
}
