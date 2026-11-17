package com.thirdstone.commerce.platform.data;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    void idsAreUrlSafeUniqueAndSortByCreationTime() throws InterruptedException {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String id = Ids.next();
            assertThat(id).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
            assertThat(seen.add(id)).isTrue();
        }
        String earlier = Ids.next();
        Thread.sleep(3);
        assertThat(Ids.next()).isGreaterThan(earlier);
    }
}
