package com.thirdstone.commerce.platform.ops;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestToolingGuardTest {

    @Test
    void devMayUseEveryTestFacility() {
        MockEnvironment env = env("dev").withProperty("commerce.payment.gateway-url", "http://simulators:8080/payment")
                .withProperty("spring.mail.host", "mailpit");
        env.setActiveProfiles("testdata");
        assertThatCode(() -> new TestToolingGuard(env)).doesNotThrowAnyException();
    }

    @Test
    void realProvidersAreAcceptedEverywhere() {
        for (String environment : new String[]{"int", "qa", "prod"}) {
            MockEnvironment env = env(environment)
                    .withProperty("commerce.payment.gateway-url", "https://api.payments.example/v1")
                    .withProperty("commerce.carrier.url", "https://carrier.example")
                    .withProperty("spring.mail.host", "smtp.example.com");
            assertThatCode(() -> new TestToolingGuard(env)).doesNotThrowAnyException();
        }
    }

    @Test
    void simulatedPaymentProviderIsRefusedOutsideDev() {
        for (String environment : new String[]{"int", "qa", "prod", "PROD", "staging"}) {
            MockEnvironment env = env(environment)
                    .withProperty("commerce.payment.gateway-url", "http://simulators:8080/payment");
            assertThatThrownBy(() -> new TestToolingGuard(env)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("commerce.payment.gateway-url");
        }
    }

    @Test
    void mailCatcherTestkitAndControllerAreRefusedOutsideDev() {
        assertThat(TestToolingGuard.problems(env("prod").withProperty("spring.mail.host", "mailpit"))).hasSize(1);
        assertThat(TestToolingGuard.problems(env("prod").withProperty("commerce.testkit.url", "http://testkit:8080")))
                .hasSize(1);
        assertThat(TestToolingGuard.problems(env("prod").withProperty("commerce.controller.url", "http://controller:8080/")))
                .hasSize(1);
        assertThat(TestToolingGuard.problems(env("prod").withProperty("commerce.carrier.url", "http://simulators/carrier")))
                .hasSize(1);
    }

    @Test
    void everyProblemIsNamedAtOnce() {
        MockEnvironment env = env("qa").withProperty("commerce.payment.gateway-url", "http://simulators:8080/payment")
                .withProperty("spring.mail.host", "mailpit");
        env.setActiveProfiles("chaos");
        assertThat(TestToolingGuard.problems(env)).hasSize(3);
    }

    @Test
    void hostThatMerelyContainsATestNameIsNotMistaken() {
        assertThat(TestToolingGuard.problems(env("prod").withProperty("spring.mail.host", "mailpit-relay.example.com")))
                .isEmpty();
    }

    private static MockEnvironment env(String environment) {
        return new MockEnvironment().withProperty("commerce.environment", environment);
    }
}
