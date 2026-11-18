package com.thirdstone.commerce.controller.experiments;

import com.thirdstone.commerce.controller.Instance;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What an experiment can do and see while it runs. */
public class Context {

    final Run run;
    final Prometheus prometheus;
    final Testkit testkit;
    final Shop shop;
    final Instances instances;
    /** Values experiments record during the window, for their own verdict. */
    final Map<String, Object> notes = new ConcurrentHashMap<>();
    Instant windowStart;
    Instant windowEnd;

    Context(Run run, Prometheus prometheus, Testkit testkit, Shop shop, Instances instances) {
        this.run = run;
        this.prometheus = prometheus;
        this.testkit = testkit;
        this.shop = shop;
        this.instances = instances;
    }

    void log(String text) {
        run.log(text);
    }

    List<Instance> of(String service) {
        return instances.of(service);
    }

    void act(Instance instance, String action) {
        instances.act(instance, action);
        log(action + " " + instance.name());
    }

    /** Sampled every couple of seconds during the window (maximum kept). */
    void sampleMax(String key, String promql) {
        double v = prometheus.scalar(promql);
        notes.merge(key, v, (a, b) -> Math.max((Double) a, (Double) b));
    }

    double noted(String key) {
        Object v = notes.get(key);
        return v == null ? 0 : (Double) v;
    }

    List<JsonNode> journal() {
        List<JsonNode> out = new ArrayList<>();
        testkit.journal(windowStart, windowEnd).forEach(out::add);
        return out;
    }
}
