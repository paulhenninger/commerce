package com.thirdstone.commerce.shipping;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("commerce")
public record ShippingProperties(Carrier carrier, @DefaultValue Shipping shipping) {

    public record Carrier(String url, String apiKey, @DefaultValue("1s") Duration connectTimeout,
                          @DefaultValue("3s") Duration readTimeout) {
        public Carrier {
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("commerce.carrier.url (CARRIER_URL) is not set");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("commerce.carrier.api-key (CARRIER_KEY) is not set");
            }
        }
    }

    public record Shipping(@DefaultValue("2s") Duration pollInterval) {
    }
}
