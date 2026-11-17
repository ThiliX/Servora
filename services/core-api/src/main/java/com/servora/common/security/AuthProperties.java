package com.servora.common.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Authentication settings, validated at startup so a misconfigured service fails to boot. */
@ConfigurationProperties("servora.auth")
@Validated
public record AuthProperties(
        @NotBlank String issuer,
        @NotBlank String jwtSecret,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration verificationTokenTtl,
        @NotNull Duration invitationTtl,
        boolean refreshCookieSecure,
        // Failed sign-ins allowed within the window: for one account from one address, for one
        // account from all addresses, and from one address across all accounts.
        @Min(1) int loginMaxFailures,
        @Min(1) int loginAccountMaxFailures,
        @Min(1) int loginAddressMaxFailures,
        @NotNull Duration loginFailureWindow,
        @Min(1) int registrationsPerAddressPerHour,
        @Min(1) int registrationsPerEmailPerHour) {

    private static final int MIN_KEY_BYTES = 32;

    public AuthProperties {
        if (jwtSecret != null && !jwtSecret.isBlank() && decode(jwtSecret).length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("servora.auth.jwt-secret must decode to at least 32 bytes");
        }
    }

    public byte[] jwtSecretBytes() {
        return decode(jwtSecret);
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("servora.auth.jwt-secret must be Base64-encoded");
        }
    }
}
