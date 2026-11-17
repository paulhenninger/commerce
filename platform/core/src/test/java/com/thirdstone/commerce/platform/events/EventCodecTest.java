package com.thirdstone.commerce.platform.events;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventCodecTest {

    private final EventCodec codec = new EventCodec(JsonMapper.builder().build());

    private static final String VALID = """
            {"eventId":"e-1","type":"inventory.reserved","version":1,"occurredAt":"2026-11-16T12:00:00Z",
             "source":"inventory","aggregateId":"01JD0000000000000000000001","payload":{"orderId":"01JD0000000000000000000001"}}""";

    /** The fixtures are the contract: a change that breaks one breaks a deployed consumer. */
    @Test
    void everyFixtureOfEveryEventTypeIsAccepted() throws IOException, URISyntaxException {
        Path root = Path.of(getClass().getResource("/contracts").toURI());
        Set<String> seen = new HashSet<>();
        List<Path> fixtures;
        try (Stream<Path> files = Files.walk(root)) {
            fixtures = files.filter(p -> p.toString().endsWith(".json")).toList();
        }
        for (Path fixture : fixtures) {
            EventEnvelope event = codec.parse(Files.readAllBytes(fixture));
            assertThat(event.type()).as(fixture.toString()).isEqualTo(fixture.getParent().getFileName().toString());
            Class<?> type = Contract.PAYLOADS.get(event.type());
            assertThat(type).as("payload class for " + event.type()).isNotNull();
            assertThat(codec.payload(event, type)).isNotNull();
            assertThat(Contract.topicOf(event.type())).isIn(Topics.ALL);
            seen.add(event.type());
        }
        assertThat(seen).as("every event type has a fixture").isEqualTo(Contract.PAYLOADS.keySet());
    }

    @Test
    void whatIsWrittenCanBeReadBack() {
        EventEnvelope written = codec.create(InventoryEvents.STOCK_CHANGED, "SKU-1", "inventory",
                new InventoryEvents.StockChanged("SKU-1", 7));
        EventEnvelope read = codec.parse(codec.serialize(written));
        assertThat(read.eventId()).isEqualTo(written.eventId());
        assertThat(read.source()).isEqualTo("inventory");
        assertThat(codec.payload(read, InventoryEvents.StockChanged.class))
                .isEqualTo(new InventoryEvents.StockChanged("SKU-1", 7));
    }

    @Test
    void unknownFieldsAreIgnoredSoProducersCanAddThem() {
        String json = VALID.replace("\"version\":1", "\"version\":1,\"shard\":\"eu\"")
                .replace("{\"orderId\"", "{\"note\":\"new\",\"orderId\"");
        EventEnvelope event = codec.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(codec.payload(event, InventoryEvents.Reserved.class).orderId())
                .isEqualTo("01JD0000000000000000000001");
    }

    @Test
    void emptyRecordIsRejected() {
        assertInvalid(new byte[0]);
        assertThatThrownBy(() -> codec.parse(null)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void bytesThatAreNotJsonAreRejected() {
        assertInvalid("\u0000\u0001 not json".getBytes(StandardCharsets.UTF_8));
        assertInvalid("[1,2,3]".getBytes(StandardCharsets.UTF_8));
        assertInvalid("null".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void envelopeMissingAFieldIsRejected() {
        assertInvalid(VALID.replace("\"eventId\":\"e-1\",", ""));
        assertInvalid(VALID.replace("\"occurredAt\":\"2026-11-16T12:00:00Z\",", ""));
        assertInvalid(VALID.replace("\"aggregateId\":\"01JD0000000000000000000001\",", ""));
        assertInvalid(VALID.replace("\"version\":1,", ""));
    }

    @Test
    void typeThatIsNotALowercaseDottedNameIsRejected() {
        assertInvalid(VALID.replace("inventory.reserved", "Inventory Reserved"));
        assertInvalid(VALID.replace("inventory.reserved", "reserved"));
        assertInvalid(VALID.replace("inventory.reserved", "a." + "b".repeat(80)));
    }

    @Test
    void idsThatCouldCarryInjectionAreRejected() {
        assertInvalid(VALID.replace("\"e-1\"", "\"e 1\\n{}\""));
        assertInvalid(VALID.replace("\"aggregateId\":\"01JD0000000000000000000001\"",
                "\"aggregateId\":\"" + "x".repeat(65) + "\""));
    }

    @Test
    void oversizedRecordIsRejectedBeforeParsing() {
        byte[] huge = VALID.replace("\"e-1\"", "\"" + "x".repeat(EventCodec.MAX_BYTES) + "\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> codec.parse(huge)).isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void payloadThatIsNotAnObjectIsRejected() {
        assertInvalid(VALID.replace("{\"orderId\":\"01JD0000000000000000000001\"}", "\"text\""));
        assertInvalid(VALID.replace("{\"orderId\":\"01JD0000000000000000000001\"}", "[1]"));
        assertInvalid(VALID.replace("{\"orderId\":\"01JD0000000000000000000001\"}", "null"));
    }

    @Test
    void payloadMissingARequiredFieldBreaksItsContract() {
        EventEnvelope event = codec.parse(VALID.replace("\"orderId\"", "\"order\"").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.payload(event, InventoryEvents.Reserved.class))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("contract");
    }

    @Test
    void payloadWithAWrongTypeBreaksItsContract() {
        EventEnvelope event = codec.parse(VALID.replace("\"01JD0000000000000000000001\"}", "{\"a\":1}}")
                .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.payload(event, InventoryEvents.Reserved.class))
                .isInstanceOf(InvalidEventException.class);
    }

    private void assertInvalid(String json) {
        assertInvalid(json.getBytes(StandardCharsets.UTF_8));
    }

    private void assertInvalid(byte[] bytes) {
        assertThatThrownBy(() -> codec.parse(bytes)).isInstanceOf(InvalidEventException.class);
    }
}
