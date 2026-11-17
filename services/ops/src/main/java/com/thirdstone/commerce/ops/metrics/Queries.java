package com.thirdstone.commerce.ops.metrics;

import com.thirdstone.commerce.platform.web.ApiException;

import java.time.Duration;
import java.util.Map;

/**
 * The only PromQL the console can cause to run: a fixed template per metric key,
 * with a service name that has already been checked against the known services.
 */
public final class Queries {

    private static final Map<String, String> SERVICE_SERIES = Map.ofEntries(
            Map.entry("rate", "sum(rate(http_server_requests_seconds_count{service=\"%s\", uri!~\"/actuator.*\"}[1m]))"),
            Map.entry("errors", "sum(rate(http_server_requests_seconds_count{service=\"%s\", status=~\"5..\"}[1m]))"),
            Map.entry("p95", "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service=\"%s\", uri!~\"/actuator.*\"}[5m])))"),
            Map.entry("heap", "sum by (instance) (jvm_memory_used_bytes{service=\"%s\", area=\"heap\"})"),
            Map.entry("dbpool", "sum by (state) (label_replace(hikaricp_connections_active{service=\"%1$s\"}, \"state\", \"active\", \"\", \"\") or label_replace(hikaricp_connections_pending{service=\"%1$s\"}, \"state\", \"waiting\", \"\", \"\"))"),
            Map.entry("threads", "sum by (instance) (jvm_threads_live_threads{service=\"%s\"})"),
            Map.entry("events", "sum by (outcome) (rate(commerce_events_consumed_total{service=\"%s\"}[1m]))"),
            Map.entry("lag", "sum by (topic) (commerce_kafka_consumer_lag{group=\"%s\"})"),
            Map.entry("outbox", "max(commerce_outbox_pending{service=\"%s\"})"));

    private static final Map<String, String> BUSINESS_SERIES = Map.ofEntries(
            Map.entry("orders", "sum by (outcome) (rate(commerce_orders_total[5m])) * 60"),
            Map.entry("cancellations", "sum by (reason) (rate(commerce_orders_total{outcome=\"cancelled\"}[5m])) * 60"),
            Map.entry("saga", "histogram_quantile(0.95, sum by (le) (rate(commerce_order_saga_seconds_bucket[5m])))"),
            Map.entry("revenue", "sum(rate(commerce_revenue_cents_total[5m])) * 60 / 100"),
            Map.entry("events", "sum by (type) (rate(commerce_events_consumed_total{outcome=\"processed\"}[1m]))"));

    private static final Map<String, Duration> RANGES = Map.of("15m", Duration.ofMinutes(15), "1h", Duration.ofHours(1),
            "6h", Duration.ofHours(6), "24h", Duration.ofHours(24));

    public static String service(String metric, String service) {
        String template = SERVICE_SERIES.get(metric);
        if (template == null) {
            throw ApiException.badRequest("Unknown metric; one of " + SERVICE_SERIES.keySet());
        }
        return template.formatted(service);
    }

    public static String business(String metric) {
        String query = BUSINESS_SERIES.get(metric);
        if (query == null) {
            throw ApiException.badRequest("Unknown metric; one of " + BUSINESS_SERIES.keySet());
        }
        return query;
    }

    public static Duration range(String range) {
        Duration d = RANGES.get(range == null ? "1h" : range);
        if (d == null) {
            throw ApiException.badRequest("Range is one of " + RANGES.keySet());
        }
        return d;
    }

    private Queries() {
    }
}
