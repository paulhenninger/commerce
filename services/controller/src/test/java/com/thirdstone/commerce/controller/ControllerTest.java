package com.thirdstone.commerce.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The policy; its behaviour against Docker is proved by scripts/verify-instances.py. */
class ControllerTest {

    @Test
    void theMeansOfWatchingAndUndoingCanNeverBeControlled() {
        for (String service : InstancePolicy.NEVER) {
            assertThat(InstancePolicy.controllable(service)).as(service).isFalse();
        }
        assertThat(InstancePolicy.controllable("something-new")).isFalse();
        assertThat(InstancePolicy.controllable(null)).isFalse();
    }

    @Test
    void theServicesAndBrokersCanBe() {
        for (String service : new String[]{"order", "identity", "storefront-web", "kafka-2", "mysql"}) {
            assertThat(InstancePolicy.controllable(service)).as(service).isTrue();
        }
    }

    @Test
    void refusesToRunOutsideDevOrWithoutAProject() {
        assertThatThrownBy(() -> new DevOnly(new MockEnvironment().withProperty("commerce.environment", "qa")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ControllerProperties("http://docker-proxy:2375", "v1.47", " "))
                .isInstanceOf(IllegalStateException.class);
    }
}
