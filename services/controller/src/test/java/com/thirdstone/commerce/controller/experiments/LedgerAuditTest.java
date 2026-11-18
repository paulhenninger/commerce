package com.thirdstone.commerce.controller.experiments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audit passes a consistent ledger, and each invariant catches the failure it
 * names — the proofs can fail.
 */
class LedgerAuditTest {

    static final String ID = "01JX0000000000000000000001";
    static final String SHOPPER = "shopper-1@example.com";
    static final Instant FROM = Instant.parse("2026-11-17T10:00:00Z");
    static final Instant TO = FROM.plus(Duration.ofMinutes(5));
    static final JsonMapper JSON = JsonMapper.builder().build();

    final Map<String, JsonNode> resources = new HashMap<>();
    final Map<String, List<JsonNode>> lists = new HashMap<>();
    final LedgerAudit.BackOffice backOffice = new LedgerAudit.BackOffice() {
        @Override
        public JsonNode get(String path) {
            return resources.get(path);
        }

        @Override
        public List<JsonNode> all(String path) {
            return lists.getOrDefault(path, List.of());
        }
    };

    static JsonNode json(String text) {
        return JSON.readTree(text.replace('\'', '"'));
    }

    /** A delivered order, consistent in every service. */
    @BeforeEach
    void deliveredOrder() {
        order("DELIVERED", null, "PENDING", "AWAITING_PAYMENT", "CONFIRMED", "SHIPPED", "DELIVERED");
        lists.put("/api/v1/backoffice/orders?q=" + SHOPPER,
                List.of(json("{'id':'" + ID + "','createdAt':'" + FROM.plusSeconds(10) + "'}")));
        payments("{'status':'AUTHORIZED','amountCents':2500}");
        reservations("{'sku':'LAMP','quantity':1,'status':'COMMITTED'}");
        shipments("{'direction':'OUTBOUND','status':'DELIVERED'}");
        emails("order-placed", "order-confirmed", "order-shipped", "order-delivered");
        stock("{'sku':'LAMP','onHand':9,'reserved':0,'available':9}");
    }

    void order(String status, String reason, String... history) {
        StringBuilder h = new StringBuilder();
        for (String s : history) {
            h.append(h.isEmpty() ? "" : ",").append("{'status':'").append(s).append("'}");
        }
        resources.put("/api/v1/backoffice/orders/" + ID, json("{'id':'" + ID + "','status':'" + status
                + "','totalCents':2500,'cancelReason':" + (reason == null ? "null" : "'" + reason + "'")
                + ",'lines':[{'sku':'LAMP','quantity':1}],'history':[" + h + "]}"));
    }

    void payments(String... rows) {
        resources.put("/api/v1/backoffice/billing/payments?orderId=" + ID, json("[" + String.join(",", rows) + "]"));
    }

    void reservations(String... rows) {
        resources.put("/api/v1/backoffice/inventory/reservations?orderId=" + ID, json("[" + String.join(",", rows) + "]"));
    }

    void shipments(String... rows) {
        resources.put("/api/v1/backoffice/shipping/shipments?orderId=" + ID, json("[" + String.join(",", rows) + "]"));
    }

    void emails(String... templates) {
        List<String> rows = new ArrayList<>();
        for (String t : templates) {
            rows.add("{'orderId':'" + ID + "','template':'" + t + "'}");
        }
        resources.put("/api/v1/backoffice/notifications?recipient=" + SHOPPER, json("[" + String.join(",", rows) + "]"));
    }

    void stock(String... rows) {
        List<JsonNode> out = new ArrayList<>();
        for (String r : rows) {
            out.add(json(r));
        }
        lists.put("/api/v1/backoffice/inventory/stock", out);
    }

    Run.Check audit() {
        return audit(Map.of(ID, SHOPPER), Set.of(ID));
    }

    Run.Check audit(Map<String, String> placed, Set<String> known) {
        Run run = new Run();
        new LedgerAudit(backOffice, run).audit(placed, known, FROM, TO, Experiment.NORMAL_REASONS);
        return run.checks.getLast();
    }

    @Test
    void aConsistentLedgerPasses() {
        Run.Check check = audit();
        assertThat(check.passed()).as(check.detail()).isTrue();
    }

    @Test
    void anAcceptedOrderThatDoesNotExistIsALostRequest() {
        resources.remove("/api/v1/backoffice/orders/" + ID);
        assertThat(audit().detail()).contains("lost request");
    }

    @Test
    void anOrderNoShopperWasToldAboutIsADuplicatedRequest() {
        lists.put("/api/v1/backoffice/orders?q=" + SHOPPER, List.of(
                json("{'id':'" + ID + "','createdAt':'" + FROM.plusSeconds(10) + "'}"),
                json("{'id':'01JX0000000000000000000002','createdAt':'" + FROM.plusSeconds(11) + "'}")));
        assertThat(audit().detail()).contains("duplicated request");
    }

    @Test
    void anOrderStillMidSagaIsStuck() {
        order("AWAITING_PAYMENT", null, "PENDING", "AWAITING_PAYMENT");
        assertThat(audit().detail()).contains("stuck saga");
    }

    @Test
    void twoChargesAreADoubleCharge() {
        payments("{'status':'AUTHORIZED','amountCents':2500}", "{'status':'AUTHORIZED','amountCents':2500}");
        assertThat(audit().detail()).contains("double charge");
    }

    @Test
    void aChargeOfTheWrongAmountIsCaught() {
        payments("{'status':'AUTHORIZED','amountCents':2499}");
        assertThat(audit().detail()).contains("charged 2499 for a total of 2500");
    }

    @Test
    void aConfirmedOrderWithNoPaymentIsAMissingCharge() {
        payments();
        assertThat(audit().detail()).contains("missing charge");
    }

    @Test
    void aCancelledOrderStillChargedIsChargedForNothing() {
        order("CANCELLED", "CUSTOMER", "PENDING", "AWAITING_PAYMENT", "CONFIRMED", "CANCELLED");
        reservations("{'sku':'LAMP','quantity':1,'status':'RELEASED'}");
        shipments("{'direction':'OUTBOUND','status':'CANCELLED'}");
        emails("order-placed", "order-confirmed", "order-cancelled");
        assertThat(audit().detail()).contains("charged for nothing");
    }

    @Test
    void aReturnedOrderWithoutARefundIsAMissingRefund() {
        order("REFUNDED", null, "PENDING", "AWAITING_PAYMENT", "CONFIRMED", "SHIPPED", "DELIVERED", "RETURN_REQUESTED",
                "RETURNED", "REFUNDED");
        reservations("{'sku':'LAMP','quantity':1,'status':'RELEASED'}");
        shipments("{'direction':'OUTBOUND','status':'DELIVERED'}", "{'direction':'RETURN','status':'DELIVERED'}");
        emails("order-placed", "order-confirmed", "order-shipped", "order-delivered", "order-refunded");
        assertThat(audit().detail()).contains("missing refund");
    }

    @Test
    void anOrderThatMovesOnWhileBeingReadIsReadAgainNotReported() {
        // Read first as RETURN_REQUESTED; by the time its payment and stock are read it was returned and refunded.
        JsonNode requested = json("{'id':'" + ID + "','status':'RETURN_REQUESTED','totalCents':2500,'cancelReason':null,"
                + "'lines':[{'sku':'LAMP','quantity':1}],'history':[{'status':'CONFIRMED'}]}");
        order("REFUNDED", null, "PENDING", "AWAITING_PAYMENT", "CONFIRMED", "SHIPPED", "DELIVERED", "RETURN_REQUESTED",
                "RETURNED", "REFUNDED");
        JsonNode refundedOrder = resources.get("/api/v1/backoffice/orders/" + ID);
        payments("{'status':'REFUNDED','amountCents':2500}");
        reservations("{'sku':'LAMP','quantity':1,'status':'RELEASED'}");
        shipments("{'direction':'OUTBOUND','status':'DELIVERED'}", "{'direction':'RETURN','status':'RECEIVED'}");
        emails("order-placed", "order-confirmed", "order-shipped", "order-delivered", "order-refunded");
        int[] reads = {0};
        LedgerAudit.BackOffice moving = new LedgerAudit.BackOffice() {
            @Override
            public JsonNode get(String path) {
                if (path.equals("/api/v1/backoffice/orders/" + ID)) {
                    return reads[0]++ == 0 ? requested : refundedOrder;
                }
                return resources.get(path);
            }

            @Override
            public List<JsonNode> all(String path) {
                return lists.getOrDefault(path, List.of());
            }
        };
        Run run = new Run();
        new LedgerAudit(moving, run).audit(Map.of(ID, SHOPPER), Set.of(ID), FROM, TO, Experiment.NORMAL_REASONS);
        assertThat(run.checks.getLast().passed()).as(run.checks.getLast().detail()).isTrue();
    }

    @Test
    void aRefundStillWaitingForTheProviderIsCaught() {
        order("REFUNDED", null, "PENDING", "AWAITING_PAYMENT", "CONFIRMED", "SHIPPED", "DELIVERED", "RETURN_REQUESTED",
                "RETURNED");
        payments("{'status':'REFUND_PENDING','amountCents':2500,'refundLastError':'circuit open'}");
        reservations("{'sku':'LAMP','quantity':1,'status':'RELEASED'}");
        shipments("{'direction':'OUTBOUND','status':'DELIVERED'}", "{'direction':'RETURN','status':'RECEIVED'}");
        emails("order-placed", "order-confirmed", "order-shipped", "order-delivered", "order-refunded");
        assertThat(audit().detail()).contains("refund is still waiting for the provider (circuit open)");
    }

    @Test
    void aParcelThatLeftBeforeTheCancellationIsASupportCaseNotALedgerError() {
        order("CANCELLED", "CUSTOMER", "PENDING", "AWAITING_PAYMENT", "CONFIRMED", "CANCELLED");
        payments("{'status':'REFUNDED','amountCents':2500}");
        reservations("{'sku':'LAMP','quantity':1,'status':'RELEASED'}");
        shipments("{'direction':'OUTBOUND','status':'DELIVERED'}");
        emails("order-placed", "order-confirmed", "order-cancelled");
        Run run = new Run();
        new LedgerAudit(backOffice, run).audit(Map.of(ID, SHOPPER), Set.of(ID), FROM, TO, Experiment.NORMAL_REASONS);
        assertThat(run.checks.getLast().passed()).as(run.checks.getLast().detail()).isTrue();
        assertThat(run.checks).anySatisfy(c -> assertThat(c.detail()).contains("cancelled after its parcel left (DELIVERED)"));
    }

    @Test
    void aShipmentSentForAnOrderNeverConfirmedIsAnError() {
        order("CANCELLED", "PAYMENT_DECLINED", "PENDING", "AWAITING_PAYMENT", "CANCELLED");
        payments("{'status':'FAILED','amountCents':2500}");
        reservations("{'sku':'LAMP','quantity':1,'status':'RELEASED'}");
        shipments("{'direction':'OUTBOUND','status':'DISPATCHED'}");
        emails("order-placed", "order-cancelled");
        assertThat(audit().detail()).contains("was cancelled but its shipment is DISPATCHED");
    }

    @Test
    void aCancelledOrderStillHoldingStockIsALeak() {
        order("CANCELLED", "PAYMENT_DECLINED", "PENDING", "AWAITING_PAYMENT", "CANCELLED");
        payments("{'status':'DECLINED','amountCents':2500}");
        reservations("{'sku':'LAMP','quantity':1,'status':'RESERVED'}");
        shipments();
        emails("order-placed", "order-cancelled");
        assertThat(audit().detail()).contains("still holds LAMP stock");
    }

    @Test
    void reservedStockAtRestIsALeakedReservation() {
        stock("{'sku':'LAMP','onHand':9,'reserved':1,'available':8}");
        assertThat(audit().detail()).contains("leaked reservation");
    }

    @Test
    void negativeStockIsOversold() {
        stock("{'sku':'LAMP','onHand':-1,'reserved':0,'available':-1}");
        assertThat(audit().detail()).contains("oversold");
    }

    @Test
    void aShippedOrderWithTwoShipmentsIsCaught() {
        shipments("{'direction':'OUTBOUND','status':'DELIVERED'}", "{'direction':'OUTBOUND','status':'DELIVERED'}");
        assertThat(audit().detail()).contains("2 outbound shipments");
    }

    @Test
    void theSameEmailTwiceIsADuplicate() {
        emails("order-placed", "order-confirmed", "order-confirmed", "order-shipped", "order-delivered");
        assertThat(audit().detail()).contains("duplicate");
    }

    @Test
    void aConfirmedOrderWithoutItsEmailIsCaught() {
        emails("order-placed", "order-shipped", "order-delivered");
        assertThat(audit().detail()).contains("no confirmation email");
    }

    @Test
    void anUnexpectedCancellationReasonIsCaught() {
        order("CANCELLED", "TIMEOUT", "PENDING", "CANCELLED");
        payments();
        reservations("{'sku':'LAMP','quantity':1,'status':'RELEASED'}");
        shipments();
        emails("order-placed", "order-cancelled");
        assertThat(audit().detail()).contains("cancelled for TIMEOUT");
    }
}
