package com.thirdstone.commerce.testkit.shop;

import com.thirdstone.commerce.testkit.TestkitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accounts exactly as a person makes them: sign up, open the verification email
 * (in Mailpit), follow the link, sign in. Nothing is inserted into a database.
 */
@Component
public class Accounts {

    private static final Logger log = LoggerFactory.getLogger(Accounts.class);
    private static final Pattern TOKEN = Pattern.compile("verify-email#token=([A-Za-z0-9_-]+)");

    private final Storefront storefront;
    private final RestClient mailpit;

    Accounts(Storefront storefront, RestClient.Builder builder, TestkitProperties props) {
        this.storefront = storefront;
        this.mailpit = builder.clone().baseUrl(props.mailpitUrl()).build();
    }

    /** An access token for the account, creating and verifying it first if it does not exist. */
    public String signIn(String email, String password, String ip) {
        Storefront.Answer login = storefront.post("/api/v1/auth/login", ip, null,
                Map.of("email", email, "password", password));
        if (login.ok() && login.text("accessToken") != null) {
            return login.text("accessToken");
        }
        if (login.status() == 429) {
            throw new IllegalStateException("Rate limited signing in " + email);
        }
        Storefront.Answer signup = storefront.post("/api/v1/auth/signup", ip, null,
                Map.of("email", email, "password", password));
        if (!signup.ok()) {
            throw new IllegalStateException("Sign-up of " + email + " refused: " + signup.status() + " " + signup.body());
        }
        String token = verificationToken(email);
        Storefront.Answer verified = storefront.post("/api/v1/auth/verify-email", ip, null,
                Map.of("token", token, "password", password));
        if (!verified.ok()) {
            throw new IllegalStateException("Verification of " + email + " refused: " + verified.status());
        }
        log.atInfo().addKeyValue("account", email).log("Test account created through the real sign-up flow");
        return verified.text("accessToken");
    }

    private String verificationToken(String email) {
        for (int attempt = 0; attempt < 60; attempt++) {
            JsonNode found = mailpit.get().uri(u -> u.path("/api/v1/search").queryParam("query", "to:\"{e}\"")
                    .queryParam("limit", 5).build(email)).retrieve().body(JsonNode.class);
            if (found != null) {
                for (JsonNode message : found.path("messages")) {
                    JsonNode full = mailpit.get().uri("/api/v1/message/{id}", message.path("ID").asString())
                            .retrieve().body(JsonNode.class);
                    if (full != null) {
                        Matcher m = TOKEN.matcher(full.path("Text").asString(""));
                        if (m.find()) {
                            return m.group(1);
                        }
                    }
                }
            }
            sleep(500);
        }
        throw new IllegalStateException("No verification email for " + email);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
