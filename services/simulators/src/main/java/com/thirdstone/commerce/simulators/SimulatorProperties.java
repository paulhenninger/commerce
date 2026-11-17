package com.thirdstone.commerce.simulators;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("commerce.simulators")
record SimulatorProperties(@DefaultValue("") String apiKey, @DefaultValue Carrier carrier) {

    record Carrier(@DefaultValue("5s") Duration dispatchAfter, @DefaultValue("15s") Duration deliverAfter) {
    }
}
