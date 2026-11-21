package com.servora.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** FR-001 and FR-002: registration with a verified email, sign-in, session refresh and sign-out. */
class AuthFlowIntegrationTest extends IntegrationTestBase {

    @Test
    void registrationRequiresEmailVerificationBeforeSignIn() {
        String email = uniqueEmail("signup");
        assertThat(register(email, PASSWORD).status()).isEqualTo(202);

        Response beforeVerification = login(email, PASSWORD);
        assertThat(beforeVerification.status()).isEqualTo(403);
        assertThat(beforeVerification.errorCode()).isEqualTo("EMAIL_NOT_VERIFIED");

        String token = mail.verificationToken(email);
        assertThat(post("/api/v1/auth/verify-email", "{\"token\": \"" + token + "\"}", null).status())
                .isEqualTo(204);

        Session session = signIn(email);
        Response me = get("/api/v1/auth/me", session);
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.<String>json("$.email")).isEqualTo(email);
        assertThat(me.body()).doesNotContain("password");

        // A verification token works once.
        Response reused = post("/api/v1/auth/verify-email", "{\"token\": \"" + token + "\"}", null);
        assertThat(reused.status()).isEqualTo(400);
        assertThat(reused.errorCode()).isEqualTo("INVALID_VERIFICATION_TOKEN");
    }

    @Test
    void emailIsNormalizedSoCaseAndWhitespaceDoNotCreateSecondAccount() {
        Session session = newUser("case");

        assertThat(login(session.email().toUpperCase(), PASSWORD).status()).isEqualTo(200);
        assertThat(register("  " + session.email().toUpperCase(), PASSWORD).status())
                .isIn(202, 400);
        assertThat(login(session.email(), PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void registeringAnExistingEmailLooksIdenticalAndChangesNothing() {
        Session existing = newUser("dupe");
        mail.forgetVerification(existing.email());

        Response second = register(existing.email(), "a-different-password-123");

        assertThat(second.status()).isEqualTo(202);
        assertThat(mail.hasVerificationFor(existing.email())).isFalse();
        assertThat(login(existing.email(), "a-different-password-123").status()).isEqualTo(401);
        assertThat(login(existing.email(), PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameError() {
        Session user = newUser("creds");

        Response wrongPassword = login(user.email(), "not-the-right-password");
        Response unknownEmail = login(uniqueEmail("nobody"), PASSWORD);

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail.status()).isEqualTo(401);
        assertThat(wrongPassword.errorCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownEmail.errorCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrongPassword.<String>json("$.error.message"))
                .isEqualTo(unknownEmail.<String>json("$.error.message"));
    }

    @Test
    void repeatedFailedSignInsAreRateLimited() {
        Session user = newUser("limited");
        for (int i = 0; i < 5; i++) {
            assertThat(login(user.email(), "wrong-password-" + i).status()).isEqualTo(401);
        }

        Response blocked = login(user.email(), PASSWORD);

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.errorCode()).isEqualTo("TOO_MANY_ATTEMPTS");
    }

    @Test
    void signUpsToOneMailboxAreLimitedTheSameWayWhetherOrNotItIsRegistered() {
        String fresh = uniqueEmail("signup-limit");
        String existing = newUser("signup-existing").email();
        for (int i = 0; i < 2; i++) {
            assertThat(register(fresh, PASSWORD).status()).isEqualTo(202);
            assertThat(register(existing, PASSWORD).status()).isEqualTo(202);
        }
        // newUser already registered the existing address once.
        assertThat(register(fresh, PASSWORD).status()).isEqualTo(202);
        Response freshBlocked = register(fresh, PASSWORD);
        Response existingBlocked = register(existing, PASSWORD);

        assertThat(freshBlocked.status()).isEqualTo(429);
        assertThat(existingBlocked.status()).isEqualTo(429);
        assertThat(freshBlocked.body()).isEqualTo(existingBlocked.body().replace(existingBlocked.<String>json("$.error.trace_id"),
                freshBlocked.<String>json("$.error.trace_id")));
    }

    @Test
    void aForgedForwardedForHeaderDoesNotEscapeTheLimit() {
        Session user = newUser("forged-address");
        for (int i = 0; i < 5; i++) {
            Response failed = send("POST", "/api/v1/auth/login",
                    "{\"email\": \"" + user.email() + "\", \"password\": \"wrong-" + i + "\"}", null,
                    "X-Forwarded-For", "203.0.113." + i);
            assertThat(failed.status()).isEqualTo(401);
        }

        Response blocked = send("POST", "/api/v1/auth/login",
                "{\"email\": \"" + user.email() + "\", \"password\": \"" + PASSWORD + "\"}", null,
                "X-Forwarded-For", "198.51.100.7");

        assertThat(blocked.status()).isEqualTo(429);
    }

    @Test
    void refreshCookieIsHttpOnlySecureAndScopedToTheAuthPath() {
        String email = newUser("cookie").email();

        String cookie = login(email, PASSWORD).header("Set-Cookie");

        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        assertThat(cookie).contains("Path=/api/v1/auth");
    }

    @Test
    void refreshRotatesTheTokenAndIssuesAWorkingAccessToken() {
        Session session = newUser("rotate");

        Response refreshed = refresh(session.refreshToken());

        assertThat(refreshed.status()).isEqualTo(200);
        String newRefreshToken = refreshTokenFrom(refreshed);
        assertThat(newRefreshToken).isNotEqualTo(session.refreshToken());
        String newAccessToken = refreshed.json("$.access_token");
        assertThat(send("GET", "/api/v1/auth/me", null, newAccessToken).status()).isEqualTo(200);
    }

    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeSession() {
        Session session = newUser("reuse");
        String rotated = refreshTokenFrom(refresh(session.refreshToken()));

        // The original token was already used once: presenting it again signals theft.
        Response replay = refresh(session.refreshToken());
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.errorCode()).isEqualTo("INVALID_SESSION");

        // The legitimate holder's newer token is revoked too.
        assertThat(refresh(rotated).status()).isEqualTo(401);
    }

    @Test
    void refreshAndLogoutRejectRequestsWithoutTheClientHeader() {
        Session session = newUser("csrf");

        Response refreshWithoutHeader =
                send("POST", "/api/v1/auth/refresh", null, null, "Cookie", "servora_refresh=" + session.refreshToken());
        Response logoutWithoutHeader =
                send("POST", "/api/v1/auth/logout", null, null, "Cookie", "servora_refresh=" + session.refreshToken());

        assertThat(refreshWithoutHeader.status()).isEqualTo(403);
        assertThat(refreshWithoutHeader.errorCode()).isEqualTo("CLIENT_HEADER_REQUIRED");
        assertThat(logoutWithoutHeader.status()).isEqualTo(403);
        // The rejected requests did not consume the token.
        assertThat(refresh(session.refreshToken()).status()).isEqualTo(200);
    }

    @Test
    void logoutRevokesTheRefreshToken() {
        Session session = newUser("logout");

        Response logout = send(
                "POST",
                "/api/v1/auth/logout",
                null,
                null,
                "Cookie",
                "servora_refresh=" + session.refreshToken(),
                "X-Servora-Client",
                "web");

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.header("Set-Cookie")).contains("Max-Age=0");
        assertThat(refresh(session.refreshToken()).status()).isEqualTo(401);
    }

    @Test
    void protectedEndpointsRejectMissingAndForgedTokensWithTheErrorEnvelope() {
        Response missing = get("/api/v1/workspaces", null);
        Response forged = send("GET", "/api/v1/workspaces", null, "not.a.real-token");

        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.errorCode()).isEqualTo("UNAUTHENTICATED");
        assertThat(missing.<String>json("$.error.trace_id")).isEqualTo(missing.header("X-Request-Id"));
        assertThat(forged.status()).isEqualTo(401);
    }

    @Test
    void invalidInputIsRejectedWithFieldDetails() {
        Response response = register(uniqueEmail("short"), "short");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.<java.util.List<String>>json("$.error.details[*].field")).contains("password");
    }

    @Test
    void healthAndOpenApiEndpointsArePublic() {
        Response health = get("/livez", null);
        Response readiness = get("/readyz", null);
        Response openApi = get("/v3/api-docs", null);

        assertThat(health.status()).isEqualTo(200);
        assertThat(health.<String>json("$.status")).isEqualTo("UP");
        assertThat(readiness.status()).isEqualTo(200);
        assertThat(openApi.status()).isEqualTo(200);
        assertThat(openApi.body()).contains("/api/v1/workspaces/{workspaceId}/members");
    }
}
