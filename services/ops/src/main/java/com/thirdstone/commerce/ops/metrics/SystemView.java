package com.thirdstone.commerce.ops.metrics;

import com.thirdstone.commerce.ops.OpsProperties;
import com.thirdstone.commerce.ops.incidents.IncidentRepository;
import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assembles the console's pages from Prometheus, Loki and the incident tables. */
@Service
public class SystemView {

    public record ServiceTile(String name, int instances, boolean up, double requestsPerSecond, double errorRatio,
                              double p95Seconds, double lag, double outboxOldestSeconds, int firingIncidents) {
    }

    public record Overview(String environment, List<ServiceTile> services, Map<String, Double> totals,
                           Map<String, Double> funnel, int firingIncidents, Map<String, Double> probes) {
    }

    public record Instance(String instance, boolean up, double uptimeSeconds, double heapBytes) {
    }

    public record ServiceDetail(String name, List<Instance> instances, Map<String, Double> lagByTopic,
                                Map<String, Double> breakers, double outboxPending, double outboxOldestSeconds,
                                Map<String, String> links) {
    }

    private final Prometheus prometheus;
    private final Loki loki;
    private final OpsProperties props;
    private final IncidentRepository incidents;
    private final String environment;

    SystemView(Prometheus prometheus, Loki loki, OpsProperties props, IncidentRepository incidents,
               org.springframework.core.env.Environment env) {
        this.prometheus = prometheus;
        this.loki = loki;
        this.props = props;
        this.incidents = incidents;
        this.environment = env.getProperty("commerce.environment", "dev");
    }

    public String environment() {
        return environment;
    }

    public String checkedService(String name) {
        if (!props.services().contains(name)) {
            throw ApiException.notFound("Service");
        }
        return name;
    }

    public Overview overview() {
        Map<String, Double> up = prometheus.byLabel("count by (service) (up{job=\"services\"} == 1)", "service");
        Map<String, Double> rps = prometheus.byLabel(
                "sum by (service) (rate(http_server_requests_seconds_count{job=\"services\", uri!~\"/actuator.*\"}[1m]))", "service");
        Map<String, Double> errors = prometheus.byLabel(
                "sum by (service) (rate(http_server_requests_seconds_count{job=\"services\", status=~\"5..\"}[5m]))"
                        + " / sum by (service) (rate(http_server_requests_seconds_count{job=\"services\"}[5m]))", "service");
        Map<String, Double> p95 = prometheus.byLabel("histogram_quantile(0.95, sum by (le, service) "
                + "(rate(http_server_requests_seconds_bucket{job=\"services\", uri!~\"/actuator.*\"}[5m])))", "service");
        Map<String, Double> lag = prometheus.byLabel("sum by (group) (commerce_kafka_consumer_lag)", "group");
        Map<String, Double> outbox = prometheus.byLabel("max by (service) (commerce_outbox_oldest_age_seconds)", "service");
        Map<String, Integer> firing = incidents.firingByService();

        List<ServiceTile> tiles = new ArrayList<>();
        for (String name : props.services()) {
            int instances = up.getOrDefault(name, 0.0).intValue();
            tiles.add(new ServiceTile(name, instances, instances > 0, rps.getOrDefault(name, 0.0),
                    errors.getOrDefault(name, Double.NaN), p95.getOrDefault(name, Double.NaN),
                    lag.getOrDefault(name, Double.NaN), outbox.getOrDefault(name, Double.NaN),
                    firing.getOrDefault(name, 0)));
        }
        Map<String, Double> totals = new LinkedHashMap<>();
        totals.put("requestsPerSecond", prometheus.scalar(
                "sum(rate(http_server_requests_seconds_count{job=\"services\", uri!~\"/actuator.*\"}[1m]))"));
        totals.put("errorsPerSecond", prometheus.scalar(
                "sum(rate(http_server_requests_seconds_count{job=\"services\", status=~\"5..\"}[1m])) or vector(0)"));
        totals.put("eventsPerSecond", prometheus.scalar("sum(rate(commerce_events_consumed_total[1m]))"));
        totals.put("deadLetters1h", prometheus.scalar("sum(increase(commerce_events_dead_lettered_total[1h])) or vector(0)"));
        totals.put("ordersInFlight", prometheus.scalar("max(commerce_orders_in_flight)"));
        Map<String, Double> funnel = prometheus.byLabel("sum by (outcome) (increase(commerce_orders_total[15m]))", "outcome");
        Map<String, Double> probes = prometheus.byLabel("min by (target) (commerce_probe_success)", "target");
        return new Overview(environment, tiles, totals, funnel, incidents.firingCount(), probes);
    }

    public ServiceDetail service(String name) {
        checkedService(name);
        List<Instance> instances = new ArrayList<>();
        Map<String, Double> uptime = prometheus.byLabel(
                "max by (instance) (process_uptime_seconds{service=\"" + name + "\"})", "instance");
        Map<String, Double> heap = prometheus.byLabel(
                "sum by (instance) (jvm_memory_used_bytes{service=\"" + name + "\", area=\"heap\"})", "instance");
        for (Prometheus.Sample s : prometheus.query("up{job=\"services\", service=\"" + name + "\"}")) {
            String instance = s.label("instance");
            instances.add(new Instance(instance, s.value() == 1, uptime.getOrDefault(instance, Double.NaN),
                    heap.getOrDefault(instance, Double.NaN)));
        }
        Map<String, Double> lag = prometheus.byLabel("sum by (topic) (commerce_kafka_consumer_lag{group=\"" + name + "\"})", "topic");
        Map<String, Double> breakers = new LinkedHashMap<>();
        for (Prometheus.Sample s : prometheus.query(
                "max by (name, state) (resilience4j_circuitbreaker_state{service=\"" + name + "\"}) == 1")) {
            breakers.put(s.label("name") + ":" + s.label("state"), 1.0);
        }
        Map<String, String> links = new LinkedHashMap<>();
        String grafana = props.grafanaUrl().replaceAll("/+$", "");
        links.put("dashboard", grafana + "/d/commerce-service?var-service=" + name);
        links.put("logs", grafana + "/explore?left=" + java.net.URLEncoder.encode(
                "{\"datasource\":\"loki\",\"queries\":[{\"refId\":\"A\",\"expr\":\"{service=\\\"" + name + "\\\"}\"}]}",
                java.nio.charset.StandardCharsets.UTF_8));
        links.put("traces", grafana + "/explore?left=" + java.net.URLEncoder.encode(
                "{\"datasource\":\"tempo\",\"queries\":[{\"refId\":\"A\",\"queryType\":\"traceql\",\"query\":\"{resource.service.name=\\\""
                        + name + "\\\"}\"}]}", java.nio.charset.StandardCharsets.UTF_8));
        return new ServiceDetail(name, instances, lag, breakers,
                prometheus.scalar("max(commerce_outbox_pending{service=\"" + name + "\"})"),
                prometheus.scalar("max(commerce_outbox_oldest_age_seconds{service=\"" + name + "\"})"), links);
    }

    public List<Prometheus.Series> serviceSeries(String name, String metric, String range) {
        return prometheus.range(Queries.service(metric, checkedService(name)), Queries.range(range));
    }

    public List<Prometheus.Series> businessSeries(String metric, String range) {
        return prometheus.range(Queries.business(metric), Queries.range(range));
    }

    public List<Loki.Line> errors(String name) {
        return loki.errors(checkedService(name), 50, Duration.ofHours(1));
    }

    public Map<String, Object> business() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byStatus", prometheus.byLabel("max by (status) (commerce_orders_by_status)", "status"));
        out.put("lastHour", prometheus.byLabel("sum by (outcome) (increase(commerce_orders_total[1h]))", "outcome"));
        out.put("cancellationReasons", prometheus.byLabel(
                "sum by (reason) (increase(commerce_orders_total{outcome=\"cancelled\"}[1h]))", "reason"));
        out.put("revenueLastHour", prometheus.byLabel("sum by (currency) (increase(commerce_revenue_cents_total[1h])) / 100", "currency"));
        out.put("refundsLastHour", prometheus.byLabel("sum by (currency) (increase(commerce_refunds_cents_total[1h])) / 100", "currency"));
        Map<String, Double> saga = new LinkedHashMap<>();
        for (var q : Map.of("p50", "0.5", "p95", "0.95", "p99", "0.99").entrySet()) {
            saga.put(q.getKey(), prometheus.scalar(
                    "histogram_quantile(" + q.getValue() + ", sum by (le) (rate(commerce_order_saga_seconds_bucket[15m])))"));
        }
        out.put("sagaSeconds", saga);
        out.put("payments", prometheus.byLabel("sum by (outcome) (increase(commerce_payments_total[1h]))", "outcome"));
        out.put("lowestStock", prometheus.byLabel("bottomk(15, max by (sku) (commerce_stock_available))", "sku"));
        return out;
    }
}
