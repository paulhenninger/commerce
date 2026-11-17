package com.thirdstone.commerce.ops.instances;

import com.thirdstone.commerce.ops.incidents.IncidentService;
import com.thirdstone.commerce.ops.kafka.KafkaInspector;
import com.thirdstone.commerce.ops.metrics.Prometheus;
import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every instance of the system, in one list: what Prometheus scrapes, what Kafka
 * says about brokers and group members, and — in DEV — what Docker says about
 * each container, joined on the instance's address. Outside DEV the list is
 * read-only and built from Prometheus and Kafka alone.
 */
@Service
public class InstancesView {

    public record Row(String service, String name, String containerId, String state, String health, boolean scraped,
                      Double uptimeSeconds, Double heapBytes, boolean relayLeader, Map<String, List<Integer>> partitions,
                      Integer brokerId, Boolean activeController, Integer partitionsLed, boolean controllable,
                      String address) {
    }

    public record Instances(boolean control, List<Row> rows) {
    }

    private static final Set<String> ACTIONS = Set.of("start", "stop", "kill", "pause", "unpause", "restart");

    private final Prometheus prometheus;
    private final KafkaInspector kafka;
    private final ControllerClient controller;
    private final IncidentService incidents;

    InstancesView(Prometheus prometheus, KafkaInspector kafka, ControllerClient controller, IncidentService incidents) {
        this.prometheus = prometheus;
        this.kafka = kafka;
        this.controller = controller;
        this.incidents = incidents;
    }

    public Instances instances() {
        // Prometheus: one target per running service instance, keyed by address.
        Map<String, Prometheus.Sample> up = new LinkedHashMap<>();
        for (Prometheus.Sample s : prometheus.query("up{job=~\"services|dev-tools\"}")) {
            up.put(ip(s.label("instance")), s);
        }
        Map<String, Double> uptime = byIp(prometheus.query("process_uptime_seconds{job=~\"services|dev-tools\"}"));
        Map<String, Double> heap = byIp(prometheus.query("sum by (instance) (jvm_memory_used_bytes{area=\"heap\"})"));
        Map<String, Double> leader = byIp(prometheus.query("commerce_outbox_relay_leader"));

        // Kafka: which address holds which partitions, and the brokers.
        Map<String, Map<String, List<Integer>>> partitions = new HashMap<>();
        Map<String, KafkaInspector.Broker> brokers = new HashMap<>();
        try {
            KafkaInspector.Cluster cluster = kafka.cluster();
            for (KafkaInspector.GroupInfo g : cluster.groups()) {
                g.partitionsByMember().forEach((member, parts) -> {
                    String address = member.split(" ")[0].replaceFirst("^/", "");
                    partitions.computeIfAbsent(address, k -> new TreeMap<>()).put(g.group(), parts);
                });
            }
            cluster.brokers().forEach(b -> brokers.put(b.host(), b));
        } catch (ApiException e) {
            // Kafka down: instances still listed, without their partitions.
        }

        List<Row> rows = new ArrayList<>();
        JsonNode containers = controller.instances();
        Set<String> seen = new java.util.HashSet<>();
        if (containers != null) {
            for (JsonNode c : containers) {
                String service = c.path("service").asString();
                List<String> ips = new ArrayList<>();
                c.path("ips").properties().forEach(e -> ips.add(e.getValue().asString()));
                String address = ips.stream().filter(up::containsKey).findFirst()
                        .orElse(ips.stream().filter(partitions::containsKey).findFirst().orElse(ips.isEmpty() ? null : ips.getFirst()));
                if (address != null) {
                    seen.add(address);
                }
                KafkaInspector.Broker broker = brokers.get(service);
                Prometheus.Sample target = address == null ? null : up.get(address);
                rows.add(new Row(service, c.path("name").asString(), c.path("id").asString(), c.path("state").asString(),
                        c.path("health").isNull() ? null : c.path("health").asString(null),
                        target != null && target.value() == 1, uptime.get(address), heap.get(address),
                        leader.getOrDefault(address, 0.0) == 1, partitions.getOrDefault(address, Map.of()),
                        broker == null ? null : broker.id(), broker == null ? null : broker.controller(),
                        broker == null ? null : broker.leaderOf(), c.path("controllable").asBoolean(), address));
            }
        }
        // Outside DEV (or if the controller is away): what Prometheus and Kafka know.
        for (var e : up.entrySet()) {
            if (seen.contains(e.getKey())) {
                continue;
            }
            Prometheus.Sample s = e.getValue();
            rows.add(new Row(s.label("service"), s.label("instance"), null, s.value() == 1 ? "running" : "not answering",
                    null, s.value() == 1, uptime.get(e.getKey()), heap.get(e.getKey()),
                    leader.getOrDefault(e.getKey(), 0.0) == 1, partitions.getOrDefault(e.getKey(), Map.of()), null, null,
                    null, false, e.getKey()));
        }
        if (containers == null) {
            for (KafkaInspector.Broker b : brokers.values()) {
                rows.add(new Row("kafka", b.host(), null, "running", null, false, null, null, false, Map.of(), b.id(),
                        b.controller(), b.leaderOf(), false, b.host()));
            }
        }
        rows.sort(Comparator.comparing(Row::service).thenComparing(Row::name));
        return new Instances(controller.available(), rows);
    }

    public JsonNode act(Caller caller, String id, String action) {
        caller.requireAdmin();
        if (!controller.available()) {
            throw ApiException.notFound("Instance control");
        }
        if (!ACTIONS.contains(action) || !id.matches("^[A-Za-z0-9_.-]{1,64}$")) {
            throw ApiException.notFound("Action");
        }
        JsonNode result = controller.act(id, action, caller.email());
        String name = result.path("name").asString(id);
        incidents.operatorAction(caller.email(), "InstanceControl", result.path("service").asString(null),
                caller.email() + " " + action + " " + name + " (now " + result.path("state").asString("?") + ")");
        return result;
    }

    private static Map<String, Double> byIp(List<Prometheus.Sample> samples) {
        Map<String, Double> out = new HashMap<>();
        samples.forEach(s -> out.put(ip(s.label("instance")), s.value()));
        return out;
    }

    private static String ip(String instance) {
        int colon = instance.lastIndexOf(':');
        return colon > 0 ? instance.substring(0, colon) : instance;
    }
}
