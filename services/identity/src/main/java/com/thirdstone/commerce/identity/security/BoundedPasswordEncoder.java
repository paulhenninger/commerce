package com.thirdstone.commerce.identity.security;

import com.thirdstone.commerce.identity.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Lets only a few password hashes run at once.
 *
 * <p>Argon2id is deliberately expensive: each hash or check takes about 19 MiB of
 * heap for its 50 ms. With no bound, enough concurrent sign-ins, sign-ups or
 * password checks exhaust the heap and take the service down for everyone —
 * a denial of service needing nothing but a password form. Here a request waits
 * for a free slot; if none frees up within a few seconds it gets 503 ("busy, try
 * again"), and memory stays bounded at {@code permits × 19 MiB} whatever arrives.
 * The work done per hash (Argon2's parameters) is unchanged.
 */
public final class BoundedPasswordEncoder implements PasswordEncoder {

    public static final String BUSY = "BUSY";

    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final long waitMillis;

    public BoundedPasswordEncoder(PasswordEncoder delegate, int permits, long waitMillis) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        this.delegate = delegate;
        this.permits = new Semaphore(permits, true);
        this.waitMillis = waitMillis;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        acquire();
        try {
            return delegate.encode(rawPassword);
        } finally {
            permits.release();
        }
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        acquire();
        try {
            return delegate.matches(rawPassword, encodedPassword);
        } finally {
            permits.release();
        }
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        // Reads the hash's prefix and parameters only: no hashing, no slot needed.
        return delegate.upgradeEncoding(encodedPassword);
    }

    private void acquire() {
        try {
            if (permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Too many sign-ins right now; please try again", BUSY);
    }
}
