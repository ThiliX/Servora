package com.servora.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.servora.common.mail.MailGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Runs the real application over HTTP against a real PostgreSQL with Row-Level Security.
 *
 * <p>The database is set up the way production is: the owner role runs Flyway and owns the tables,
 * and the application connects as a separate runtime role that owns nothing and cannot bypass RLS.
 * The connection pool is deliberately tiny so pooled connections are reused constantly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTestBase.TestBeans.class)
public abstract class IntegrationTestBase {

    protected static final String OWNER_USER = "servora_owner";
    protected static final String OWNER_PASSWORD = "owner-" + UUID.randomUUID();
    protected static final String APP_USER = "servora_app";
    protected static final String APP_PASSWORD = "app-" + UUID.randomUUID();
    protected static final String PASSWORD = "correct-horse-battery-staple";
    protected static final int POOL_SIZE = 2;

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("servora")
            .withUsername(OWNER_USER)
            .withPassword(OWNER_PASSWORD);

    static {
        POSTGRES.start();
        try (Connection connection = ownerConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> POOL_SIZE);
        registry.add("spring.flyway.user", () -> OWNER_USER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.flyway.placeholders.app_role", () -> APP_USER);
        registry.add("servora.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(key));
    }

    protected static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    protected static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        RecordingMail recordingMail() {
            return new RecordingMail();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    /** Captures the links that would have been emailed. */
    public static final class RecordingMail implements MailGateway {

        private final Map<String, String> verificationLinks = new ConcurrentHashMap<>();
        private final Map<String, String> invitationLinks = new ConcurrentHashMap<>();

        @Override
        public void sendEmailVerification(String toEmail, String verificationLink) {
            verificationLinks.put(toEmail, verificationLink);
        }

        @Override
        public void sendWorkspaceInvitation(String toEmail, String workspaceName, String invitationLink) {
            invitationLinks.put(toEmail, invitationLink);
        }

        public String verificationToken(String email) {
            return tokenOf(verificationLinks.get(email));
        }

        public String invitationToken(String email) {
            return tokenOf(invitationLinks.get(email));
        }

        public boolean hasVerificationFor(String email) {
            return verificationLinks.containsKey(email);
        }

        public void forgetVerification(String email) {
            verificationLinks.remove(email);
        }

        private static String tokenOf(String link) {
            assertThat(link).as("an email link was sent").isNotNull();
            return link.substring(link.indexOf("#token=") + "#token=".length());
        }
    }

    /** A clock tests can move forward to exercise expiry. */
    public static final class MutableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        public void advance(Duration duration) {
            offset.updateAndGet(current -> current.plus(duration));
        }

        public void reset() {
            offset.set(Duration.ZERO);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }
    }

    public record Response(int status, String body, HttpResponse<String> raw) {

        public <T> T json(String path) {
            return JsonPath.read(body, path);
        }

        public String errorCode() {
            return json("$.error.code");
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    public record Session(String email, UUID userId, String accessToken, String refreshToken) {}

    public record Member(Session session, UUID memberId) {}

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected RecordingMail mail;

    @Autowired
    protected MutableClock clock;

    private final HttpClient http = HttpClient.newHttpClient();

    protected Response send(String method, String path, String body, String accessToken, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Response get(String path, Session session) {
        return send("GET", path, null, session == null ? null : session.accessToken());
    }

    protected Response post(String path, String body, Session session) {
        return send("POST", path, body, session == null ? null : session.accessToken());
    }

    protected Response patch(String path, String body, Session session) {
        return send("PATCH", path, body, session.accessToken());
    }

    protected Response delete(String path, Session session) {
        return send("DELETE", path, null, session.accessToken());
    }

    protected static String uniqueEmail(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    protected static String refreshTokenFrom(Response response) {
        String cookie = response.header("Set-Cookie");
        assertThat(cookie).startsWith("servora_refresh=");
        return cookie.substring("servora_refresh=".length(), cookie.indexOf(';'));
    }

    protected Response register(String email, String password) {
        return post(
                "/api/v1/auth/register",
                """
                {"email": "%s", "password": "%s", "display_name": "Test User"}"""
                        .formatted(email, password),
                null);
    }

    protected Response login(String email, String password) {
        return post(
                "/api/v1/auth/login",
                """
                {"email": "%s", "password": "%s"}""".formatted(email, password),
                null);
    }

    protected Response refresh(String refreshToken) {
        return send(
                "POST",
                "/api/v1/auth/refresh",
                null,
                null,
                "Cookie",
                "servora_refresh=" + refreshToken,
                "X-Servora-Client",
                "web");
    }

    /** Registers a user, verifies the email and signs in. */
    protected Session newUser(String label) {
        String email = uniqueEmail(label);
        assertThat(register(email, PASSWORD).status()).isEqualTo(202);
        Response verify = post(
                "/api/v1/auth/verify-email",
                """
                {"token": "%s"}""".formatted(mail.verificationToken(email)),
                null);
        assertThat(verify.status()).isEqualTo(204);
        return signIn(email);
    }

    protected Session signIn(String email) {
        Response response = login(email, PASSWORD);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return new Session(
                email,
                UUID.fromString(response.json("$.user.id")),
                response.json("$.access_token"),
                refreshTokenFrom(response));
    }

    protected UUID createWorkspace(Session owner, String name) {
        String slug = name.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Response response = post(
                "/api/v1/workspaces",
                """
                {"name": "%s", "slug": "%s"}""".formatted(name, slug),
                owner);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return UUID.fromString(response.json("$.id"));
    }

    protected Response invite(Session inviter, UUID workspaceId, String email, String role) {
        return post(
                "/api/v1/workspaces/" + workspaceId + "/invitations",
                """
                {"email": "%s", "role": "%s"}""".formatted(email, role),
                inviter);
    }

    protected Response acceptInvitation(Session user, String token) {
        return post(
                "/api/v1/invitations/accept",
                """
                {"token": "%s"}""".formatted(token),
                user);
    }

    /** Creates a new verified user and adds them to the workspace through a real invitation. */
    protected Member addMember(Session inviter, UUID workspaceId, String label, String role) {
        Session user = newUser(label);
        addExistingUser(inviter, workspaceId, user, role);
        return new Member(user, memberIdOf(inviter, workspaceId, user.email()));
    }

    protected void addExistingUser(Session inviter, UUID workspaceId, Session user, String role) {
        Response invitation = invite(inviter, workspaceId, user.email(), role);
        assertThat(invitation.status()).as(invitation.body()).isEqualTo(201);
        Response accepted = acceptInvitation(user, mail.invitationToken(user.email()));
        assertThat(accepted.status()).as(accepted.body()).isEqualTo(200);
    }

    protected UUID memberIdOf(Session viewer, UUID workspaceId, String email) {
        Response members = get("/api/v1/workspaces/" + workspaceId + "/members", viewer);
        assertThat(members.status()).isEqualTo(200);
        java.util.List<String> ids = members.json("$.items[?(@.email == '" + email + "')].id");
        assertThat(ids).hasSize(1);
        return UUID.fromString(ids.get(0));
    }
}
