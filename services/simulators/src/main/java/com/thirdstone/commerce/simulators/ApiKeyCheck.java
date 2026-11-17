package com.thirdstone.commerce.simulators;

import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Like a real provider, the simulators want to know who is calling. */
@Component
class ApiKeyCheck {

    private final byte[] key;

    ApiKeyCheck(SimulatorProperties props) {
        this.key = props.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    void check(String authorization) {
        if (key.length == 0) {
            return;
        }
        byte[] given = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("Bearer " + new String(key, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expected)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid API key", "INVALID_API_KEY");
        }
    }
}
