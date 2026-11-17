package com.thirdstone.commerce.controller;

import java.util.Set;

/**
 * Which services may be acted on. An allowlist, plus a denylist for the ones
 * that must never be: the controller itself and the proxy (or nobody could undo
 * anything), the ops service and its edge (or nobody could see it), and the
 * observability stack (or nobody could tell what happened).
 */
public final class InstancePolicy {

    static final Set<String> NEVER = Set.of("controller", "docker-proxy", "ops", "ops-web", "prometheus",
            "alertmanager", "loki", "tempo", "alloy", "grafana", "mysqld-exporter", "mysql-init", "kafka-init");

    static final Set<String> ALLOWED = Set.of("identity", "catalog", "inventory", "order", "billing", "shipping",
            "notification", "storefront-web", "kafka-1", "kafka-2", "kafka-3", "mysql", "simulators");

    public static final Set<String> ACTIONS = Set.of("start", "stop", "kill", "pause", "unpause", "restart");

    public static boolean controllable(String service) {
        return service != null && ALLOWED.contains(service) && !NEVER.contains(service);
    }

    private InstancePolicy() {
    }
}
