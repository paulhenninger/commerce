package com.thirdstone.commerce.ops;

import com.thirdstone.commerce.platform.data.Ids;
import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.testing.CommerceContainers;
import com.thirdstone.commerce.platform.testing.Edge;
import com.thirdstone.commerce.platform.testing.EventBus;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"commerce.ops.consumer-groups=catalog", "commerce.ops.probes="})
@AutoConfigureMockMvc
class OpsIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CommerceContainers.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired KafkaTemplate<String, byte[]> kafka;
    final EventBus bus = EventBus.shared();
    final EventCodec codec = new EventCodec(JsonMapper.builder().build());

    private static String alert(String status, String fingerprint, String endsAt) {
        return """
                {"version":"4","status":"%1$s","alerts":[{"status":"%1$s","fingerprint":"%2$s",
                 "labels":{"alertname":"ServiceDown","severity":"critical","service":"catalog"},
                 "annotations":{"summary":"catalog has no healthy instance","runbook_url":"docs/operations.md#runbook-service-down"},
                 "startsAt":"2026-11-17T10:00:00Z","endsAt":"%3$s"}]}""".formatted(status, fingerprint, endsAt);
    }

    @Test
    void anAlertBecomesAnIncidentThatCanBeAcknowledgedAndResolves() throws Exception {
        String fingerprint = "fp" + System.nanoTime();
        mvc.perform(post("/internal/alerts").contentType("application/json")
                .content(alert("firing", fingerprint, "0001-01-01T00:00:00Z"))).andExpect(jsonPath("$.recorded").value(1));
        // Alertmanager repeats itself: still one incident.
        mvc.perform(post("/internal/alerts").contentType("application/json")
                .content(alert("firing", fingerprint, "0001-01-01T00:00:00Z")));
        String list = mvc.perform(Edge.admin(get("/api/v1/ops/incidents"), 1).param("status", "FIRING"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = JsonMapper.builder().build().readTree(list).path("items").path(0).path("id").asLong();
        mvc.perform(Edge.admin(get("/api/v1/ops/incidents/" + id), 1))
                .andExpect(jsonPath("$.alertname").value("ServiceDown"))
                .andExpect(jsonPath("$.service").value("catalog"))
                .andExpect(jsonPath("$.status").value("FIRING"));

        mvc.perform(Edge.admin(post("/api/v1/ops/incidents/" + id + "/ack"), 1))
                .andExpect(jsonPath("$.acknowledgedBy").value("user1@example.com"));
        mvc.perform(Edge.admin(post("/api/v1/ops/incidents/" + id + "/ack"), 2)).andExpect(status().isConflict());
        mvc.perform(Edge.admin(post("/api/v1/ops/incidents/" + id + "/notes"), 1).contentType("application/json")
                        .content("{\"note\":\"Restarted catalog\"}"))
                .andExpect(jsonPath("$.notes.length()").value(2))
                .andExpect(jsonPath("$.notes[1].note").value("Restarted catalog"));

        mvc.perform(post("/internal/alerts").contentType("application/json")
                .content(alert("resolved", fingerprint, "2026-11-17T10:05:00Z")));
        mvc.perform(Edge.admin(get("/api/v1/ops/incidents/" + id), 1))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.endsAt").value("2026-11-17T10:05:00Z"));
    }

    @Test
    void theConsoleApiIsForAdministratorsOnly() throws Exception {
        mvc.perform(Edge.user(get("/api/v1/ops/incidents"), 7)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ops/incidents")).andExpect(status().isUnauthorized());
        mvc.perform(Edge.admin(get("/api/v1/ops/testkit/status"), 1)).andExpect(status().isNotFound());
        mvc.perform(Edge.admin(get("/api/v1/ops/config"), 1)).andExpect(jsonPath("$.environment").value("dev"))
                .andExpect(jsonPath("$.testkit").value(false));
    }

    @Test
    void kafkaStateShowsTopicsGroupsAndLag() throws Exception {
        mvc.perform(Edge.admin(get("/api/v1/ops/kafka"), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers.length()").value(1))
                .andExpect(jsonPath("$.topics.length()").value(Topics.ALL.size() * 2))
                .andExpect(jsonPath("$.groups[0].group").value("catalog"));
    }

    @Test
    void deadLettersCanBeReadAndReplayedToTheirTopic() throws Exception {
        String key = Ids.next();
        var envelope = codec.create(InventoryEvents.RESERVED, key, "inventory", new InventoryEvents.Reserved(key));
        ProducerRecord<String, byte[]> dead = new ProducerRecord<>(Topics.dlt(Topics.INVENTORY), key, codec.serialize(envelope));
        dead.headers().add(new RecordHeader("kafka_dlt-original-topic", Topics.INVENTORY.getBytes(StandardCharsets.UTF_8)));
        dead.headers().add(new RecordHeader("kafka_dlt-exception-message", "handler failed".getBytes(StandardCharsets.UTF_8)));
        long offset = kafka.send(dead).get().getRecordMetadata().offset();

        mvc.perform(Edge.admin(get("/api/v1/ops/kafka/dead-letters/" + Topics.dlt(Topics.INVENTORY)), 1))
                .andExpect(jsonPath("$[0].key").value(key))
                .andExpect(jsonPath("$[0].reason").value("handler failed"))
                .andExpect(jsonPath("$[0].originalTopic").value(Topics.INVENTORY))
                .andExpect(jsonPath("$[0].payload").value(org.hamcrest.Matchers.containsString("inventory.reserved")));

        mvc.perform(Edge.admin(post("/api/v1/ops/kafka/dead-letters/" + Topics.dlt(Topics.INVENTORY) + "/replay"), 1)
                        .contentType("application/json").content("{\"partition\":0,\"offset\":" + offset + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value(Topics.INVENTORY))
                .andExpect(jsonPath("$.newOffset").value(greaterThanOrEqualTo(0)));
        // The very same event is back on the topic it came from.
        await().atMost(Duration.ofSeconds(30)).until(() ->
                !bus.events(InventoryEvents.RESERVED, key, InventoryEvents.Reserved.class).isEmpty());
        mvc.perform(Edge.admin(get("/api/v1/ops/incidents"), 1).param("status", "RESOLVED"))
                .andExpect(jsonPath("$.items[0].alertname").value("DeadLetterReplayed"));
    }

    @Test
    void onlyDeadLetterTopicsCanBeBrowsed() throws Exception {
        mvc.perform(Edge.admin(get("/api/v1/ops/kafka/dead-letters/" + Topics.ORDER), 1)).andExpect(status().isNotFound());
        mvc.perform(Edge.admin(get("/api/v1/ops/kafka/dead-letters/__consumer_offsets.dlt"), 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void consoleQueriesAreLimitedToKnownServicesAndMetrics() throws Exception {
        mvc.perform(Edge.admin(get("/api/v1/ops/services/evil\"}or{x=\"/series"), 1).param("metric", "rate"))
                .andExpect(status().isNotFound());
        mvc.perform(Edge.admin(get("/api/v1/ops/services/order/series"), 1).param("metric", "up{}"))
                .andExpect(status().isBadRequest());
    }
}
