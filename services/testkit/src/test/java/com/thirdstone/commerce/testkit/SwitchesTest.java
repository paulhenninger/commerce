package com.thirdstone.commerce.testkit;

import com.thirdstone.commerce.testkit.chaos.Actor;
import com.thirdstone.commerce.testkit.chaos.ChaosLab;
import com.thirdstone.commerce.testkit.shop.Seeder;
import com.thirdstone.commerce.testkit.shop.Shoppers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.PUT;

/** ADR-033: every simulation starts off, and what is switched on survives a restart. */
class SwitchesTest {

    @TempDir
    Path dir;

    private final JsonMapper json = JsonMapper.builder().build();
    private Seeder seeder;
    private Shoppers shoppers;
    private ChaosLab lab;
    private RestClient.Builder builder;
    private MockRestServiceServer simulators;
    private Shoppers.Settings current;

    @BeforeEach
    void setUp() {
        seeder = mock(Seeder.class);
        shoppers = mock(Shoppers.class);
        lab = mock(ChaosLab.class);
        current = Shoppers.Settings.OFF;
        when(shoppers.settings()).thenAnswer(i -> current);
        when(shoppers.update(any())).thenAnswer(i -> current = i.getArgument(0));
        when(lab.list()).thenReturn(List.of());
        builder = RestClient.builder();
        simulators = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    }

    private Switches switches() {
        TestkitProperties props = new TestkitProperties("http://storefront", "http://mailpit", "http://simulators",
                "admin@example.com", "a", "s", null, dir.toString());
        return new Switches(seeder, shoppers, lab, builder, props, json);
    }

    @Test
    void aFreshVolumeMeansEverythingOff() {
        Switches switches = switches();
        assertThat(switches.load()).isNull();
        Switches.State state = switches.state();
        assertThat(state.testData()).isFalse();
        assertThat(state.shoppers()).isEqualTo(Shoppers.Settings.OFF);
        assertThat(state.actors()).isEmpty();
        assertThat(state.faults()).containsOnlyKeys("payment", "carrier")
                .allSatisfy((target, fault) -> assertThat(fault).containsEntry("outage", false));
    }

    @Test
    void whatIsSwitchedOnIsRememberedAndReapplied() {
        Shoppers.Settings busy = new Shoppers.Settings(20, 60, 0.1, 0.1, 0.05, 0.02);
        ChaosLab.Start flood = new ChaosLab.Start("producer", "FLOOD", "orders", 50.0);
        when(lab.start(flood)).thenReturn(new Actor.View("1", "producer", "FLOOD", "orders", 50, Instant.now(), true,
                Map.of(), null));
        simulators.expect(requestTo("http://simulators/admin/faults/payment")).andExpect(method(PUT))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Switches before = switches();
        before.testData(true);
        before.shoppers(busy);
        before.startActor(flood);
        when(lab.list()).thenReturn(List.of(new Actor.View("1", "producer", "FLOOD", "orders", 50, Instant.now(),
                true, Map.of(), null)));
        before.fault("payment", Map.of("outage", true, "latencyMs", 0, "errorRate", 0.0));
        assertThat(Files.exists(dir.resolve("switches.json"))).isTrue();

        // A new testkit on the same volume: it finds the switches and puts them back.
        Switches after = switches();
        Switches.State remembered = after.load();
        assertThat(remembered.testData()).isTrue();
        assertThat(remembered.shoppers()).isEqualTo(busy);
        assertThat(remembered.actors()).containsExactly(flood);
        assertThat(remembered.faults().get("payment")).containsEntry("outage", true);

        simulators.reset();
        simulators.expect(requestTo("http://simulators/admin/faults/payment"))
                .andExpect(content().json("{\"outage\":true}")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        simulators.expect(requestTo("http://simulators/admin/faults/carrier"))
                .andExpect(content().json("{\"outage\":false}")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        after.apply(remembered);
        verify(seeder, org.mockito.Mockito.atLeastOnce()).testData(true);
        assertThat(current).isEqualTo(busy);
        verify(lab, org.mockito.Mockito.times(2)).start(flood);
        simulators.verify();
    }

    @Test
    void applyingAStateSwitchesOffWhatItDoesNotList() {
        simulators.expect(requestTo("http://simulators/admin/faults/payment"))
                .andExpect(content().json("{\"outage\":false}")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        simulators.expect(requestTo("http://simulators/admin/faults/carrier"))
                .andExpect(content().json("{\"outage\":false}")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        current = new Shoppers.Settings(20, 60, 0.1, 0.1, 0.05, 0.02);

        switches().apply(Switches.State.off());

        var order = inOrder(lab);
        order.verify(lab).stopAll();
        verify(seeder).testData(false);
        assertThat(current).isEqualTo(Shoppers.Settings.OFF);
        simulators.verify();
    }

    @Test
    void anUnreadableFileLeavesEverythingOff() throws Exception {
        Files.writeString(dir.resolve("switches.json"), "{not json");
        assertThat(switches().load()).isNull();
    }
}
