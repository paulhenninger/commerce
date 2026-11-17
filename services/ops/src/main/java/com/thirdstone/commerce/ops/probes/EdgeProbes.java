package com.thirdstone.commerce.ops.probes;

import com.thirdstone.commerce.ops.OpsProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Is the site up for a customer?", measured from outside the services: every
 * 15 s, fetch a public page of each edge as a browser would.
 */
@Component
class EdgeProbes {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Map<String, String> targets;
    private final MultiGauge success;
    private final MultiGauge seconds;

    EdgeProbes(OpsProperties props, MeterRegistry registry) {
        this.targets = props.probeTargets();
        this.success = MultiGauge.builder("commerce.probe.success").register(registry);
        this.seconds = MultiGauge.builder("commerce.probe").baseUnit("seconds").register(registry);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    void probe() {
        List<MultiGauge.Row<?>> ok = new ArrayList<>();
        List<MultiGauge.Row<?>> took = new ArrayList<>();
        targets.forEach((name, url) -> {
            long start = System.nanoTime();
            boolean up;
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
                up = response.statusCode() == 200;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                up = false;
            }
            ok.add(MultiGauge.Row.of(Tags.of("target", name), up ? 1 : 0));
            took.add(MultiGauge.Row.of(Tags.of("target", name), (System.nanoTime() - start) / 1e9));
        });
        success.register(ok, true);
        seconds.register(took, true);
    }
}
