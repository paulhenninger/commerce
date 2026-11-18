package com.thirdstone.commerce.controller.experiments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("commerce.experiments")
public record ExperimentProperties(String prometheusUrl, String testkitUrl, String storefrontUrl, String adminEmail,
                                   String adminPassword, @DefaultValue("/var/lib/commerce") String stateDir,
                                   @DefaultValue("2m") Duration sagaTimeout) {
}
