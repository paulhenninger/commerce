package com.thirdstone.commerce.testkit.kafka;

import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContractTapTest {

    @Test
    void contractTapTellsChaosFromRealViolations() {
        EventCodec codec = new EventCodec(JsonMapper.builder().build());
        var tap = new ContractTap(null, codec,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var good = new ConsumerRecord<String, byte[]>(Topics.INVENTORY, 0, 0, "k",
                codec.serialize(codec.create(InventoryEvents.RESERVED, "k", "inventory", new InventoryEvents.Reserved("k"))));
        var bad = new ConsumerRecord<String, byte[]>(Topics.INVENTORY, 0, 1, "k", "garbage".getBytes(StandardCharsets.UTF_8));
        var chaos = new ConsumerRecord<String, byte[]>(Topics.INVENTORY, 0, 2, "k", "garbage".getBytes(StandardCharsets.UTF_8));
        chaos.headers().add(new RecordHeader("commerce-chaos", "MALFORMED".getBytes(StandardCharsets.UTF_8)));
        var wrongTopic = new ConsumerRecord<String, byte[]>(Topics.ORDER, 0, 3, "k", good.value());
        tap.check(good);
        tap.check(bad);
        tap.check(chaos);
        tap.check(wrongTopic);
        assertThat(tap.violations()).containsEntry("real " + Topics.INVENTORY + " unparseable", 1L)
                .containsEntry("chaos " + Topics.INVENTORY + " unparseable", 1L)
                .containsEntry("real " + Topics.ORDER + " inventory.reserved", 1L)
                .hasSize(3);
        // Only the real, valid record is kept for the DUPLICATE producer to replay, byte for byte.
        assertThat(tap.recent(Topics.INVENTORY, 10)).singleElement()
                .satisfies(r -> assertThat(r.value()).isEqualTo(good.value()));
        assertThat(tap.recent(Topics.ORDER, 10)).isEmpty();
    }
}
