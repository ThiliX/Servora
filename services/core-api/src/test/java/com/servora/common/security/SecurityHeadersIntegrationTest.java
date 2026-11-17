package com.servora.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** M7: security response headers on authenticated, public, error and documentation responses. */
class SecurityHeadersIntegrationTest extends IntegrationTestBase {

    @Test
    void apiResponsesForbidLoadingFramingAndSniffing() {
        Session user = newUser("headers");
        Response response = get("/api/v1/workspaces", user);
        assertThat(response.status()).isEqualTo(200);
        assertStrict(response);
        assertThat(response.header("Cache-Control")).contains("no-store");
    }

    @Test
    void errorsAndUnauthenticatedResponsesCarryTheSameHeaders() {
        Response unauthenticated = send("GET", "/api/v1/workspaces", null, null);
        assertThat(unauthenticated.status()).isEqualTo(401);
        assertStrict(unauthenticated);
    }

    @Test
    void publicWidgetResponsesCarryTheSameHeaders() {
        Response response = send("GET", "/api/v1/public/widgets/pk_unknown/config", null, null,
                "Origin", "https://shop.example.test");
        assertThat(response.status()).isEqualTo(404);
        assertStrict(response);
    }

    @Test
    void theApiDocumentationGetsAPolicyThatAllowsOnlyItsOwnAssets() {
        Response docs = send("GET", "/v3/api-docs", null, null);
        assertThat(docs.status()).isEqualTo(200);
        assertThat(docs.header("Content-Security-Policy")).isEqualTo(ApiHeaders.DOCS_POLICY);
        assertThat(docs.header("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    private static void assertStrict(Response response) {
        assertThat(response.header("Content-Security-Policy")).isEqualTo(ApiHeaders.API_POLICY);
        assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.header("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.header("Permissions-Policy")).isEqualTo(ApiHeaders.PERMISSIONS_POLICY);
    }
}
