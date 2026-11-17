package com.thirdstone.commerce.testkit;

import com.thirdstone.commerce.testkit.journal.Journal;
import com.thirdstone.commerce.testkit.shop.Shoppers;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The testkit's own logic; its behaviour against the stack is proved by scripts/verify-testkit.py. */
class TestkitTest {

    @Test
    void refusesToRunOutsideDev() {
        assertThatThrownBy(() -> new DevOnly(new MockEnvironment().withProperty("commerce.environment", "prod")))
                .isInstanceOf(IllegalStateException.class);
        new DevOnly(new MockEnvironment().withProperty("commerce.environment", "dev"));
    }

    @Test
    void shopperSettingsAreBounded() {
        assertThatThrownBy(() -> new Shoppers.Settings(500, 10, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Shoppers.Settings(5, 10, 1.5, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        new Shoppers.Settings(20, 60, 0.1, 0.1, 0.05, 0.02);
    }

    @Test
    void journalKeepsOrderAndAnswersByTime() throws InterruptedException {
        Journal journal = new Journal();
        Instant before = Instant.now();
        journal.record("a", "order.placed", "o1", "accepted", null);
        Thread.sleep(2);
        journal.record("b", "order.placed", "o2", "accepted", null);
        assertThat(journal.between(before, Instant.now().plusSeconds(1), 10)).extracting(Journal.Entry::orderId)
                .containsExactly("o1", "o2");
        assertThat(journal.latest(1)).extracting(Journal.Entry::orderId).containsExactly("o2");
    }
}
