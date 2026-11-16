package com.servora.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OriginsTest {

    @ParameterizedTest
    @CsvSource({
        "https://shop.example.com, https://shop.example.com",
        "HTTPS://Shop.Example.COM, https://shop.example.com",
        "https://shop.example.com/, https://shop.example.com",
        "https://shop.example.com:443, https://shop.example.com",
        "https://shop.example.com:8443, https://shop.example.com:8443",
        "http://localhost:5173, http://localhost:5173",
        "http://localhost:80, http://localhost",
        "'  https://shop.example.com  ', https://shop.example.com",
        "https://XN--bcher-kva.example, https://xn--bcher-kva.example",
    })
    void canonicalisesEquivalentSpellings(String input, String expected) {
        assertThat(Origins.canonical(input)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "shop.example.com",
                "ftp://shop.example.com",
                "javascript:alert(1)",
                "https://*.example.com",
                "https://shop.example.com/path",
                "https://shop.example.com?x=1",
                "https://shop.example.com#frag",
                "https://user:pass@shop.example.com",
                "https://",
                "https://shop.example.com:0",
                "https://shop.example.com:99999",
                "https://shop_example.com",
                // International names must be supplied in their ASCII (punycode) form.
                "https://bücher.example",
                "null",
                "*",
            })
    void rejectsAnythingThatIsNotAPlainOrigin(String input) {
        assertThat(Origins.canonical(input)).isEmpty();
    }

    @Test
    void plainHttpIsAcceptedOnlyForLocalHosts() {
        assertThat(Origins.isSecureOrLocal("https://shop.example.com")).isTrue();
        assertThat(Origins.isSecureOrLocal("http://localhost:5173")).isTrue();
        assertThat(Origins.isSecureOrLocal("http://127.0.0.1")).isTrue();
        assertThat(Origins.isSecureOrLocal("http://demo.localhost:8080")).isTrue();
        assertThat(Origins.isSecureOrLocal("http://shop.example.com")).isFalse();
        assertThat(Origins.isSecureOrLocal("http://localhost.evil.test")).isFalse();
    }

    @Test
    void matchingIsExactAndNeverBySubstringPrefixOrSuffix() {
        List<String> allowed = List.of("https://example.com");

        assertThat(Origins.isAllowed(allowed, "https://example.com")).isTrue();
        assertThat(Origins.isAllowed(allowed, "https://EXAMPLE.com:443")).isTrue();

        assertThat(Origins.isAllowed(allowed, "https://example.com.evil.test")).isFalse();
        assertThat(Origins.isAllowed(allowed, "https://evil-example.com")).isFalse();
        assertThat(Origins.isAllowed(allowed, "https://sub.example.com")).isFalse();
        assertThat(Origins.isAllowed(allowed, "http://example.com")).isFalse();
        assertThat(Origins.isAllowed(allowed, "https://example.com:8443")).isFalse();
        assertThat(Origins.isAllowed(allowed, "https://example.com@evil.test")).isFalse();
        assertThat(Origins.isAllowed(allowed, "null")).isFalse();
        assertThat(Origins.isAllowed(allowed, null)).isFalse();
    }
}
