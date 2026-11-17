package com.thirdstone.commerce.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("commerce.mail")
public record MailProperties(String from, String shopUrl) {

    public MailProperties {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("commerce.mail.from (MAIL_FROM) is not set");
        }
        shopUrl = shopUrl == null ? "" : shopUrl.replaceAll("/+$", "");
    }
}
