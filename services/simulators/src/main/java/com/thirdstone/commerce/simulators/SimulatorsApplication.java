package com.thirdstone.commerce.simulators;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * DEV ONLY. A fake payment provider and a fake carrier, with switches for
 * latency, errors and outages, so the system's handling of a misbehaving
 * dependency can be watched and proved. It refuses to start anywhere but dev.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SimulatorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorsApplication.class, args);
    }
}
