package com.servora.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.servora.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class DemoModeTest {

    private final DemoMode.Guard guard = new DemoMode.Guard();

    @Test
    void refusesABlockedRoute() {
        MockHttpServletRequest request = request("PUT", "/api/v1/workspaces/{workspaceId}/assistant");
        assertThatThrownBy(() -> guard.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("DEMO_READ_ONLY");
                    assertThat(ex.status().value()).isEqualTo(403);
                });
    }

    @Test
    void allowsTheSameRouteWithAnotherMethodAndUnlistedRoutes() throws Exception {
        assertThat(guard.preHandle(
                        request("GET", "/api/v1/workspaces/{workspaceId}/assistant"),
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();
        assertThat(guard.preHandle(
                        request("POST", "/api/v1/workspaces/{workspaceId}/knowledge/documents"),
                        new MockHttpServletResponse(),
                        new Object()))
                .isTrue();
        assertThat(guard.preHandle(request("POST", null), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    private static MockHttpServletRequest request(String method, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/");
        if (pattern != null) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        }
        return request;
    }
}
