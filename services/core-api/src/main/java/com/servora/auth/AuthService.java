package com.servora.auth;

import com.servora.common.error.ApiException;
import com.servora.common.mail.MailGateway;
import com.servora.common.security.AuthProperties;
import com.servora.common.security.Tokens;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public record UserView(UUID id, String email, String displayName) {

        static UserView of(User user) {
            return new UserView(user.getId(), user.getEmail(), user.getDisplayName());
        }
    }

    /** A new access token plus the raw refresh token to place in the cookie. */
    public record IssuedSession(String accessToken, long expiresInSeconds, String refreshToken, UserView user) {}

    private final UserRepository users;
    private final EmailVerificationTokenRepository verificationTokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final LoginAttemptLimiter loginLimiter;
    private final MailGateway mail;
    private final AuthProperties properties;
    private final Clock clock;
    private final String webBaseUrl;
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository users,
            EmailVerificationTokenRepository verificationTokens,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            LoginAttemptLimiter loginLimiter,
            MailGateway mail,
            AuthProperties properties,
            Clock clock,
            @Value("${servora.web.base-url}") String webBaseUrl) {
        this.users = users;
        this.verificationTokens = verificationTokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.loginLimiter = loginLimiter;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
        this.webBaseUrl = webBaseUrl;
        // Verified against when the email is unknown, so both paths cost one hash comparison.
        this.dummyPasswordHash = passwordEncoder.encode(Tokens.generate());
    }

    /**
     * Registers a user and sends a verification link. The outcome is deliberately the same whether
     * or not the email is already registered, so the endpoint cannot be used to enumerate accounts.
     */
    @Transactional
    public void register(String email, String password, String displayName) {
        String normalized = Tokens.normalizeEmail(email);
        if (users.findByEmail(normalized).isPresent()) {
            return;
        }
        Instant now = clock.instant();
        User user = users.save(new User(normalized, passwordEncoder.encode(password), displayName.trim(), now));

        String token = Tokens.generate();
        verificationTokens.save(new EmailVerificationToken(
                user.getId(), Tokens.hash(token), now.plus(properties.verificationTokenTtl()), now));
        // The token travels in the URL fragment, which browsers do not send to servers or proxies.
        mail.sendEmailVerification(normalized, webBaseUrl + "/verify-email#token=" + token);
    }

    @Transactional
    public void verifyEmail(String token) {
        Instant now = clock.instant();
        EmailVerificationToken stored = verificationTokens
                .findByTokenHash(Tokens.hash(token))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest(
                        "INVALID_VERIFICATION_TOKEN", "The verification link is invalid or has expired."));
        User user = users.findById(stored.getUserId()).orElseThrow();
        user.markEmailVerified(now);
        stored.markUsed(now);
    }

    @Transactional
    public IssuedSession login(String email, String password, String clientAddress) {
        String normalized = Tokens.normalizeEmail(email);
        String limiterKey = normalized + "|" + clientAddress;
        if (loginLimiter.isBlocked(limiterKey)) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many failed attempts. Try again later.");
        }

        Optional<User> found = users.findByEmail(normalized);
        String hash = found.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(password, hash);

        if (found.isEmpty() || !passwordMatches || found.get().getStatus() == User.Status.DISABLED) {
            loginLimiter.recordFailure(limiterKey);
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "The email or password is incorrect.");
        }
        User user = found.get();
        if (user.getStatus() == User.Status.PENDING_VERIFICATION) {
            // Reached only with the correct password, so it reveals nothing to a third party.
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED", "Verify your email address before signing in.");
        }
        loginLimiter.reset(limiterKey);
        return issueSession(user, UUID.randomUUID(), clock.instant());
    }

    /**
     * Rotates a refresh token. Presenting a token that was already rotated means it was copied, so
     * the whole family is revoked. {@code noRollbackFor} keeps that revocation when the request is
     * then rejected.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedSession refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens
                .findByTokenHash(Tokens.hash(rawRefreshToken))
                .orElseThrow(AuthService::invalidSession);

        if (current.isRevoked() || refreshTokens.revokeIfActive(current.getId(), now) == 0) {
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw invalidSession();
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw invalidSession();
        }
        User user = users.findById(current.getUserId()).filter(User::isActive).orElseThrow(AuthService::invalidSession);
        return issueSession(user, current.getFamilyId(), now);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens
                .findByTokenHash(Tokens.hash(rawRefreshToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public UserView currentUser(UUID userId) {
        return users.findById(userId)
                .filter(User::isActive)
                .map(UserView::of)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Authentication is required."));
    }

    private IssuedSession issueSession(User user, UUID familyId, Instant now) {
        String refreshToken = Tokens.generate();
        refreshTokens.save(new RefreshToken(
                user.getId(), familyId, Tokens.hash(refreshToken), now.plus(properties.refreshTokenTtl()), now));

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .build();
        String accessToken = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedSession(
                accessToken, properties.accessTokenTtl().toSeconds(), refreshToken, UserView.of(user));
    }

    private static ApiException invalidSession() {
        return ApiException.unauthorized("INVALID_SESSION", "The session is no longer valid. Sign in again.");
    }
}
