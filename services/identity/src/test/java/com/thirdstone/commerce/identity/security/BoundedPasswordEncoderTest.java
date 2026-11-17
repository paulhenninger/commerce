package com.thirdstone.commerce.identity.security;

import com.thirdstone.commerce.identity.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedPasswordEncoderTest {

    /** Counts how many hashes run at the same moment. */
    static final class Slow implements PasswordEncoder {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final CountDownLatch release;

        Slow(CountDownLatch release) {
            this.release = release;
        }

        private String work() {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return "hash";
        }

        @Override
        public String encode(CharSequence raw) {
            return work();
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            work();
            return true;
        }
    }

    @Test
    void neverMoreThanThePermittedHashesRunAtOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Slow slow = new Slow(release);
        BoundedPasswordEncoder bounded = new BoundedPasswordEncoder(slow, 3, 10_000);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            calls.add(pool.submit(() -> n % 2 == 0 ? bounded.encode("pw") : bounded.matches("pw", "hash")));
        }
        Thread.sleep(300);
        assertThat(slow.running.get()).isEqualTo(3);
        release.countDown();
        for (Future<?> call : calls) {
            call.get();
        }
        assertThat(slow.most.get()).isEqualTo(3);
        pool.shutdown();
    }

    @Test
    void aCallerThatCannotGetASlotIsToldTheServiceIsBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BoundedPasswordEncoder bounded = new BoundedPasswordEncoder(new Slow(release), 1, 100);
        Thread holder = Thread.ofVirtual().start(() -> bounded.encode("pw"));
        Thread.sleep(50);
        assertThatThrownBy(() -> bounded.matches("pw", "hash"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo(BoundedPasswordEncoder.BUSY);
                });
        release.countDown();
        holder.join();
    }
}
