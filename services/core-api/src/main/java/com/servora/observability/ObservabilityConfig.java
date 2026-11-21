package com.servora.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps traces to what a person would want to follow (ADR-022).
 *
 * <ul>
 *   <li>Health checks and metric scrapes are not traced: they arrive every few seconds and say
 *       nothing about a user's request.
 *   <li>Scheduled runs are not traced as such: the outbox is polled every two seconds, mostly
 *       finding nothing. A delivered event gets its own span in the trace of the request that
 *       wrote it ({@link TraceParents}).
 *   <li>Database statements are traced only inside another operation, so a background query never
 *       starts a trace of its own.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

    @Bean
    ObservationPredicate skipProbesAndScrapes() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext server) {
                String path = server.getCarrier().getRequestURI();
                return !(path.startsWith("/actuator") || path.equals("/livez") || path.equals("/readyz"));
            }
            return true;
        };
    }

    @Bean
    ObservationPredicate skipScheduledPolls() {
        return (name, context) -> !name.equals("tasks.scheduled.execution");
    }

    @Bean
    ObservationPredicate skipOrphanDatabaseStatements() {
        return (name, context) -> !(name.startsWith("jdbc.") && context.getParentObservation() == null);
    }
}
