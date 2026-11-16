package com.servora.widget;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * Canonical form and matching for web origins ({@code scheme://host[:port]}).
 *
 * <p>Allowed origins are stored canonically and compared by exact string equality. There is no
 * wildcard, prefix or substring matching, so {@code https://example.com.evil.test} and {@code
 * https://evil-example.com} can never match {@code https://example.com}.
 */
public final class Origins {

    private Origins() {}

    /**
     * Returns the canonical origin, or empty if the value is not a plain origin. Lower-cases scheme
     * and host and drops the scheme's default port. International domain names must be supplied in
     * their ASCII (punycode) form, which is also what browsers send in the Origin header.
     */
    public static Optional<String> canonical(String value) {
        if (value == null || value.isBlank() || value.length() > 300) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            return Optional.empty();
        }
        String path = uri.getRawPath();
        boolean hasPath = path != null && !path.isEmpty() && !path.equals("/");
        if (uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || hasPath) {
            return Optional.empty();
        }
        String host;
        try {
            host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (host.isEmpty() || host.startsWith(".") || host.endsWith(".") || !host.matches("[a-z0-9.-]+")) {
            return Optional.empty();
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            return Optional.empty();
        }
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        return Optional.of(scheme + "://" + host + (defaultPort ? "" : ":" + port));
    }

    /** Plain http is acceptable only for local development hosts. */
    public static boolean isSecureOrLocal(String canonicalOrigin) {
        if (canonicalOrigin.startsWith("https://")) {
            return true;
        }
        String host = canonicalOrigin.substring("http://".length()).replaceFirst(":[0-9]+$", "");
        return host.equals("localhost") || host.equals("127.0.0.1") || host.endsWith(".localhost");
    }

    /** Whether a request's Origin header is on the allow-list. Used when widget sessions are issued. */
    public static boolean isAllowed(Collection<String> allowedCanonicalOrigins, String originHeader) {
        return canonical(originHeader).map(allowedCanonicalOrigins::contains).orElse(false);
    }
}
