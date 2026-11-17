package com.thirdstone.commerce.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("commerce.payment")
public record PaymentProperties(
        String gatewayUrl,
        String apiKey,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout) {

    public PaymentProperties {
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            throw new IllegalStateException("commerce.payment.gateway-url (PAYMENT_GATEWAY_URL) is not set");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("commerce.payment.api-key (PAYMENT_GATEWAY_KEY) is not set");
        }
    }
}
