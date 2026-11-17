package com.servora.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.ai.AiAnswer;
import com.servora.ai.AiAnswer.Citation;
import com.servora.ai.AiAnswer.RetrievalHit;
import com.servora.ai.AiClient;
import com.servora.common.ratelimit.RateLimiter;
import com.servora.support.IntegrationTestBase;
import com.servora.support.TwoTenants;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** FR-020 to FR-024: public widget sessions, token confinement, origins, messages, suppression and handoff. */
class WidgetConversationIntegrationTest extends IntegrationTestBase {

    private static final String SHOP = "https://shop.techstore.test";
    private static final String HOTEL = "https://www.hotelpro.test";

    private static TwoTenants tenants;
    private static String keyA;
    private static String keyB;
    private static UUID chunkA;
    private static UUID documentA;
    private static UUID chunkB;

    @Autowired
    private ScriptedAi ai;

    @Autowired
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() throws SQLException {
        if (tenants == null) {
            tenants = TwoTenants.create(this);
            keyA = configureWidget(tenants.techStore(), tenants.ownerA(), SHOP);
            keyB = configureWidget(tenants.hotelPro(), tenants.ownerB(), HOTEL);
            documentA = UUID.randomUUID();
            chunkA = seedChunk(tenants.techStore(), documentA, "Refunds are accepted within 30 days.");
            chunkB = seedChunk(tenants.hotelPro(), UUID.randomUUID(), "Check-in starts at 3 pm.");
        }
        ai.script = (tenantId, assistant, question) -> answered(chunkA, true);
        ai.questions.clear();
        clock.reset();
    }

    // --- helpers -------------------------------------------------------------------------------

    private String configureWidget(UUID workspaceId, Session owner, String origin) {
        String base = "/api/v1/workspaces/" + workspaceId + "/widget";
        assertThat(send("PUT", base + "/allowed-origins", "{\"origins\": [\"" + origin + "\"]}", owner.accessToken())
                        .status())
                .isEqualTo(200);
        return get(base, owner).json("$.public_key");
    }

    private static UUID seedChunk(UUID tenantId, UUID documentId, String text) throws SQLException {
        UUID chunkId = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO documents (id, tenant_id, source_id, lineage_id, version, original_filename,
                                           storage_key, detected_mime, size_bytes, content_hash, status)
                    SELECT ?, ?, s.id, ?, 1, 'policy.md', ?, 'text/markdown', 10, ?, 'READY'
                    FROM knowledge_sources s WHERE s.tenant_id = ?
                    """)) {
                statement.setObject(1, documentId);
                statement.setObject(2, tenantId);
                statement.setObject(3, documentId);
                statement.setString(4, "k/" + documentId);
                statement.setString(5, UUID.randomUUID().toString());
                statement.setObject(6, tenantId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document_chunks (id, tenant_id, document_id, chunk_index, content, embedding,
                                                 embedding_model, embedding_revision, embedding_dimension)
                    VALUES (?, ?, ?, 0, ?, '[0.1,0.2,0.3]'::vector, 'test-model', 'r1', 3)
                    """)) {
                statement.setObject(1, chunkId);
                statement.setObject(2, tenantId);
                statement.setObject(3, documentId);
                statement.setString(4, text);
                statement.executeUpdate();
            }
        }
        return chunkId;
    }

    private static AiAnswer answered(UUID chunk, boolean used) {
        return new AiAnswer(
                "ANSWERED",
                "Refunds are accepted within 30 days.",
                "question",
                false,
                null,
                List.of(new Citation(chunk, documentA, "policy.md", 2, "Refunds", "Refunds are accepted within 30 days.")),
                List.of(new RetrievalHit(chunk, documentA, "policy.md", 0.81, used)),
                "fake",
                "servora-fake-llm",
                "2026-11-17.1",
                "test-model",
                0.45,
                9,
                50,
                10);
    }

    private Response config(String key, String origin) {
        return origin == null
                ? send("GET", "/api/v1/public/widgets/" + key + "/config", null, null)
                : send("GET", "/api/v1/public/widgets/" + key + "/config", null, null, "Origin", origin);
    }

    private Response startSession(String key, String origin) {
        return send("POST", "/api/v1/public/widgets/" + key + "/sessions", null, null, "Origin", origin);
    }

    private String token(String key, String origin) {
        Response response = startSession(key, origin);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json("$.token");
    }

    private Response postMessage(String token, String key, String text) {
        return send("POST", "/api/v1/public/conversation/messages", "{\"text\": \"" + text + "\"}", token,
                "Idempotency-Key", key, "Origin", WIDGET_APP_ORIGIN);
    }

    private static String newKey() {
        return "msg-" + UUID.randomUUID();
    }

    private record Event(String name, String data) {}

    private static List<Event> events(Response response) {
        List<Event> events = new ArrayList<>();
        for (String block : response.body().replace("\r\n", "\n").split("\n\n")) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
            if (name != null) {
                events.add(new Event(name, data.toString()));
            }
        }
        return events;
    }

    private static List<String> names(Response response) {
        return events(response).stream().map(Event::name).toList();
    }

    private static Event event(Response response, String name) {
        return events(response).stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    private static UUID conversationOf(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(payload, "$.conversation_id"));
    }

    private static List<String> storedMessages(UUID conversationId) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT sender_type || ':' || visibility || ':' || delivery_status FROM messages
                        WHERE conversation_id = ? ORDER BY created_at, id
                        """)) {
            statement.setObject(1, conversationId);
            ResultSet result = statement.executeQuery();
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    // --- widget key and origin -----------------------------------------------------------------

    @Test
    void configIsServedOnlyToAnAllowedOriginWithExactMatch() {
        Response ok = config(keyA, SHOP);
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.<String>json("$.title")).isEqualTo("TechStore");
        assertThat(ok.<String>json("$.welcome_message")).isNotBlank();
        assertThat(ok.header("Access-Control-Allow-Origin")).isEqualTo(SHOP);

        // Default port and case are canonical forms of the same origin.
        assertThat(config(keyA, "HTTPS://Shop.TechStore.test:443").status()).isEqualTo(200);

        for (String origin : List.of(
                "https://shop.techstore.test.evil.test",
                "https://evil-shop.techstore.test",
                "https://techstore.test",
                "http://shop.techstore.test",
                "https://shop.techstore.test:8443",
                "null",
                HOTEL)) {
            Response refused = config(keyA, origin);
            assertThat(refused.status()).as(origin).isEqualTo(403);
            assertThat(refused.errorCode()).isEqualTo("ORIGIN_NOT_ALLOWED");
            assertThat(refused.header("Access-Control-Allow-Origin")).as(origin).isNull();
        }
        assertThat(config(keyA, null).status()).isEqualTo(403);
    }

    @Test
    void unknownMalformedRotatedAndDisabledKeysAreIndistinguishable() {
        Session owner = newUser("rotate");
        UUID workspaceId = createWorkspace(owner, "Rotate");
        String oldKey = configureWidget(workspaceId, owner, SHOP);
        String base = "/api/v1/workspaces/" + workspaceId + "/widget";
        assertThat(config(oldKey, SHOP).status()).isEqualTo(200);

        String newKey = post(base + "/rotate-key", null, owner).json("$.public_key");
        assertThat(config(oldKey, SHOP).status()).isEqualTo(404);
        assertThat(config(newKey, SHOP).status()).isEqualTo(200);

        assertThat(patch(base, "{\"status\": \"DISABLED\"}", owner).status()).isEqualTo(200);
        for (String key : List.of(newKey, "wk_doesnotexist12345", "not-a-key", "wk_' OR 1=1 --")) {
            Response response = config(key.replace(" ", "%20").replace("'", "%27"), SHOP);
            assertThat(response.status()).as(key).isEqualTo(404);
            assertThat(response.errorCode()).isEqualTo("WIDGET_NOT_FOUND");
        }
    }

    @Test
    void corsPreflightIsAnsweredOnlyForAllowedOrigins() {
        Response allowed = send("OPTIONS", "/api/v1/public/widgets/" + keyA + "/sessions", null, null,
                "Origin", SHOP, "Access-Control-Request-Method", "POST");
        assertThat(allowed.status()).isEqualTo(204);
        assertThat(allowed.header("Access-Control-Allow-Origin")).isEqualTo(SHOP);

        Response foreign = send("OPTIONS", "/api/v1/public/widgets/" + keyA + "/sessions", null, null,
                "Origin", HOTEL, "Access-Control-Request-Method", "POST");
        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.header("Access-Control-Allow-Origin")).isNull();

        Response app = send("OPTIONS", "/api/v1/public/conversation/messages", null, null,
                "Origin", WIDGET_APP_ORIGIN, "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization,content-type,idempotency-key");
        assertThat(app.status()).isEqualTo(204);
        assertThat(app.header("Access-Control-Allow-Headers")).contains("Idempotency-Key");

        Response site = send("OPTIONS", "/api/v1/public/conversation/messages", null, null,
                "Origin", SHOP, "Access-Control-Request-Method", "POST");
        assertThat(site.status()).isEqualTo(403);
    }

    // --- session token confinement -------------------------------------------------------------

    @Test
    void aSessionTokenReadsOnlyItsOwnConversation() {
        String tokenA = token(keyA, SHOP);
        String tokenB = token(keyB, HOTEL);
        assertThat(postMessage(tokenB, newKey(), "Hello from the hotel").status()).isEqualTo(200);

        Response history = send("GET", "/api/v1/public/conversation", null, tokenA);
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.<String>json("$.conversation_id")).isEqualTo(conversationOf(tokenA).toString());
        assertThat(history.<List<Object>>json("$.messages")).isEmpty();
        assertThat(history.body()).doesNotContain(conversationOf(tokenB).toString()).doesNotContain("hotel");
    }

    @Test
    void forgedExpiredAndForeignTokensAreRejected() {
        String token = token(keyA, SHOP);
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String otherConversation = payload.replace(conversationOf(token).toString(), UUID.randomUUID().toString());
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(otherConversation.getBytes())
                + "." + parts[2];

        byte[] userKey = new byte[48];
        new java.security.SecureRandom().nextBytes(userKey);
        String wrongKey = sign(userKey, payloadClaims(conversationOf(token)));
        String unsigned = parts[0].replace(parts[0], Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes())) + "." + parts[1] + ".";

        for (String bad : List.of(tampered, wrongKey, unsigned, "garbage", tenants.ownerA().accessToken())) {
            Response response = send("GET", "/api/v1/public/conversation", null, bad);
            assertThat(response.status()).as(bad).isEqualTo(401);
            assertThat(response.errorCode()).isEqualTo("WIDGET_SESSION_INVALID");
        }
        assertThat(send("GET", "/api/v1/public/conversation", null, null).status()).isEqualTo(401);

        clock.advance(Duration.ofHours(2).plusMinutes(1));
        assertThat(send("GET", "/api/v1/public/conversation", null, token).status()).isEqualTo(401);
    }

    @Test
    void aWidgetTokenIsWorthlessOnDashboardEndpoints() {
        String token = token(keyA, SHOP);

        for (String path : List.of(
                "/api/v1/workspaces",
                "/api/v1/workspaces/" + tenants.techStore(),
                "/api/v1/workspaces/" + tenants.techStore() + "/conversations",
                "/api/v1/workspaces/" + tenants.techStore() + "/knowledge/documents",
                "/api/v1/auth/me")) {
            assertThat(send("GET", path, null, token).status()).as(path).isEqualTo(401);
        }
    }

    private static JwtClaimsSet payloadClaims(UUID conversationId) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(WidgetTokens.ISSUER)
                .audience(List.of(WidgetTokens.AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .subject(UUID.randomUUID().toString())
                .claim("scope", "widget:conversation")
                .claim("tenant_id", tenants.techStore().toString())
                .claim("widget_id", UUID.randomUUID().toString())
                .claim("conversation_id", conversationId.toString())
                .build();
    }

    private static String sign(byte[] key, JwtClaimsSet claims) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
                new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    // --- messages ------------------------------------------------------------------------------

    @Test
    void aMessageIsAnsweredOverServerSentEventsWithCitationsAndRecorded() throws SQLException {
        String token = token(keyA, SHOP);
        ai.script = (tenantId, assistant, question) -> {
            assertThat(tenantId).isEqualTo(tenants.techStore());
            assertThat(question).isEqualTo("What is your refund policy?");
            return answered(chunkA, true);
        };

        Response response = postMessage(token, newKey(), "  What is your refund policy?  ");

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/event-stream");
        assertThat(names(response)).containsExactly("customer_message", "assistant_typing", "assistant_message", "done");
        String reply = event(response, "assistant_message").data();
        assertThat(reply).contains("\"sender_type\":\"ASSISTANT\"").contains("Refunds are accepted within 30 days.");
        assertThat(reply).contains("\"filename\":\"policy.md\"").contains("\"page_number\":2");
        // Internal identifiers are not exposed to the customer.
        assertThat(reply).doesNotContain(chunkA.toString()).doesNotContain(documentA.toString());

        Response history = send("GET", "/api/v1/public/conversation", null, token);
        assertThat(history.<List<String>>json("$.messages[*].sender_type")).containsExactly("CUSTOMER", "ASSISTANT");
        assertThat(history.<String>json("$.messages[1].citations[0].snippet")).contains("30 days");

        UUID conversation = conversationOf(token);
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT i.source, i.outcome, i.created_by_user_id, m.reply_to_id IS NOT NULL
                        FROM messages m JOIN ai_interactions i ON i.tenant_id = m.tenant_id AND i.id = m.interaction_id
                        WHERE m.conversation_id = ? AND m.sender_type = 'ASSISTANT'
                        """)) {
            statement.setObject(1, conversation);
            ResultSet row = statement.executeQuery();
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo("WIDGET");
            assertThat(row.getString(2)).isEqualTo("ANSWERED");
            assertThat(row.getObject(3)).isNull();
            assertThat(row.getBoolean(4)).isTrue();
        }
    }

    @Test
    void aRetriedSubmissionIsStoredOnceAndReplaysTheSameReply() throws SQLException {
        String token = token(keyA, SHOP);
        String key = newKey();

        Response first = postMessage(token, key, "Refunds?");
        Response retry = postMessage(token, key, "Refunds?");
        Response reused = postMessage(token, key, "Something else");

        assertThat(names(first)).containsExactly("customer_message", "assistant_typing", "assistant_message", "done");
        assertThat(names(retry)).containsExactly("customer_message", "assistant_message", "done");
        assertThat(event(retry, "assistant_message").data()).isEqualTo(event(first, "assistant_message").data());
        assertThat(event(retry, "customer_message").data()).isEqualTo(event(first, "customer_message").data());
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(ai.questions).hasSize(1);
        assertThat(storedMessages(conversationOf(token)))
                .containsExactly("CUSTOMER:PUBLIC:DELIVERED", "ASSISTANT:PUBLIC:DELIVERED");
    }

    @Test
    void invalidSubmissionsAreRejectedBeforeAnythingIsStored() throws SQLException {
        String token = token(keyA, SHOP);
        String path = "/api/v1/public/conversation/messages";

        assertThat(send("POST", path, "{\"text\": \"Hi\"}", token).errorCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(send("POST", path, "{\"text\": \"Hi\"}", token, "Idempotency-Key", "short").status()).isEqualTo(400);
        assertThat(postMessage(token, newKey(), "   ").status()).isEqualTo(400);
        assertThat(postMessage(token, newKey(), "x".repeat(2001)).status()).isEqualTo(400);
        assertThat(send("POST", path, "{\"text\": \"Hi\"}", null, "Idempotency-Key", newKey()).status()).isEqualTo(401);
        assertThat(storedMessages(conversationOf(token))).isEmpty();
        assertThat(ai.questions).isEmpty();
    }

    @Test
    void messagesAreRateLimitedPerConversation() {
        String token = token(keyA, SHOP);
        for (int i = 0; i < WIDGET_MESSAGES_PER_MINUTE; i++) {
            assertThat(postMessage(token, newKey(), "Question " + i).status()).isEqualTo(200);
        }
        Response limited = postMessage(token, newKey(), "One too many");

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.errorCode()).isEqualTo("WIDGET_RATE_LIMITED");
        assertThat(ai.questions).hasSize(WIDGET_MESSAGES_PER_MINUTE);
        // Another conversation is not affected.
        assertThat(postMessage(token(keyA, SHOP), newKey(), "Fresh").status()).isEqualTo(200);
        // The window moves on.
        clock.advance(Duration.ofMinutes(1));
        assertThat(postMessage(token, newKey(), "Later").status()).isEqualTo(200);
    }

    @Test
    void theRateLimiterCountsPerBucketAndWindow() {
        String bucket = "test:" + UUID.randomUUID();
        assertThat(rateLimiter.tryAcquire(bucket, 2, Duration.ofMinutes(1))).isTrue();
        assertThat(rateLimiter.tryAcquire(bucket, 2, Duration.ofMinutes(1))).isTrue();
        assertThat(rateLimiter.tryAcquire(bucket, 2, Duration.ofMinutes(1))).isFalse();
        assertThat(rateLimiter.tryAcquire(bucket + "-other", 2, Duration.ofMinutes(1))).isTrue();
        clock.advance(Duration.ofMinutes(1));
        assertThat(rateLimiter.tryAcquire(bucket, 2, Duration.ofMinutes(1))).isTrue();
        assertThat(RateLimiter.hashed("203.0.113.9")).hasSize(24).doesNotContain("203");
    }

    // --- takeover, handoff and failures ----------------------------------------------------------

    @Test
    void aReplyIsSuppressedIfAPersonTookOverWhileItWasGenerated() throws SQLException {
        String token = token(keyA, SHOP);
        UUID conversation = conversationOf(token);
        ai.script = (tenantId, assistant, question) -> {
            // An agent takes over while the model is still answering.
            try (Connection connection = ownerConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "UPDATE conversations SET control_mode = 'HUMAN_ACTIVE', version = version + 1 WHERE id = ?")) {
                statement.setObject(1, conversation);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            return answered(chunkA, true);
        };

        Response response = postMessage(token, newKey(), "Refunds?");

        assertThat(names(response)).containsExactly("customer_message", "assistant_typing", "notice", "done");
        assertThat(event(response, "notice").data()).contains("SUPPRESSED");
        assertThat(response.body()).doesNotContain("Refunds are accepted within 30 days.");
        assertThat(storedMessages(conversation))
                .containsExactly("CUSTOMER:PUBLIC:DELIVERED", "ASSISTANT:INTERNAL:SUPPRESSED");
        assertThat(send("GET", "/api/v1/public/conversation", null, token).<List<String>>json("$.messages[*].sender_type"))
                .containsExactly("CUSTOMER");
    }

    @Test
    void askingForAPersonStopsAiRepliesAndIsIdempotent() throws SQLException {
        String token = token(keyA, SHOP);

        Response handoff = send("POST", "/api/v1/public/conversation/handoff", null, token);
        Response again = send("POST", "/api/v1/public/conversation/handoff", null, token);
        Response after = postMessage(token, newKey(), "Are you there?");

        assertThat(handoff.status()).isEqualTo(200);
        assertThat(handoff.<String>json("$.control_mode")).isEqualTo("ESCALATION_PENDING");
        assertThat(again.<List<String>>json("$.messages[*].sender_type")).containsExactly("SYSTEM");
        assertThat(names(after)).containsExactly("customer_message", "notice", "done");
        assertThat(event(after, "notice").data()).contains("HUMAN_PENDING");
        assertThat(ai.questions).isEmpty();
        assertThat(storedMessages(conversationOf(token)))
                .containsExactly("SYSTEM:PUBLIC:DELIVERED", "CUSTOMER:PUBLIC:DELIVERED");
    }

    @Test
    void whenTheAiServiceFailsTheCustomerGetsTheFallbackMessage() {
        String token = token(keyA, SHOP);
        ai.script = (tenantId, assistant, question) -> {
            throw new AiClient.AiUnavailableException("connection refused", null);
        };

        Response response = postMessage(token, newKey(), "Refunds?");

        // A service error also hands the conversation to a person (M5).
        assertThat(names(response)).containsExactly(
                "customer_message", "assistant_typing", "assistant_message", "system_message", "notice", "done");
        assertThat(event(response, "assistant_message").data())
                .contains("I am not sure about that.")
                .doesNotContain("connection refused");
    }

    @Test
    void aTechStoreQuestionCanNeverBeAnsweredFromHotelProKnowledge() throws SQLException {
        String token = token(keyA, SHOP);
        AtomicInteger calls = new AtomicInteger();
        // A faulty AI service cites the other tenant's chunk.
        ai.script = (tenantId, assistant, question) -> {
            calls.incrementAndGet();
            assertThat(tenantId).isEqualTo(tenants.techStore());
            return answered(chunkB, true);
        };

        Response response = postMessage(token, newKey(), "What time is hotel check-in?");

        assertThat(calls).hasValue(1);
        String body = response.body();
        assertThat(body).doesNotContain(chunkB.toString())
                .doesNotContain(tenants.hotelPro().toString())
                .doesNotContain("Check-in starts at 3 pm")
                .doesNotContain("Refunds are accepted");
        assertThat(event(response, "assistant_message").data()).contains("I am not sure about that.");
        assertThat(event(response, "assistant_message").data()).contains("\"citations\":[]");
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM message_citations c JOIN messages m ON m.id = c.message_id WHERE m.conversation_id = ?")) {
            statement.setObject(1, conversationOf(token));
            ResultSet row = statement.executeQuery();
            row.next();
            assertThat(row.getInt(1)).isZero();
        }
    }

    // --- dashboard views -----------------------------------------------------------------------

    @Test
    void ownersSeeTheirConversationsIncludingSuppressedRepliesAndNobodyElseDoes() {
        Session owner = newUser("inbox");
        UUID workspaceId = createWorkspace(owner, "Inbox");
        String key = configureWidget(workspaceId, owner, SHOP);
        String token = token(key, SHOP);
        ai.script = (tenantId, assistant, question) -> new AiAnswer(
                "NO_EVIDENCE", assistant.fallbackMessage(), null, true, "no_evidence", List.of(), List.of(),
                "fake", null, "v", "e", 0.45, 3, 0, 0);
        assertThat(postMessage(token, newKey(), "Hello?").status()).isEqualTo(200);
        token(key, SHOP); // An opened chat with no message is not listed.

        String base = "/api/v1/workspaces/" + workspaceId + "/conversations";
        Response list = get(base, owner);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        assertThat(list.<Integer>json("$.total")).isEqualTo(1);
        assertThat(list.<String>json("$.items[0].origin")).isEqualTo(SHOP);
        assertThat(list.<Integer>json("$.items[0].message_count")).isEqualTo(2);

        String id = list.json("$.items[0].id");
        Response messages = get(base + "/" + id + "/messages", owner);
        assertThat(messages.<List<String>>json("$.items[*].sender_type")).containsExactly("CUSTOMER", "ASSISTANT");
        assertThat(messages.<String>json("$.items[1].outcome")).isEqualTo("NO_EVIDENCE");
        assertThat(messages.<String>json("$.items[1].visibility")).isEqualTo("PUBLIC");

        Member agent = addMember(owner, workspaceId, "inbox-agent", "AGENT");
        assertThat(get(base, agent.session()).status()).isEqualTo(403);
        assertThat(get(base, tenants.ownerB()).status()).isEqualTo(404);
        // Another workspace's conversation id, asked for through this workspace.
        String foreign = "/api/v1/workspaces/" + tenants.techStore() + "/conversations/" + id;
        assertThat(get(foreign, tenants.ownerA()).status()).isEqualTo(404);
        assertThat(get(foreign + "/messages", tenants.ownerA()).status()).isEqualTo(404);
    }
}
