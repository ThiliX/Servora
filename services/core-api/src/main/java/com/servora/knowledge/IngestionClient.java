package com.servora.knowledge;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.servora.knowledge.KnowledgeConfig.IngestionProperties;
import com.servora.observability.TracedHttp;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Hands a document to the document worker for processing. */
public interface IngestionClient {

    /** Throws if the worker did not accept the job; the outbox then retries. */
    void enqueue(UUID tenantId, UUID documentId);

    @Configuration(proxyBeanMethods = false)
    class Config {

        @Bean
        @ConditionalOnMissingBean(IngestionClient.class)
        IngestionClient ingestionClient(IngestionProperties properties, ObservationRegistry observations) {
            return new HttpIngestionClient(properties, observations);
        }
    }

    /**
     * Calls the worker's internal enqueue endpoint. The request carries a short-lived token signed
     * with a key shared only between the two services; the tenant and document it names come from
     * the server's own outbox row, never from a client.
     */
    final class HttpIngestionClient implements IngestionClient {

        static final String AUDIENCE = "servora-document-worker";
        static final String ISSUER = "servora-core-api";

        private final IngestionProperties properties;
        private final NimbusJwtEncoder encoder;
        private final HttpClient http;
        private final ObservationRegistry observations;

        HttpIngestionClient(IngestionProperties properties, ObservationRegistry observations) {
            byte[] key = Base64.getDecoder().decode(properties.tokenSecret().trim());
            if (key.length < 32) {
                throw new IllegalArgumentException("servora.ingestion.token-secret must decode to at least 32 bytes");
            }
            this.properties = properties;
            this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
            this.observations = observations;
            this.http = HttpClient.newBuilder()
                    // HTTP/1.1 only. The default first tries an h2c upgrade, which the Python services
                    // do not support, and after which uvicorn dropped later requests' trace headers.
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(properties.requestTimeout())
                    .build();
        }

        @Override
        public void enqueue(UUID tenantId, UUID documentId) {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(ISSUER)
                    .audience(java.util.List.of(AUDIENCE))
                    .issuedAt(now)
                    .expiresAt(now.plus(properties.tokenTtl()))
                    .id(UUID.randomUUID().toString())
                    .claim("tenant_id", tenantId.toString())
                    .claim("document_id", documentId.toString())
                    .build();
            String token = encoder.encode(JwtEncoderParameters.from(
                            JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                    .getTokenValue();

            // The body is empty on purpose: the worker trusts only what the signed token says.
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.enqueueUrl()))
                    .timeout(properties.requestTimeout())
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody());
            try {
                HttpResponse<Void> response = TracedHttp.send(
                        observations, "document-worker", http, request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Worker rejected the job with status " + response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while contacting the worker", e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not reach the worker", e);
            }
        }
    }
}
