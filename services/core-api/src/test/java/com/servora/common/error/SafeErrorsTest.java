package com.servora.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeErrorsTest {

    @Test
    void keepsTypesAndLocationButNeverMessages() {
        Exception root = new IllegalStateException("Key (email)=(someone@example.test) already exists");
        Exception error = new RuntimeException("wrapped: customer said 'my card number is 4111'", root);

        String described = SafeErrors.describe(error);

        assertThat(described)
                .contains("java.lang.RuntimeException <- java.lang.IllegalStateException")
                .contains("at com.servora.common.error.SafeErrorsTest.keepsTypesAndLocationButNeverMessages")
                .doesNotContain("someone@example.test")
                .doesNotContain("4111");
    }
}
