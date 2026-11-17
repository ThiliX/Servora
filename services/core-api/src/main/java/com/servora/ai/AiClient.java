package com.servora.ai;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.servora.assistant.AssistantService.AssistantView;
import com.servora.knowledge.KnowledgeConfig.IngestionProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

/** Asks the AI service to answer a question from one tenant's knowledge base. */
public interface AiClient {

    /** Throws {@link AiUnavailableException} if the service cannot be reached or replies with an error. */
    AiAnswer answer(UUID tenantId, AssistantView assistant, String question);

    class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @ConfigurationProperties("servora.ai")
    @Validated
    record AiProperties(@NotBlank String answerUrl, @NotNull Duration requestTimeout, @Min(1) int testsPerHour) {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    class Config {

        @Bean
        @ConditionalOnMissingBean(AiClient.class)
        AiClient aiClient(AiProperties properties, IngestionProperties tokens, ObjectMapper json) {
            return new HttpAiClient(properties, tokens, json);
        }
    }

    /**
     * Calls the AI service over HTTP. The tenant travels only inside a short-lived token signed
     * with the key the internal services share; the AI service reads it from nowhere else.
     */
    final class HttpAiClient implements AiClient {

        static final String AUDIENCE = "servora-ai-service";
        static final String ISSUER = "servora-core-api";

        private final AiProperties properties;
        private final Duration tokenTtl;
        private final NimbusJwtEncoder encoder;
        private final ObjectMapper json;
        private final HttpClient http;

        HttpAiClient(AiProperties properties, IngestionProperties tokens, ObjectMapper json) {
            byte[] key = Base64.getDecoder().decode(tokens.tokenSecret().trim());
            if (key.length < 32) {
                throw new IllegalArgumentException("servora.ingestion.token-secret must decode to at least 32 bytes");
            }
            this.properties = properties;
            this.tokenTtl = tokens.tokenTtl();
            this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
            this.json = json;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }

        @Override
        public AiAnswer answer(UUID tenantId, AssistantView assistant, String question) {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(ISSUER)
                    .audience(List.of(AUDIENCE))
                    .issuedAt(now)
                    .expiresAt(now.plus(tokenTtl))
                    .id(UUID.randomUUID().toString())
                    .claim("tenant_id", tenantId.toString())
                    .build();
            String token = encoder.encode(JwtEncoderParameters.from(
                            JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                    .getTokenValue();
            try {
                String body = json.writeValueAsString(Map.of(
                        "question",
                        question,
                        "assistant",
                        Map.of(
                                "name", assistant.name(),
                                "tone", assistant.tone().name(),
                                "language", assistant.language(),
                                "fallback_message", assistant.fallbackMessage(),
                                "confidence_policy", assistant.confidencePolicy().name())));
                HttpRequest request = HttpRequest.newBuilder(URI.create(properties.answerUrl()))
                        .timeout(properties.requestTimeout())
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new AiUnavailableException("AI service replied with status " + response.statusCode(), null);
                }
                return json.readValue(response.body(), AiAnswer.class);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("Interrupted while contacting the AI service", e);
            } catch (java.io.IOException | tools.jackson.core.JacksonException e) {
                throw new AiUnavailableException("Could not get an answer from the AI service", e);
            }
        }
    }
}
