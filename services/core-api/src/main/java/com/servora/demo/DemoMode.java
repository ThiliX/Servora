package com.servora.demo;

import com.servora.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Public demo mode (SERVORA_DEMO_MODE=true). Visitors share the demo accounts, so actions that would
 * spoil the demo for the next visitor are refused: creating accounts or workspaces, changing a
 * workspace's name, its assistant's messages or its widget, managing the team, and deleting
 * documents or conversations. Everything else (the inbox, the playground, uploads) works as usual.
 * Off by default; never switch it on for a deployment with real customers.
 */
@Configuration
@ConditionalOnBooleanProperty("servora.demo.enabled")
public class DemoMode implements WebMvcConfigurer {

    /** Method and route pattern, exactly as declared on the controllers. */
    static final Set<String> BLOCKED = Set.of(
            "POST /api/v1/auth/register",
            "POST /api/v1/workspaces",
            "PATCH /api/v1/workspaces/{workspaceId}",
            "PUT /api/v1/workspaces/{workspaceId}/assistant",
            "PATCH /api/v1/workspaces/{workspaceId}/widget",
            "POST /api/v1/workspaces/{workspaceId}/widget/rotate-key",
            "PUT /api/v1/workspaces/{workspaceId}/widget/allowed-origins",
            "PATCH /api/v1/workspaces/{workspaceId}/members/{memberId}",
            "POST /api/v1/workspaces/{workspaceId}/invitations",
            "DELETE /api/v1/workspaces/{workspaceId}/invitations/{invitationId}",
            "POST /api/v1/invitations/accept",
            "PUT /api/v1/workspaces/{workspaceId}/retention",
            "DELETE /api/v1/workspaces/{workspaceId}/conversations/{conversationId}",
            "POST /api/v1/workspaces/{workspaceId}/knowledge/documents/{documentId}/replace",
            "POST /api/v1/workspaces/{workspaceId}/knowledge/documents/{documentId}/archive",
            "DELETE /api/v1/workspaces/{workspaceId}/knowledge/documents/{documentId}");

    static final String CODE = "DEMO_READ_ONLY";
    static final String MESSAGE = "This action is turned off in the public demo.";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Guard());
    }

    static final class Guard implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern != null && BLOCKED.contains(request.getMethod() + " " + pattern)) {
                throw new ApiException(HttpStatus.FORBIDDEN, CODE, MESSAGE);
            }
            return true;
        }
    }
}
