package com.servora.conversation;

import com.servora.common.web.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Security, CORS and threading for the public widget endpoints under {@code /api/v1/public}. */
@Configuration
public class PublicWidgetConfig {

    static final String PUBLIC_PATH = "/api/v1/public/";

    /**
     * A separate filter chain with no dashboard token processing at all. Widget endpoints check
     * their own key, origin and session token, so a dashboard token is meaningless here and a widget
     * token is never seen by the dashboard's token check.
     */
    @Bean
    @Order(1)
    SecurityFilterChain publicWidgetFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(PUBLIC_PATH + "**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(a -> {})
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /** Virtual threads: each reply mostly waits on the AI service. */
    @Bean(destroyMethod = "close")
    ExecutorService widgetReplyExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * CORS for the public endpoints. The widget key endpoints allow exactly the widget's own allowed
     * origins; the conversation endpoints allow only the origin that serves the widget iframe.
     */
    @Component
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    static class PublicCorsFilter extends OncePerRequestFilter {

        private static final String WIDGETS = PUBLIC_PATH + "widgets/";
        private static final String CONVERSATION = PUBLIC_PATH + "conversation";

        private final PublicWidgetService widgets;
        private final WidgetProperties properties;

        PublicCorsFilter(PublicWidgetService widgets, WidgetProperties properties) {
            this.widgets = widgets;
            this.properties = properties;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith(PUBLIC_PATH);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String path = request.getRequestURI();
            String origin = request.getHeader("Origin");
            boolean preflight = HttpMethod.OPTIONS.matches(request.getMethod())
                    && request.getHeader("Access-Control-Request-Method") != null;
            response.addHeader("Vary", "Origin");

            boolean allowed = false;
            String allowedHeaders = "Content-Type";
            if (origin != null && path.startsWith(WIDGETS)) {
                String rest = path.substring(WIDGETS.length());
                int slash = rest.indexOf('/');
                String publicKey = slash < 0 ? rest : rest.substring(0, slash);
                allowed = widgets.isOriginAllowed(publicKey, origin);
            } else if (origin != null && path.startsWith(CONVERSATION)) {
                allowed = origin.equals(properties.appOrigin());
                allowedHeaders = "Authorization, Content-Type, Idempotency-Key";
            }

            if (allowed) {
                response.setHeader("Access-Control-Allow-Origin", origin);
            }
            if (preflight) {
                if (allowed) {
                    response.setHeader("Access-Control-Allow-Methods", "GET, POST");
                    response.setHeader("Access-Control-Allow-Headers", allowedHeaders);
                    response.setHeader("Access-Control-Max-Age", "600");
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                } else {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":{\"code\":\"ORIGIN_NOT_ALLOWED\",\"message\":"
                            + "\"This website is not allowed to use this widget.\",\"trace_id\":\""
                            + TraceIds.current() + "\"}}");
                }
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
