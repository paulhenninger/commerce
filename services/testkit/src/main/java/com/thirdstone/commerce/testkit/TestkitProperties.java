package com.thirdstone.commerce.testkit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("commerce.testkit")
public record TestkitProperties(String storefrontUrl, String mailpitUrl, String simulatorsUrl, String adminEmail,
                                String adminPassword, String shopperPassword, Kafka kafka,
                                @DefaultValue("0") int shoppersAtStart) {

    public record Kafka(String bootstrap, @DefaultValue("SASL_PLAINTEXT") String securityProtocol,
                        String readerPassword, String chaosPassword, String intruderPassword) {
    }
}
