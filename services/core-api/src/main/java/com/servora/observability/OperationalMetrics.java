package com.servora.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Platform-wide gauges read from the database: documents by status, the outbox backlog and its
 * age, the oldest document still processing, and open tickets by status.
 *
 * <p>The numbers come from {@code operational_metrics()}, which returns aggregates only; no metric
 * carries a tenant. They are refreshed on a schedule rather than at scrape time, so a scrape never
 * waits on the database.
 */
@Component
@ConditionalOnProperty(name = "servora.metrics.operational.enabled", havingValue = "true", matchIfMissing = true)
public class OperationalMetrics {

    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);

    private final JdbcClient jdbc;
    private final MultiGauge documents;
    private final MultiGauge outboxEvents;
    private final MultiGauge tickets;
    private final AtomicReference<Double> outboxOldestAge = new AtomicReference<>(0.0);
    private final AtomicReference<Double> processingOldestAge = new AtomicReference<>(0.0);

    public OperationalMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.documents = MultiGauge.builder("servora.documents")
                .description("Documents by processing status, all workspaces")
                .register(registry);
        this.outboxEvents = MultiGauge.builder("servora.outbox.events")
                .description("Outbox events waiting for delivery or given up on")
                .register(registry);
        this.tickets = MultiGauge.builder("servora.tickets.open")
                .description("Tickets not yet closed, by status")
                .register(registry);
        Gauge.builder("servora.outbox.oldest.pending.age", outboxOldestAge, AtomicReference::get)
                .description("Age of the oldest undelivered outbox event")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("servora.documents.processing.oldest.age", processingOldestAge, AtomicReference::get)
                .description("Time the oldest document has been processing")
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(
            initialDelayString = "${servora.metrics.operational.initial-delay:PT5S}",
            fixedDelayString = "${servora.metrics.operational.interval:PT30S}")
    public void refresh() {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.sql("SELECT metric, label, value FROM operational_metrics()").query().listOfRows();
        } catch (DataAccessException e) {
            log.warn("Operational metrics unavailable: {}", e.getClass().getSimpleName());
            return;
        }
        List<MultiGauge.Row<?>> documentRows = new ArrayList<>();
        List<MultiGauge.Row<?>> outboxRows = new ArrayList<>();
        List<MultiGauge.Row<?>> ticketRows = new ArrayList<>();
        double outboxAge = 0;
        double processingAge = 0;
        for (Map<String, Object> row : rows) {
            String metric = (String) row.get("metric");
            String label = (String) row.get("label");
            double value = ((Number) row.get("value")).doubleValue();
            switch (metric) {
                case "documents" -> documentRows.add(MultiGauge.Row.of(Tags.of("status", label), value));
                case "outbox_events" -> outboxRows.add(MultiGauge.Row.of(Tags.of("status", label), value));
                case "tickets" -> ticketRows.add(MultiGauge.Row.of(Tags.of("status", label), value));
                case "outbox_oldest_pending_age_seconds" -> outboxAge = value;
                case "documents_processing_oldest_age_seconds" -> processingAge = value;
                default -> {
                    // A newer database may return more metrics than this version knows.
                }
            }
        }
        // overwrite=true: a status that no longer has rows disappears instead of keeping its last value.
        documents.register(documentRows, true);
        outboxEvents.register(outboxRows, true);
        tickets.register(ticketRows, true);
        outboxOldestAge.set(outboxAge);
        processingOldestAge.set(processingAge);
    }
}
