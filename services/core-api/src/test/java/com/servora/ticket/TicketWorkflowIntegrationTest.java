package com.servora.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.servora.ai.AiAnswer;
import com.servora.ai.AiAnswer.Citation;
import com.servora.ai.AiAnswer.RetrievalHit;
import com.servora.ai.AiClient;
import com.servora.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** FR-032 to FR-037: escalation, tickets, takeover, agent replies, notes, suggestions and ratings. */
class TicketWorkflowIntegrationTest extends IntegrationTestBase {

    private static final String SITE = "https://shop.example.test";

    private Session owner;
    private UUID workspace;
    private String widgetKey;
    private Member supervisor;
    private Member agent;
    private Member otherAgent;
    private UUID chunk;
    private UUID document;

    @Autowired
    private ScriptedAi ai;

    @BeforeEach
    void setUp() throws SQLException {
        owner = newUser("tickets-owner");
        workspace = createWorkspace(owner, "Tickets");
        assertThat(send("PUT", base() + "/widget/allowed-origins", "{\"origins\": [\"" + SITE + "\"]}", owner.accessToken())
                        .status())
                .isEqualTo(200);
        widgetKey = get(base() + "/widget", owner).json("$.public_key");
        supervisor = addMember(owner, workspace, "tickets-supervisor", "SUPERVISOR");
        agent = addMember(owner, workspace, "tickets-agent", "AGENT");
        otherAgent = addMember(owner, workspace, "tickets-agent-2", "AGENT");
        document = UUID.randomUUID();
        chunk = seedChunk(workspace, document);
        ai.script = (tenantId, assistant, question) -> answered("Refunds are accepted within 30 days.");
        ai.questions.clear();
        clock.reset();
    }

    // --- helpers -------------------------------------------------------------------------------

    private String base() {
        return "/api/v1/workspaces/" + workspace;
    }

    private static UUID seedChunk(UUID tenantId, UUID documentId) throws SQLException {
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
                    VALUES (?, ?, ?, 0, 'Refunds are accepted within 30 days.', '[0.1,0.2,0.3]'::vector, 'test-model', 'r1', 3)
                    """)) {
                statement.setObject(1, chunkId);
                statement.setObject(2, tenantId);
                statement.setObject(3, documentId);
                statement.executeUpdate();
            }
        }
        return chunkId;
    }

    private AiAnswer answered(String text) {
        return new AiAnswer("ANSWERED", text, "question", false, null,
                List.of(new Citation(chunk, document, "policy.md", null, null, "Refunds are accepted within 30 days.")),
                List.of(new RetrievalHit(chunk, document, "policy.md", 0.8, true)),
                "fake", "servora-fake-llm", "v", "test-model", 0.45, 5, 10, 5);
    }

    private static AiAnswer outcome(String outcome, String intent, String reason) {
        return new AiAnswer(outcome, "I am not sure about that. Would you like to talk to a member of our team?", intent,
                true, reason, List.of(), List.of(), "fake", null, "v", "e", 0.45, 1, 0, 0);
    }

    private String token() {
        Response response = send("POST", "/api/v1/public/widgets/" + widgetKey + "/sessions", null, null, "Origin", SITE);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json("$.token");
    }

    private Response customerSays(String token, String text) {
        return send("POST", "/api/v1/public/conversation/messages", "{\"text\": \"" + text + "\"}", token,
                "Idempotency-Key", "k-" + UUID.randomUUID());
    }

    private Response history(String token) {
        return send("GET", "/api/v1/public/conversation", null, token);
    }

    private static UUID conversationOf(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        return UUID.fromString(JsonPath.read(payload, "$.conversation_id"));
    }

    private static List<String> eventNames(Response response) {
        List<String> names = new ArrayList<>();
        for (String line : response.body().replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("event:")) {
                names.add(line.substring(6).trim());
            }
        }
        return names;
    }

    private String ticketOf(UUID conversationId) throws SQLException {
        try (Connection connection = ownerConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT id FROM tickets WHERE conversation_id = ?")) {
            statement.setObject(1, conversationId);
            ResultSet row = statement.executeQuery();
            return row.next() ? row.getString(1) : null;
        }
    }

    private static int count(String sql, Object parameter) throws SQLException {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            ResultSet row = statement.executeQuery();
            row.next();
            return row.getInt(1);
        }
    }

    private int versionOf(UUID conversationId) throws SQLException {
        return count("SELECT version FROM conversations WHERE id = ?", conversationId);
    }

    private Response takeover(Session session, UUID conversationId, int version) {
        return send("POST", base() + "/conversations/" + conversationId + "/takeover",
                "{\"expected_version\": " + version + "}", session.accessToken());
    }

    // --- journey 4.3 ---------------------------------------------------------------------------

    @Test
    void fullJourneyFromAiToPersonToResolutionAndRating() throws SQLException {
        String token = token();
        UUID conversation = conversationOf(token);
        assertThat(customerSays(token, "Refunds?").status()).isEqualTo(200);

        // 24-25: the customer asks for a person; the conversation is escalated with one ticket.
        Response handoff = send("POST", "/api/v1/public/conversation/handoff", null, token);
        assertThat(handoff.<String>json("$.control_mode")).isEqualTo("ESCALATION_PENDING");
        String ticket = ticketOf(conversation);
        assertThat(ticket).isNotNull();
        Response detail = get(base() + "/tickets/" + ticket, owner);
        assertThat(detail.<String>json("$.ticket.status")).isEqualTo("OPEN");
        assertThat(detail.<String>json("$.ticket.reason")).isEqualTo("CUSTOMER_REQUEST");
        assertThat(detail.<Integer>json("$.ticket.number")).isEqualTo(1);

        // An agent sees unassigned tickets in the queue, but not yet as theirs.
        assertThat(get(base() + "/tickets?view=UNASSIGNED", agent.session()).<Integer>json("$.total")).isEqualTo(1);
        assertThat(get(base() + "/tickets?view=MINE", agent.session()).<Integer>json("$.total")).isZero();

        // 26: a supervisor assigns it; the agent is notified.
        Response assigned = send("PUT", base() + "/tickets/" + ticket + "/assignee",
                "{\"membership_id\": \"" + agent.memberId() + "\"}", supervisor.session().accessToken());
        assertThat(assigned.status()).as(assigned.body()).isEqualTo(200);
        Response notes = get(base() + "/notifications", agent.session());
        assertThat(notes.<Integer>json("$.unread_count")).isEqualTo(1);
        assertThat(notes.<String>json("$.items[0].type")).isEqualTo("TICKET_ASSIGNED");
        assertThat(get(base() + "/tickets?view=MINE", agent.session()).<Integer>json("$.total")).isEqualTo(1);
        // The other agent can no longer see it at all.
        assertThat(get(base() + "/tickets/" + ticket, otherAgent.session()).status()).isEqualTo(404);

        // An agent may not reply before taking over.
        assertThat(send("POST", base() + "/conversations/" + conversation + "/replies", "{\"body\": \"Hi\"}",
                        agent.session().accessToken()).errorCode())
                .isEqualTo("TAKEOVER_REQUIRED");

        // 27: the agent takes over with the version they saw.
        Response taken = takeover(agent.session(), conversation, versionOf(conversation));
        assertThat(taken.status()).as(taken.body()).isEqualTo(200);
        assertThat(taken.<String>json("$.ticket.control_mode")).isEqualTo("HUMAN_ACTIVE");
        assertThat(taken.<String>json("$.ticket.status")).isEqualTo("IN_PROGRESS");

        // 29: an internal note and a public reply.
        assertThat(send("POST", base() + "/conversations/" + conversation + "/notes",
                        "{\"body\": \"Customer seems to have order 1234\"}", agent.session().accessToken())
                .status()).isEqualTo(201);
        Response reply = send("POST", base() + "/conversations/" + conversation + "/replies",
                "{\"body\": \"Hello, I can help with your refund.\"}", agent.session().accessToken());
        assertThat(reply.status()).as(reply.body()).isEqualTo(201);
        assertThat(get(base() + "/tickets/" + ticket, agent.session()).<String>json("$.ticket.status"))
                .isEqualTo("WAITING_FOR_CUSTOMER");

        Response seen = history(token);
        assertThat(seen.<List<String>>json("$.messages[*].sender_type"))
                .containsExactly("CUSTOMER", "ASSISTANT", "SYSTEM", "SYSTEM", "AGENT");
        assertThat(seen.body()).contains("I can help with your refund").doesNotContain("order 1234");

        // The customer replies: no AI answer, the ticket is back in progress and the agent is notified.
        ai.questions.clear();
        Response customerReply = customerSays(token, "Thanks, it was order 1234");
        assertThat(eventNames(customerReply)).containsExactly("customer_message", "notice", "done");
        assertThat(ai.questions).isEmpty();
        assertThat(get(base() + "/tickets/" + ticket, agent.session()).<String>json("$.ticket.status")).isEqualTo("IN_PROGRESS");
        assertThat(get(base() + "/notifications", agent.session()).<List<String>>json("$.items[*].type"))
                .contains("CUSTOMER_MESSAGE");

        // Rating is not possible before resolution.
        assertThat(send("POST", "/api/v1/public/conversation/feedback", "{\"rating\": 5}", token).errorCode())
                .isEqualTo("CONVERSATION_NOT_RESOLVED");

        // 30: the agent resolves; the conversation ends and the customer rates it once.
        Response resolved = send("POST", base() + "/tickets/" + ticket + "/resolve",
                "{\"outcome\": \"SOLVED\", \"note\": \"Refund issued\"}", agent.session().accessToken());
        assertThat(resolved.<String>json("$.ticket.status")).isEqualTo("RESOLVED");
        assertThat(resolved.<String>json("$.ticket.conversation_status")).isEqualTo("CLOSED");
        assertThat(customerSays(token, "One more thing").errorCode()).isEqualTo("CONVERSATION_CLOSED");

        Response rated = send("POST", "/api/v1/public/conversation/feedback",
                "{\"rating\": 5, \"comment\": \"Quick and helpful\"}", token);
        assertThat(rated.status()).as(rated.body()).isEqualTo(200);
        assertThat(rated.<Boolean>json("$.feedback_submitted")).isTrue();
        assertThat(send("POST", "/api/v1/public/conversation/feedback", "{\"rating\": 1}", token).errorCode())
                .isEqualTo("FEEDBACK_EXISTS");
        assertThat(send("POST", "/api/v1/public/conversation/feedback", "{\"rating\": 6}", token).status()).isEqualTo(400);

        Response closed = send("POST", base() + "/tickets/" + ticket + "/close", null, agent.session().accessToken());
        assertThat(closed.<String>json("$.ticket.status")).isEqualTo("CLOSED");
        assertThat(closed.<Integer>json("$.feedback.rating")).isEqualTo(5);
        assertThat(closed.<List<String>>json("$.events[*].event_type")).containsExactly(
                "CREATED", "ASSIGNED", "STATUS_CHANGED", "TAKEN_OVER", "STATUS_CHANGED", "STATUS_CHANGED",
                "RESOLVED", "CLOSED");
    }

    // --- idempotency and concurrency -----------------------------------------------------------

    @Test
    void parallelEscalationsCreateExactlyOneTicket() throws Exception {
        String token = token();
        UUID conversation = conversationOf(token);
        int parallel = 8;
        CyclicBarrier start = new CyclicBarrier(parallel);
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < parallel; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return send("POST", "/api/v1/public/conversation/handoff", null, token).status();
                }, pool));
            }
            for (CompletableFuture<Integer> call : calls) {
                assertThat(call.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM tickets WHERE conversation_id = ?", conversation)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM messages WHERE conversation_id = ? AND sender_type = 'SYSTEM'", conversation))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ticket_events WHERE event_type = 'CREATED' AND ticket_id = "
                + "(SELECT id FROM tickets WHERE conversation_id = ?)", conversation)).isEqualTo(1);
    }

    /**
     * Takeover racing an AI reply, many times. Whatever the timing, an AI reply is never delivered
     * after the takeover: either it was delivered first, or it is suppressed.
     */
    @Test
    void anAiReplyIsNeverDeliveredAfterATakeover() throws Exception {
        int rounds = 24;
        int suppressed = 0;
        int deliveredFirst = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                String token = token();
                UUID conversation = conversationOf(token);
                CountDownLatch aiStarted = new CountDownLatch(1);
                // Alternate who is likely to win, and add jitter so the interleavings vary.
                long aiDelay = round % 2 == 0 ? 60 : (round % 3) * 7L;
                long takeoverDelay = round % 2 == 0 ? (round % 4) * 3L : 80;
                ai.script = (tenantId, assistant, question) -> {
                    aiStarted.countDown();
                    sleep(aiDelay);
                    return answered("Refunds are accepted within 30 days.");
                };

                CompletableFuture<Response> customer = CompletableFuture.supplyAsync(() -> customerSays(token, "Refunds?"), pool);
                CompletableFuture<Response> agentCall = CompletableFuture.supplyAsync(() -> {
                    try {
                        aiStarted.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    sleep(takeoverDelay);
                    return takeover(owner, conversation, 1);
                }, pool);

                Response sse = customer.get(30, TimeUnit.SECONDS);
                Response taken = agentCall.get(30, TimeUnit.SECONDS);
                assertThat(taken.status()).as("round %d: %s", round, taken.body()).isEqualTo(200);

                try (Connection connection = ownerConnection();
                        PreparedStatement statement = connection.prepareStatement("""
                                SELECT m.delivery_status, m.created_at,
                                       (SELECT e.created_at FROM ticket_events e JOIN tickets t ON t.id = e.ticket_id
                                        WHERE t.conversation_id = m.conversation_id AND e.event_type = 'TAKEN_OVER')
                                FROM messages m WHERE m.conversation_id = ? AND m.sender_type = 'ASSISTANT'
                                """)) {
                    statement.setObject(1, conversation);
                    ResultSet row = statement.executeQuery();
                    assertThat(row.next()).as("round %d has an assistant reply", round).isTrue();
                    String delivery = row.getString(1);
                    Timestamp replyAt = row.getTimestamp(2);
                    Timestamp takenAt = row.getTimestamp(3);
                    if ("DELIVERED".equals(delivery)) {
                        deliveredFirst++;
                        assertThat(replyAt).as("round %d: delivered reply must precede takeover", round).isBefore(takenAt);
                        assertThat(eventNames(sse)).contains("assistant_message");
                    } else {
                        suppressed++;
                        assertThat(delivery).isEqualTo("SUPPRESSED");
                        assertThat(eventNames(sse)).doesNotContain("assistant_message").contains("notice");
                        assertThat(history(token).body()).doesNotContain("Refunds are accepted within 30 days.");
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(suppressed + deliveredFirst).isEqualTo(rounds);
        // Both orders must actually have happened, or the test proves nothing.
        assertThat(suppressed).isPositive();
        assertThat(deliveredFirst).isPositive();
    }

    @Test
    void aStaleTakeoverIsRefusedAndTheCallerMustLookAgain() throws SQLException {
        String token = token();
        UUID conversation = conversationOf(token);
        send("POST", "/api/v1/public/conversation/handoff", null, token);

        Response stale = takeover(owner, conversation, 1);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.errorCode()).isEqualTo("CONVERSATION_CHANGED");

        Response fresh = takeover(owner, conversation, versionOf(conversation));
        assertThat(fresh.status()).isEqualTo(200);
        // Repeating it as the same person is harmless.
        assertThat(takeover(owner, conversation, 1).status()).isEqualTo(200);
    }

    // --- notes and suggestions -----------------------------------------------------------------

    @Test
    void notesAndUnsentSuggestionsNeverReachAnyPublicEndpoint() throws SQLException {
        String token = token();
        UUID conversation = conversationOf(token);
        customerSays(token, "Refunds?");
        assertThat(takeover(owner, conversation, versionOf(conversation)).status()).isEqualTo(200);

        send("POST", base() + "/conversations/" + conversation + "/notes",
                "{\"body\": \"SECRET-NOTE do not share\"}", owner.accessToken());
        ai.script = (tenantId, assistant, question) -> answered("SECRET-SUGGESTION refunds within 30 days");
        Response suggestion = send("POST", base() + "/conversations/" + conversation + "/suggestions", null, owner.accessToken());
        assertThat(suggestion.status()).as(suggestion.body()).isEqualTo(201);
        assertThat(suggestion.<Boolean>json("$.usable")).isTrue();
        String suggestionId = suggestion.json("$.id");

        Response publicView = history(token);
        Response nextReply = customerSays(token, "Hello?");
        for (Response response : List.of(publicView, nextReply)) {
            assertThat(response.body()).doesNotContain("SECRET-NOTE").doesNotContain("SECRET-SUGGESTION");
        }
        // Staff see both.
        assertThat(get(base() + "/conversations/" + conversation + "/notes", owner).body()).contains("SECRET-NOTE");
        Response staffThread = get(base() + "/conversations/" + conversation + "/messages", owner);
        assertThat(staffThread.<List<String>>json("$.items[*].visibility")).contains("AI_SUGGESTION");

        // Sending the suggestion (edited) creates the agent's own message.
        Response sent = send("POST", base() + "/conversations/" + conversation + "/replies",
                "{\"body\": \"Refunds are accepted within 30 days of delivery.\", \"suggestion_id\": \"" + suggestionId + "\"}",
                owner.accessToken());
        assertThat(sent.status()).as(sent.body()).isEqualTo(201);
        assertThat(history(token).body()).contains("within 30 days of delivery").doesNotContain("SECRET-SUGGESTION");
        assertThat(count("SELECT count(*) FROM messages WHERE source_suggestion_id = ?", UUID.fromString(suggestionId)))
                .isEqualTo(1);
    }

    // --- escalation policy end to end ----------------------------------------------------------

    @Test
    void riskyMessagesEscalateWithoutAskingTheAi() throws SQLException {
        String token = token();
        Response response = customerSays(token, "I am going to speak to my lawyer about this order");

        assertThat(eventNames(response)).containsExactly("customer_message", "system_message", "notice", "done");
        assertThat(ai.questions).isEmpty();
        String ticket = ticketOf(conversationOf(token));
        Response detail = get(base() + "/tickets/" + ticket, owner);
        assertThat(detail.<String>json("$.ticket.reason")).isEqualTo("RISK_RULE");
        assertThat(detail.<String>json("$.ticket.priority")).isEqualTo("HIGH");
    }

    @Test
    void twoUnusableRepliesInARowEscalate() throws SQLException {
        String token = token();
        ai.script = (tenantId, assistant, question) -> outcome("NO_EVIDENCE", null, "no_evidence");

        Response first = customerSays(token, "Do you sell helmets?");
        assertThat(eventNames(first)).containsExactly("customer_message", "assistant_typing", "assistant_message", "done");
        assertThat(ticketOf(conversationOf(token))).isNull();

        Response second = customerSays(token, "Bicycle helmets?");
        assertThat(eventNames(second)).containsExactly(
                "customer_message", "assistant_typing", "assistant_message", "system_message", "notice", "done");
        assertThat(get(base() + "/tickets/" + ticketOf(conversationOf(token)), owner).<String>json("$.ticket.reason"))
                .isEqualTo("REPEATED_MISUNDERSTANDING");
    }

    @Test
    void conflictingSourcesAndServiceErrorsEscalateWithTheirReason() throws SQLException {
        String conflicted = token();
        ai.script = (tenantId, assistant, question) -> new AiAnswer("ESCALATED", "Let me check with a colleague.",
                "question", true, "conflicting_sources",
                List.of(new Citation(chunk, document, "policy.md", null, null, "x")),
                List.of(new RetrievalHit(chunk, document, "policy.md", 0.8, true)),
                "fake", "m", "v", "e", 0.45, 1, 1, 1);
        customerSays(conflicted, "Refund window?");
        assertThat(get(base() + "/tickets/" + ticketOf(conversationOf(conflicted)), owner).<String>json("$.ticket.reason"))
                .isEqualTo("INSUFFICIENT_EVIDENCE");

        String failed = token();
        ai.script = (tenantId, assistant, question) -> {
            throw new AiClient.AiUnavailableException("down", null);
        };
        customerSays(failed, "Refund window?");
        assertThat(get(base() + "/tickets/" + ticketOf(conversationOf(failed)), owner).<String>json("$.ticket.reason"))
                .isEqualTo("SERVICE_ERROR");
        // Ticket numbers run per workspace.
        assertThat(get(base() + "/tickets/" + ticketOf(conversationOf(failed)), owner).<Integer>json("$.ticket.number"))
                .isEqualTo(2);
    }

    // --- access ------------------------------------------------------------------------------

    @Test
    void rolesAndOwnershipAreEnforced() throws SQLException {
        String token = token();
        UUID conversation = conversationOf(token);
        send("POST", "/api/v1/public/conversation/handoff", null, token);
        String ticket = ticketOf(conversation);

        // Agents cannot assign, and cannot take over a conversation that was never escalated.
        assertThat(send("PUT", base() + "/tickets/" + ticket + "/assignee",
                        "{\"membership_id\": \"" + agent.memberId() + "\"}", agent.session().accessToken()).status())
                .isEqualTo(403);
        assertThat(get(base() + "/tickets/assignees", agent.session()).status()).isEqualTo(403);
        String quiet = token();
        customerSays(quiet, "Refunds?");
        assertThat(takeover(agent.session(), conversationOf(quiet), 1).status()).isEqualTo(404);

        // An agent picks up the unassigned ticket by taking over; the other agent then loses sight of it.
        assertThat(takeover(agent.session(), conversation, versionOf(conversation)).status()).isEqualTo(200);
        for (String path : List.of(
                "/tickets/" + ticket,
                "/conversations/" + conversation + "/notes",
                "/conversations/" + conversation + "/messages",
                "/conversations/" + conversation)) {
            assertThat(get(base() + path, otherAgent.session()).status()).as(path).isEqualTo(404);
        }
        assertThat(send("POST", base() + "/tickets/" + ticket + "/resolve", "{\"outcome\": \"SOLVED\"}",
                        otherAgent.session().accessToken()).status())
                .isEqualTo(404);

        // Invalid manual transitions are refused.
        assertThat(send("PATCH", base() + "/tickets/" + ticket, "{\"status\": \"CLOSED\"}", agent.session().accessToken())
                        .errorCode())
                .isEqualTo("INVALID_STATUS");
        assertThat(send("POST", base() + "/tickets/" + ticket + "/close", null, agent.session().accessToken()).errorCode())
                .isEqualTo("TICKET_NOT_RESOLVED");
        assertThat(send("PATCH", base() + "/tickets/" + ticket, "{\"priority\": \"URGENT\"}", agent.session().accessToken())
                        .<String>json("$.ticket.priority"))
                .isEqualTo("URGENT");
    }

    @Test
    void anotherWorkspaceCannotReachTicketsNotesOrConversations() throws SQLException {
        String token = token();
        UUID conversation = conversationOf(token);
        send("POST", "/api/v1/public/conversation/handoff", null, token);
        String ticket = ticketOf(conversation);

        Session stranger = newUser("tickets-stranger");
        UUID strangerWorkspace = createWorkspace(stranger, "Elsewhere");
        String theirs = "/api/v1/workspaces/" + strangerWorkspace;
        String ours = base();

        assertThat(get(ours + "/tickets/" + ticket, stranger).status()).isEqualTo(404);
        assertThat(get(ours + "/tickets", stranger).status()).isEqualTo(404);
        // Our ids through their workspace.
        assertThat(get(theirs + "/tickets/" + ticket, stranger).status()).isEqualTo(404);
        assertThat(get(theirs + "/conversations/" + conversation + "/notes", stranger).status()).isEqualTo(404);
        assertThat(takeover(stranger, conversation, versionOf(conversation)).status()).isEqualTo(404);
        assertThat(send("POST", theirs + "/conversations/" + conversation + "/takeover", "{\"expected_version\": 2}",
                        stranger.accessToken()).status())
                .isEqualTo(404);
        assertThat(send("POST", theirs + "/conversations/" + conversation + "/notes", "{\"body\": \"x\"}",
                        stranger.accessToken()).status())
                .isEqualTo(404);
        assertThat(send("POST", theirs + "/tickets/" + ticket + "/resolve", "{\"outcome\": \"SOLVED\"}",
                        stranger.accessToken()).status())
                .isEqualTo(404);
        assertThat(get(theirs + "/tickets", stranger).<Integer>json("$.total")).isZero();
        assertThat(get(theirs + "/notifications", stranger).<Integer>json("$.unread_count")).isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
