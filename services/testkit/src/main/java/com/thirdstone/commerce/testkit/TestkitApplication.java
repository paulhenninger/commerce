package com.thirdstone.commerce.testkit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DEV ONLY. Seeds test data and accounts, runs simulated shoppers through the
 * storefront exactly as browsers would, watches every topic for broken
 * contracts, and runs the problem producers and consumers of the chaos lab.
 * It refuses to start anywhere but dev.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TestkitApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestkitApplication.class, args);
    }
}
