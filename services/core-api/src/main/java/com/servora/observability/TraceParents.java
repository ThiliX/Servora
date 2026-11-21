package com.servora.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Carries a trace across the outbox: the request that writes an event stores its W3C
 * {@code traceparent}, and the dispatcher continues that trace when it delivers the event, possibly
 * seconds later and on another instance.
 */
@Component
public class TraceParents {

    private static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    private final ObjectProvider<Tracer> tracer;
    private final ObservationRegistry registry;

    public TraceParents(ObjectProvider<Tracer> tracer, ObservationRegistry registry) {
        this.tracer = tracer;
        this.registry = registry;
    }

    /** The current span as a {@code traceparent} value, or null when there is no current span. */
    public String current() {
        Tracer active = tracer.getIfAvailable();
        Span span = active == null ? null : active.currentSpan();
        if (span == null || span.isNoop()) {
            return null;
        }
        TraceContext context = span.context();
        String value = "00-" + context.traceId() + "-" + context.spanId() + "-"
                + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
        return FORMAT.matcher(value).matches() ? value : null;
    }

    /**
     * An observation for processing a queued event, continuing the trace in {@code traceParent}
     * when there is one. The caller starts and stops it.
     */
    public Observation consumer(String name, String contextualName, String traceParent) {
        ReceiverContext<Map<String, String>> context = new ReceiverContext<>((carrier, key) -> carrier.get(key));
        context.setCarrier(traceParent != null && FORMAT.matcher(traceParent).matches()
                ? Map.of("traceparent", traceParent)
                : Map.of());
        return Observation.createNotStarted(name, () -> context, registry).contextualName(contextualName);
    }
}
