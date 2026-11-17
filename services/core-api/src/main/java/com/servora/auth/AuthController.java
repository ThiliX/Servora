package com.servora.auth;

import com.servora.auth.AuthService.IssuedSession;
import com.servora.auth.AuthService.UserView;
import com.servora.common.error.ApiException;
import com.servora.common.security.AuthProperties;
import com.servora.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "servora_refresh";
    static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    /** Browsers cannot add this header to a cross-site request without a CORS preflight. */
    static final String CLIENT_HEADER = "X-Servora-Client";

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 128) String password,
            @NotBlank @Size(max = 100) String displayName) {}

    public record VerifyEmailRequest(@NotBlank @Size(max = 200) String token) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

    public record MessageResponse(String message) {}

    public record SessionResponse(String accessToken, String tokenType, long expiresIn, UserView user) {}

    private final AuthService auth;
    private final AuthProperties properties;

    public AuthController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @PostMapping("/register")
    ResponseEntity<MessageResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        auth.register(request.email(), request.password(), request.displayName(), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new MessageResponse("If the address can be registered, a verification email has been sent."));
    }

    @PostMapping("/verify-email")
    ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        auth.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return sessionResponse(auth.login(request.email(), request.password(), http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    ResponseEntity<SessionResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(name = CLIENT_HEADER, required = false) String clientHeader) {
        requireClientHeader(clientHeader);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("INVALID_SESSION", "The session is no longer valid. Sign in again.");
        }
        return sessionResponse(auth.refresh(refreshToken));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @RequestHeader(name = CLIENT_HEADER, required = false) String clientHeader) {
        requireClientHeader(clientHeader);
        if (refreshToken != null && !refreshToken.isBlank()) {
            auth.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal Jwt jwt) {
        return auth.currentUser(CurrentUser.id(jwt));
    }

    private ResponseEntity<SessionResponse> sessionResponse(IssuedSession session) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie(session.refreshToken(), properties.refreshTokenTtl())
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new SessionResponse(session.accessToken(), "Bearer", session.expiresInSeconds(), session.user()));
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.refreshCookieSecure())
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private static void requireClientHeader(String clientHeader) {
        if (clientHeader == null || clientHeader.isBlank()) {
            throw ApiException.forbidden("CLIENT_HEADER_REQUIRED", "The request is missing a required header.");
        }
    }
}
