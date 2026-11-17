package com.servora.common.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Response headers for API responses, shared by both filter chains. The API returns JSON and
 * event streams only, so its responses may load nothing, run nothing and be framed by nothing.
 * Spring Security's defaults already add {@code X-Content-Type-Options: nosniff},
 * {@code X-Frame-Options: DENY}, no-store cache headers, and HSTS on HTTPS requests.
 *
 * <p>The Swagger UI pages need scripts and styles, so they get a policy that allows the API's
 * own origin only.
 */
public final class ApiHeaders {

    public static final String API_POLICY = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    public static final String DOCS_POLICY =
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'";
    public static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    private ApiHeaders() {}

    public static Customizer<HeadersConfigurer<HttpSecurity>> strict() {
        return headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(API_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY));
    }

    public static Customizer<HeadersConfigurer<HttpSecurity>> docs() {
        return headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(DOCS_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY));
    }
}
