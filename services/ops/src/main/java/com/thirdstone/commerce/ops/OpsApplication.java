package com.thirdstone.commerce.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The monitoring site's API: what state the system and each part of it are in,
 * Kafka and its dead letters, incidents. It observes; the one change it can make
 * is to replay a dead letter.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
    }
}
