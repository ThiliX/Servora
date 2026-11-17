package com.servora.privacy;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Settings for the retention job and the workspace data export. */
public final class DataProperties {

    private DataProperties() {}

    @ConfigurationProperties("servora.retention")
    @Validated
    public record Retention(boolean enabled, @NotNull Duration interval, @Min(1) int batchSize) {}

    @ConfigurationProperties("servora.export")
    @Validated
    public record Export(@Min(1) int perWorkspacePerHour, @Min(1) int maxConversations) {}
}
