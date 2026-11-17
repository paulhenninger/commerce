package com.thirdstone.commerce.testkit.kafka;

import com.thirdstone.commerce.testkit.TestkitProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka clients for the testkit's three principals: {@code testkit} reads
 * everything, {@code testkit-chaos} may write every event topic (a trusted
 * producer gone wrong), {@code intruder} may do nothing at all.
 */
@Component
public class KafkaClients {

    public enum Principal { READER, CHAOS, INTRUDER }

    private final TestkitProperties.Kafka kafka;

    KafkaClients(TestkitProperties props) {
        this.kafka = props.kafka();
    }

    public Map<String, Object> base(Principal principal) {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", kafka.bootstrap());
        config.put("security.protocol", kafka.securityProtocol());
        if (kafka.securityProtocol().startsWith("SASL")) {
            String user = switch (principal) {
                case READER -> "testkit";
                case CHAOS -> "testkit-chaos";
                case INTRUDER -> "intruder";
            };
            String password = switch (principal) {
                case READER -> kafka.readerPassword();
                case CHAOS -> kafka.chaosPassword();
                case INTRUDER -> kafka.intruderPassword();
            };
            config.put("sasl.mechanism", "SCRAM-SHA-512");
            config.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\""
                    + user + "\" password=\"" + password + "\";");
        }
        return config;
    }

    public KafkaProducer<String, byte[]> producer(Principal principal, Map<String, Object> extra) {
        Map<String, Object> config = base(principal);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        config.putAll(extra);
        return new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    public KafkaConsumer<String, byte[]> consumer(Principal principal, String group, Map<String, Object> extra) {
        Map<String, Object> config = base(principal);
        if (group != null) {
            config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        }
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.putAll(extra);
        return new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer());
    }
}
