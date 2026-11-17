package com.thirdstone.commerce.testkit.chaos;

import com.thirdstone.commerce.platform.events.EventCodec;
import com.thirdstone.commerce.testkit.kafka.KafkaClients;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Starts and stops the problem actors. Capped so the lab cannot exhaust a developer's machine by accident. */
@Component
public class ChaosLab implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChaosLab.class);
    static final int MAX_ACTORS = 10;
    static final double MAX_RATE = 2000;

    public record Start(String kind, String type, String topic, Double ratePerSecond) {
    }

    private final KafkaClients clients;
    private final EventCodec codec;
    private final Map<String, Actor> actors = new LinkedHashMap<>();
    private final AtomicInteger next = new AtomicInteger();
    private final MultiGauge records;

    ChaosLab(KafkaClients clients, EventCodec codec, MeterRegistry registry) {
        this.clients = clients;
        this.codec = codec;
        this.records = MultiGauge.builder("commerce.testkit.actor.records").register(registry);
    }

    public synchronized List<Actor.View> list() {
        return actors.values().stream().map(Actor::view).toList();
    }

    public synchronized Actor.View start(Start s) {
        actors.values().removeIf(a -> !a.running);
        if (actors.size() >= MAX_ACTORS) {
            throw new IllegalArgumentException("At most " + MAX_ACTORS + " actors at once; stop one first");
        }
        double rate = s.ratePerSecond() == null ? 10 : s.ratePerSecond();
        if (rate <= 0 || rate > MAX_RATE) {
            throw new IllegalArgumentException("ratePerSecond is between 0 and " + MAX_RATE);
        }
        String id = String.valueOf(next.incrementAndGet());
        Actor actor;
        if ("producer".equals(s.kind())) {
            if ("UNAUTHORISED".equals(s.type())) {
                actor = new Intruder(id, s.topic(), Math.min(rate, 2), clients);
            } else {
                ProblemProducer.Type type = ProblemProducer.Type.valueOf(s.type());
                ProblemProducer.checkTopic(type, s.topic());
                // An oversized record is 2 MiB: a handful a second is plenty.
                actor = new ProblemProducer(id, type, s.topic(), type == ProblemProducer.Type.OVERSIZED ? Math.min(rate, 5) : rate,
                        clients, codec);
            }
        } else if ("consumer".equals(s.kind())) {
            actor = new ProblemConsumer(id, ProblemConsumer.Type.valueOf(s.type()), s.topic(), clients);
        } else {
            throw new IllegalArgumentException("kind is producer or consumer");
        }
        actors.put(id, actor);
        Thread.ofPlatform().daemon(true).name("chaos-" + id).start(actor);
        log.atWarn().addKeyValue("actor", id).addKeyValue("type", s.type()).addKeyValue("topic", s.topic())
                .addKeyValue("rate", rate).log("Chaos actor started");
        return actor.view();
    }

    public synchronized boolean stop(String id) {
        Actor actor = actors.remove(id);
        if (actor == null) {
            return false;
        }
        actor.stop();
        log.atWarn().addKeyValue("actor", id).log("Chaos actor stopped");
        return true;
    }

    public synchronized void stopAll() {
        actors.values().forEach(Actor::stop);
        actors.clear();
    }

    @Override
    public void destroy() {
        stopAll();
    }

    @Scheduled(fixedDelay = 5000)
    void export() {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (Actor.View v : list()) {
            v.counts().forEach((what, n) -> rows.add(MultiGauge.Row.of(
                    Tags.of("actor", v.id(), "type", v.type(), "outcome", what), n)));
        }
        records.register(rows, true);
    }
}
