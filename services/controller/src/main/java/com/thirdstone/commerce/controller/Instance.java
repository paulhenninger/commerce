package com.thirdstone.commerce.controller;

import java.util.Map;

/**
 * One container of this deployment, as the console shows it.
 *
 * @param ips       its address on each network (to match it with Prometheus
 *                  targets and Kafka group members)
 * @param controllable whether the controller would act on it
 */
public record Instance(String id, String name, String service, int number, String state, String status,
                       String health, long createdAt, Map<String, String> ips, boolean controllable) {
}
