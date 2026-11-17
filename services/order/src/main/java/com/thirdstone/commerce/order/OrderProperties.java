package com.thirdstone.commerce.order;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("commerce.order")
public record OrderProperties(
        @DefaultValue("2m") Duration sagaTimeout,
        @DefaultValue("30") int returnWindowDays,
        @DefaultValue("5000") int maxInFlight) {
}
