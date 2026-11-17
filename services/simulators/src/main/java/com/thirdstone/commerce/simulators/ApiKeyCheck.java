package com.thirdstone.commerce.simulators;

import com.thirdstone.commerce.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * Like a real provider, the simulators want to know who is calling. Each caller
 * has its own key (billing's for payments, shipping's for the carrier);
 * {@code commerce.simulators.api-key} lists them, comma-separated.
 */
@Component
class ApiKeyCheck {

    private final List<byte[]> accepted;

    ApiKeyCheck(SimulatorProperties props) {
        this.accepted = Arrays.stream(props.apiKey().split(",")).map(String::strip).filter(k -> !k.isEmpty())
                .map(k -> ("Bearer " + k).getBytes(StandardCharsets.UTF_8)).toList();
    }

    void check(String authorization) {
        if (accepted.isEmpty()) {
            return;
        }
        byte[] given = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        for (byte[] key : accepted) {
            // No early exit: every key is compared, in constant time.
            ok |= MessageDigest.isEqual(given, key);
        }
        if (!ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid API key", "INVALID_API_KEY");
        }
    }
}
