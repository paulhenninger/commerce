package com.thirdstone.commerce.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * DEV ONLY. Starts, stops, kills, pauses and restarts the instances of this
 * deployment on request from the ops console, so resilience can be watched and
 * proved. Acts only on an allowlist of services, never on itself or on what
 * watches the system. Refuses to start anywhere but dev.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControllerApplication.class, args);
    }
}
