package com.servora;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;

/**
 * Applies the database migrations and exits. Used by the production profile's one-shot migrations
 * container, which runs this class from the API image with the database owner's credentials. The
 * API itself then runs with Flyway disabled and only the runtime role's credentials (ADR-008).
 *
 * <p>Settings mirror {@code spring.flyway} in application.yml: the same migrations on the classpath
 * and the same placeholders, read from the same environment variables.
 *
 * <p>Run with: {@code java -Dloader.main=com.servora.Migrate -cp app.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}
 */
public final class Migrate {

    private Migrate() {}

    public static void main(String[] args) {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        String url = "jdbc:postgresql://%s:%s/%s".formatted(
                env("SERVORA_POSTGRES_HOST", "localhost"),
                env("SERVORA_POSTGRES_PORT", "5432"),
                env("SERVORA_POSTGRES_DB", "servora"));
        Flyway.configure()
                .dataSource(url, env("SERVORA_POSTGRES_OWNER_USER", "servora_owner"), required("SERVORA_POSTGRES_OWNER_PASSWORD"))
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "app_role", env("SERVORA_POSTGRES_APP_USER", "servora_app"),
                        "worker_role", env("SERVORA_POSTGRES_WORKER_USER", "servora_worker"),
                        "ai_role", env("SERVORA_POSTGRES_AI_USER", "servora_ai")))
                .connectRetries(10)
                .load()
                .migrate();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
