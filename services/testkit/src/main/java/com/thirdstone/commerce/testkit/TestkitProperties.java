package com.thirdstone.commerce.testkit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param stateDir where the simulation switches are kept (a volume in Compose),
 *                 so a restart keeps what was switched on (ADR-033)
 */
@ConfigurationProperties("commerce.testkit")
public record TestkitProperties(String storefrontUrl, String mailpitUrl, String simulatorsUrl, String adminEmail,
                                String adminPassword, String shopperPassword, Kafka kafka,
                                @DefaultValue("/var/lib/commerce") String stateDir) {

    public record Kafka(String bootstrap, @DefaultValue("SASL_PLAINTEXT") String securityProtocol,
                        String readerPassword, String chaosPassword, String intruderPassword) {
    }
}
