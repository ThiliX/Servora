package com.servora.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.support.IntegrationTestBase;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** The public demo mode, switched on: the listed routes are refused, the rest keep working. */
@TestPropertySource(properties = "servora.demo.enabled=true")
class DemoModeIntegrationTest extends IntegrationTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyBlockedRouteExists() {
        // A renamed route would otherwise silently stop being blocked.
        Set<String> declared = new HashSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getMethodsCondition()
                .getMethods()
                .forEach(method -> info.getPatternValues().forEach(pattern -> declared.add(method + " " + pattern))));
        assertThat(declared).containsAll(DemoMode.BLOCKED);
    }

    @Test
    void registrationIsRefused() {
        Response response = register(uniqueEmail("demo"), PASSWORD);
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.errorCode()).isEqualTo("DEMO_READ_ONLY");
    }

    @Test
    void signingInStillWorks() {
        Response response = login(uniqueEmail("nobody"), PASSWORD);
        assertThat(response.status()).isEqualTo(401);
    }
}
