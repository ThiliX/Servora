package com.servora.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.servora.assistant.AssistantService;
import com.servora.assistant.AssistantService.AssistantView;
import com.servora.knowledge.KnowledgeConfig.IngestionProperties;
import com.servora.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/** ADR-022: every call to the AI service carries its own trace context, from any thread. */
class AiClientTracePropagationTest extends IntegrationTestBase {

    @Autowired
    private IngestionProperties tokens;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ObservationRegistry observations;

    @Test
    void eachCallSendsTheTraceparentOfItsOwnSpan() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        List<String> upgrades = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/answers", exchange -> {
            received.add(String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")));
            upgrades.add(String.valueOf(exchange.getRequestHeaders().getFirst("Upgrade")));
            byte[] body = """
                    {"outcome":"NO_EVIDENCE","answer":"x","intent":null,"needs_escalation":true,
                     "escalation_reason":"no_evidence","citations":[],"retrieval":[],"provider":"fake",
                     "model":null,"prompt_version":"p","embedding_model":"m","evidence_threshold":0.35,
                     "latency_ms":1,"input_tokens":0,"output_tokens":0}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            AiClient.AiProperties properties = new AiClient.AiProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/answers",
                    java.time.Duration.ofSeconds(10),
                    10);
            AiClient client = new AiClient.HttpAiClient(properties, tokens, json, observations);
            AssistantView assistant = new AssistantView(
                    UUID.randomUUID(), "A", "Hi", "Fallback", "en", AssistantService.Tone.FRIENDLY, true,
                    AssistantService.ConfidencePolicy.BALANCED, 1, java.time.Instant.now());
            // On a fresh thread with no current span, like the widget's background replies, so each call
            // starts its own trace whatever earlier tests left on the test thread.
            Thread caller = new Thread(() -> {
                for (int i = 0; i < 3; i++) {
                    client.answer(UUID.randomUUID(), assistant, "q" + i);
                }
            });
            caller.start();
            caller.join(30_000);
        } finally {
            server.stop(0);
        }

        assertThat(received).hasSize(3).allMatch(value -> value.matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$"));
        assertThat(received.stream().map(value -> value.substring(3, 35)).distinct()).hasSize(3);
        // No HTTP/2 upgrade attempt: uvicorn loses later requests' headers after one.
        assertThat(upgrades).containsOnly("null");
    }
}
