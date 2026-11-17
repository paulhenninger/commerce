package com.thirdstone.commerce.ops.kafka;

import com.thirdstone.commerce.ops.OpsProperties;
import com.thirdstone.commerce.ops.incidents.IncidentService;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Puts a dead letter back on the topic it came from, for its consumer to try
 * again once the cause is fixed. The only write the ops service makes to an
 * event topic (its ACL allows it, audited here): the record is re-published
 * exactly as it was — same key, same bytes, same trace — so a consumer that did
 * apply it after all simply skips it as a duplicate.
 */
@Service
public class DeadLetterReplay {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplay.class);

    public record Replayed(String topic, int partition, long offset, String to, long newOffset) {
    }

    private final KafkaInspector inspector;
    private final KafkaTemplate<String, byte[]> kafka;
    private final IncidentService incidents;
    private final OpsProperties props;

    DeadLetterReplay(KafkaInspector inspector, KafkaTemplate<String, byte[]> kafka, IncidentService incidents,
                     OpsProperties props) {
        this.inspector = inspector;
        this.kafka = kafka;
        this.incidents = incidents;
        this.props = props;
    }

    public Replayed replay(Caller caller, String topic, int partition, long offset) {
        caller.requireAdmin();
        if (props.replayRequiresMfa() && !caller.strongAuth()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Replaying events needs a two-factor sign-in",
                    "ADMIN_MFA_REQUIRED");
        }
        ConsumerRecord<String, byte[]> dead = inspector.read(topic, partition, offset);
        String original = topic.substring(0, topic.length() - Topics.DLT_SUFFIX.length());
        ProducerRecord<String, byte[]> again = new ProducerRecord<>(original, dead.key(), dead.value());
        for (String name : new String[]{"traceparent", "event-type"}) {
            String value = KafkaInspector.header(dead, name);
            if (value != null) {
                again.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
            }
        }
        again.headers().add(new RecordHeader("commerce-replayed-from",
                (topic + "/" + partition + "/" + offset).getBytes(StandardCharsets.UTF_8)));
        long newOffset;
        try {
            newOffset = kafka.send(again).get(10, TimeUnit.SECONDS).getRecordMetadata().offset();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("Interrupted");
        } catch (Exception e) {
            throw ApiException.unavailable("Could not publish to " + original);
        }
        String what = "Replayed dead letter " + topic + "/" + partition + "/" + offset + " (key " + dead.key()
                + ") to " + original + " at offset " + newOffset;
        incidents.operatorAction(caller.email(), "DeadLetterReplayed", original, what);
        log.atWarn().addKeyValue("actor", caller.userId()).addKeyValue("topic", topic).addKeyValue("offset", offset)
                .log("Dead letter replayed");
        return new Replayed(topic, partition, offset, original, newOffset);
    }
}
