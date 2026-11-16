package com.servora.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox. Safe to run on several instances: each event is claimed with a row lock. */
@Component
@ConditionalOnProperty(name = "servora.outbox.enabled", havingValue = "true")
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_EVENTS_PER_RUN = 50;

    private final OutboxProcessor processor;

    public OutboxDispatcher(OutboxProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${servora.outbox.poll-interval:PT2S}")
    public void dispatch() {
        try {
            for (int i = 0; i < MAX_EVENTS_PER_RUN && processor.processNext(); i++) {
                // Keep draining until nothing is due.
            }
        } catch (RuntimeException e) {
            log.error("Outbox dispatch run failed: {}", e.getClass().getSimpleName());
        }
    }
}
