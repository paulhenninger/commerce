package com.thirdstone.commerce.platform.ops;

import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start a service outside DEV that has test tooling switched on.
 *
 * <p>Only the DEV deployment has test data, simulated shoppers, the simulated payment
 * provider and carrier, the mail catcher and the chaos tooling. INT, QA and PROD
 * contain none of it: those containers are absent from their Compose files, and CI
 * proves it (scripts/verify-clean-deployments.sh). This is the second line: a service
 * told it runs anywhere but dev checks its own settings at startup and refuses,
 * naming every problem, if any of them points at a test facility. A misconfigured
 * service fails its rollout instead of quietly charging a fake payment provider.
 */
public class TestToolingGuard {

    /** Hosts that only exist in DEV. */
    private static final List<String> TEST_HOSTS = List.of("simulators", "testkit", "mailpit");

    /** Settings that name where a service sends money, parcels, mail or test traffic. */
    private static final List<String> CHECKED = List.of(
            "commerce.payment.gateway-url", "commerce.carrier.url", "commerce.testkit.url",
            "spring.mail.host");

    public TestToolingGuard(Environment env) {
        String environment = env.getProperty("commerce.environment", "dev");
        if ("dev".equalsIgnoreCase(environment)) {
            return;
        }
        List<String> problems = problems(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start in '" + environment
                    + "' with test tooling configured (it exists only in dev):\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    static List<String> problems(Environment env) {
        List<String> problems = new ArrayList<>();
        for (String key : CHECKED) {
            String value = env.getProperty(key, "");
            for (String host : TEST_HOSTS) {
                if (value.equals(host) || value.contains("//" + host + ":") || value.contains("//" + host + "/")
                        || value.endsWith("//" + host)) {
                    problems.add(key + " points at the test facility '" + host + "' (" + value + ")");
                }
            }
        }
        for (String profile : env.getActiveProfiles()) {
            if (profile.equals("testdata") || profile.equals("chaos")) {
                problems.add("Spring profile '" + profile + "' is active");
            }
        }
        return problems;
    }
}
