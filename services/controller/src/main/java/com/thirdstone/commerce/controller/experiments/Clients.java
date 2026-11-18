package com.thirdstone.commerce.controller.experiments;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Every client an experiment uses has timeouts: a run must never hang on one call. */
final class Clients {

    static RestClient.Builder timed(RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return builder.clone().requestFactory(factory);
    }

    private Clients() {
    }
}
