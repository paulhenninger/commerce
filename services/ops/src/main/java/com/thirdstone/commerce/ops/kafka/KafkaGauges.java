package com.thirdstone.commerce.ops.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kafka's state as metrics, so Prometheus can alert on lag, missing brokers and
 * under-replication: the services cannot see their own group's lag, and nothing
 * else in the stack talks to Kafka's admin API.
 */
@Component
class KafkaGauges {

    private static final Logger log = LoggerFactory.getLogger(KafkaGauges.class);

    private final KafkaInspector inspector;
    private final MultiGauge lag;
    private final MultiGauge endOffsets;
    private final AtomicInteger brokers = new AtomicInteger(-1);
    private final AtomicInteger underReplicated = new AtomicInteger();

    KafkaGauges(KafkaInspector inspector, MeterRegistry registry) {
        this.inspector = inspector;
        this.lag = MultiGauge.builder("commerce.kafka.consumer.lag").register(registry);
        this.endOffsets = MultiGauge.builder("commerce.kafka.topic.end.offset").register(registry);
        registry.gauge("commerce.kafka.brokers", brokers);
        registry.gauge("commerce.kafka.under.replicated.partitions", underReplicated);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 5000)
    void refresh() {
        try {
            KafkaInspector.Cluster cluster = inspector.cluster();
            brokers.set(cluster.brokers().size());
            underReplicated.set(cluster.topics().stream().mapToInt(KafkaInspector.TopicInfo::underReplicated).sum());
            List<MultiGauge.Row<?>> lagRows = new ArrayList<>();
            cluster.groups().forEach(g -> g.lagByTopic().forEach((topic, value) ->
                    lagRows.add(MultiGauge.Row.of(Tags.of("group", g.group(), "topic", topic), value))));
            lag.register(lagRows, true);
            endOffsets.register(cluster.topics().stream()
                    .map(t -> MultiGauge.Row.of(Tags.of("topic", t.name()), t.endOffset())).toList(), true);
        } catch (RuntimeException e) {
            // Kafka down: zero brokers is the truth, and KafkaBrokerDown fires on it.
            brokers.set(0);
            log.atWarn().log("Could not read Kafka's state: {}", e.getMessage());
        }
    }
}
