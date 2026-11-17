package com.thirdstone.commerce.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("commerce.ops")
public record OpsProperties(String prometheusUrl, String lokiUrl, String grafanaUrl, List<String> services,
                            List<String> consumerGroups, @DefaultValue("") List<String> probes,
                            @DefaultValue("true") boolean replayRequiresMfa) {

    /** name → URL, from "name=url" entries. */
    public Map<String, String> probeTargets() {
        Map<String, String> targets = new LinkedHashMap<>();
        for (String probe : probes) {
            int eq = probe.indexOf('=');
            if (eq > 0) {
                targets.put(probe.substring(0, eq).strip(), probe.substring(eq + 1).strip());
            }
        }
        return targets;
    }
}
