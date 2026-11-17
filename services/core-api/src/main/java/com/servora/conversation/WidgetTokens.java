package com.servora.conversation;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies widget session tokens.
 *
 * <p>A token is bound to one tenant, widget, customer and conversation, and carries a single
 * scope. It is signed with its own key, separate from the dashboard's, so it can never pass the
 * dashboard's token check, and the public endpoints read the conversation only from the token.
 */
@Component
@EnableConfigurationProperties(WidgetProperties.class)
public class WidgetTokens {

    public static final String AUDIENCE = "servora-widget";
    public static final String ISSUER = "servora-core-api";
    static final String SCOPE = "widget:conversation";

    public record WidgetSession(UUID tenantId, UUID widgetId, UUID customerId, UUID conversationId) {}

    public record IssuedToken(String token, Instant expiresAt) {}

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    public WidgetTokens(WidgetProperties properties, Clock clock) {
        byte[] keyBytes = Base64.getDecoder().decode(properties.tokenSecret().trim());
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("servora.widget.token-secret must decode to at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(5));
        timestamps.setClock(clock);
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(ISSUER),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(AUDIENCE)),
                new JwtClaimValidator<String>("scope", SCOPE::equals)));
        this.ttl = properties.tokenTtl();
        this.clock = clock;
    }

    public IssuedToken issue(WidgetSession session) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .subject(session.customerId().toString())
                .claim("scope", SCOPE)
                .claim("tenant_id", session.tenantId().toString())
                .claim("widget_id", session.widgetId().toString())
                .claim("conversation_id", session.conversationId().toString())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /** The session a valid token is bound to; empty for a missing, tampered, expired or foreign token. */
    public Optional<WidgetSession> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder.decode(token);
            return Optional.of(new WidgetSession(
                    UUID.fromString(jwt.getClaimAsString("tenant_id")),
                    UUID.fromString(jwt.getClaimAsString("widget_id")),
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("conversation_id"))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
