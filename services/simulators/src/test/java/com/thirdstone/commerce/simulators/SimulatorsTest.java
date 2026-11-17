package com.thirdstone.commerce.simulators;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"commerce.simulators.api-key=sim-key, other-key", "commerce.simulators.carrier.dispatch-after=0s",
        "commerce.simulators.carrier.deliver-after=1h", "management.server.port=0",
        "management.tracing.export.enabled=false"})
@AutoConfigureMockMvc
class SimulatorsTest {

    @Autowired MockMvc mvc;

    @AfterEach
    void clearFaults() throws Exception {
        for (String target : Faults.TARGETS) {
            mvc.perform(put("/admin/faults/" + target).contentType("application/json")
                    .content("{\"latencyMs\":0,\"errorRate\":0,\"outage\":false}"));
        }
    }

    private ResultActions charge(String key, String method) throws Exception {
        return mvc.perform(post("/payments/v1/charges").header("Authorization", "Bearer sim-key")
                .header("Idempotency-Key", key).contentType("application/json")
                .content("{\"orderId\":\"o\",\"amountCents\":100,\"currency\":\"USD\",\"paymentMethod\":\"" + method + "\"}"));
    }

    @Test
    void chargesAreApprovedOnceAndRepeatedWithTheSameAnswer() throws Exception {
        String id = charge("k1", "tok_visa").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        charge("k1", "tok_visa").andExpect(status().isCreated())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(id));
    }

    @Test
    void testTokensAreDeclined() throws Exception {
        charge("k2", "tok_decline").andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("DECLINED"));
        charge("k3", "tok_insufficient").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void callersWithoutTheKeyAreRefused() throws Exception {
        mvc.perform(post("/payments/v1/charges").header("Idempotency-Key", "k4").contentType("application/json")
                .content("{\"amountCents\":1,\"paymentMethod\":\"tok_visa\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void anOutageAnswers503UntilItIsLifted() throws Exception {
        mvc.perform(put("/admin/faults/payment").contentType("application/json")
                .content("{\"latencyMs\":0,\"errorRate\":0,\"outage\":true}")).andExpect(status().isOk());
        charge("k5", "tok_visa").andExpect(status().isServiceUnavailable());
        clearFaults();
        charge("k5", "tok_visa").andExpect(status().isCreated());
        mvc.perform(get("/admin/faults")).andExpect(jsonPath("$.payment.outage").value(false));
    }

    @Test
    void refundsNeedARealCharge() throws Exception {
        String body = charge("k6", "tok_visa").andReturn().getResponse().getContentAsString();
        String chargeId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        mvc.perform(post("/payments/v1/refunds").header("Authorization", "Bearer sim-key").header("Idempotency-Key", "r1")
                .contentType("application/json").content("{\"chargeId\":\"" + chargeId + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amountCents").value(100));
        mvc.perform(post("/payments/v1/refunds").header("Authorization", "Bearer sim-key").header("Idempotency-Key", "r2")
                .contentType("application/json").content("{\"chargeId\":\"ch_nope\"}")).andExpect(status().isNotFound());
    }

    @Test
    void shipmentsAdvanceWithTime() throws Exception {
        String body = mvc.perform(post("/carrier/v1/shipments").header("Authorization", "Bearer sim-key")
                        .header("Idempotency-Key", "s1").contentType("application/json")
                        .content("{\"reference\":\"o1\",\"direction\":\"OUTBOUND\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.carrier").value("SimShip"))
                .andReturn().getResponse().getContentAsString();
        String tracking = body.replaceAll(".*\"trackingNumber\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/carrier/v1/shipments/" + tracking).header("Authorization", "Bearer sim-key"))
                .andExpect(jsonPath("$.status").value("DISPATCHED"));
    }
}
