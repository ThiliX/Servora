package com.servora.conversation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Public widget settings: session tokens, the widget app's origin, and public rate limits. */
@ConfigurationProperties("servora.widget")
@Validated
public record WidgetProperties(
        // Base64 HMAC key for widget session tokens, at least 32 bytes. Must differ from the user
        // token key, so a widget token can never be accepted as a dashboard token.
        @NotBlank String tokenSecret,
        @NotNull Duration tokenTtl,
        // Origin that serves the widget iframe. Conversation endpoints accept browser calls only from it.
        @NotBlank String appOrigin,
        @NotNull Duration replyTimeout,
        @Min(1) int configPerIpPerMinute,
        @Min(1) int sessionsPerIpPerHour,
        @Min(1) int messagesPerConversationPerMinute,
        @Min(1) int messagesPerIpPerMinute,
        @Min(1) int aiRepliesPerTenantPerDay) {}
