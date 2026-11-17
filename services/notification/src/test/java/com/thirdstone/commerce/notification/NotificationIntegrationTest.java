package com.thirdstone.commerce.notification;

import com.thirdstone.commerce.platform.data.Ids;
import com.thirdstone.commerce.platform.events.Address;
import com.thirdstone.commerce.platform.events.EventEnvelope;
import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.ShippingEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.testing.CommerceContainers;
import com.thirdstone.commerce.platform.testing.Edge;
import com.thirdstone.commerce.platform.testing.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"commerce.notification.worker-interval-millis=200", "spring.mail.host=localhost",
        "commerce.mail.from=Commerce <no-reply@commerce.test>", "commerce.mail.shop-url=https://shop.example"})
@AutoConfigureMockMvc
class NotificationIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CommerceContainers.register(registry);
    }

    private static final Address ADDRESS = new Address("Ada", "1 Secret Street", "Springfield", "12345", "US");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean JavaMailSender mail;
    final EventBus bus = EventBus.shared();

    @BeforeEach
    void reset() {
        clearInvocations(mail);
        doNothing().when(mail).send(any(SimpleMailMessage.class));
    }

    private List<SimpleMailMessage> sentTo(String recipient) {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(captor.capture());
        return captor.getAllValues().stream().filter(m -> m.getTo()[0].equals(recipient)).toList();
    }

    private String status(String recipient) {
        return jdbc.sql("SELECT status FROM notifications WHERE recipient = ? ORDER BY id DESC LIMIT 1")
                .param(recipient).query(String.class).optional().orElse(null);
    }

    @Test
    void everyCustomerFacingEventSendsItsEmailOnce() {
        String orderId = Ids.next();
        String to = "ada-" + orderId.toLowerCase() + "@example.com";
        EventEnvelope confirmed = bus.publish(OrderEvents.CONFIRMED, orderId, new OrderEvents.Confirmed(orderId, 5, to,
                List.of(new Line("MUG", "Blue mug", 2, 1250)), 2500, "USD", ADDRESS));
        bus.publish(ShippingEvents.DISPATCHED, orderId, new ShippingEvents.Dispatched(orderId, 5, to, "SimShip", "SIM1"));
        bus.publish(ShippingEvents.DELIVERED, orderId, new ShippingEvents.Delivered(orderId, 5, to, "SIM1"));
        bus.publish(ShippingEvents.RETURN_LABEL_CREATED, orderId,
                new ShippingEvents.ReturnLabelCreated(orderId, 5, to, "SimShip", "RET1"));
        bus.publish(OrderEvents.REFUNDED, orderId, new OrderEvents.Refunded(orderId, 5, to, 2500, "USD"));
        bus.publish(OrderEvents.CANCELLED, orderId, new OrderEvents.Cancelled(orderId, 5, to, "CUSTOMER", 2500, "USD"));
        // The same event again: no second email.
        bus.publishRaw(Topics.ORDER, orderId, JsonMapper.builder().build().writeValueAsBytes(confirmed));

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                "SELECT COUNT(*) FROM notifications WHERE recipient = ? AND status = 'SENT'").param(to)
                .query(Integer.class).single() == 6);
        List<SimpleMailMessage> sent = sentTo(to);
        assertThat(sent).hasSize(6);
        assertThat(sent).extracting(SimpleMailMessage::getSubject).anySatisfy(s -> assertThat(s).contains("confirmed"))
                .anySatisfy(s -> assertThat(s).contains("on its way")).anySatisfy(s -> assertThat(s).contains("delivered"))
                .anySatisfy(s -> assertThat(s).contains("return label")).anySatisfy(s -> assertThat(s).contains("refund"))
                .anySatisfy(s -> assertThat(s).contains("cancelled"));
        SimpleMailMessage confirmation = sent.stream().filter(m -> m.getSubject().contains("confirmed")).findFirst()
                .orElseThrow();
        assertThat(confirmation.getText()).contains("2 × Blue mug").contains("USD 25.00")
                .contains("https://shop.example/orders/" + orderId).doesNotContain("Secret Street");
    }

    @Test
    void aMailServerThatFailsIsRetriedLater() {
        doThrow(new MailSendException("relay down")).doNothing().when(mail).send(any(SimpleMailMessage.class));
        String orderId = Ids.next();
        String to = "retry-" + orderId.toLowerCase() + "@example.com";
        bus.publish(ShippingEvents.DELIVERED, orderId, new ShippingEvents.Delivered(orderId, 5, to, "SIM1"));
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                "SELECT attempts FROM notifications WHERE recipient = ?").param(to).query(Integer.class)
                .optional().orElse(0) == 1);
        assertThat(status(to)).isEqualTo("PENDING");
        // Rather than wait a minute for the backoff: make it due now.
        jdbc.sql("UPDATE notifications SET next_attempt_at = UTC_TIMESTAMP(6) WHERE recipient = ?").param(to).update();
        await().atMost(Duration.ofSeconds(30)).until(() -> "SENT".equals(status(to)));
    }

    @Test
    void theBackOfficeSeesWhoWasSentWhatButNotTheText() throws Exception {
        String orderId = Ids.next();
        String to = "audit-" + orderId.toLowerCase() + "@example.com";
        bus.publish(ShippingEvents.DELIVERED, orderId, new ShippingEvents.Delivered(orderId, 5, to, "SIM1"));
        await().atMost(Duration.ofSeconds(30)).until(() -> "SENT".equals(status(to)));
        mvc.perform(Edge.admin(get("/api/v1/backoffice/notifications"), 1).param("recipient", to))
                .andExpect(jsonPath("$[0].template").value("shipment-delivered"))
                .andExpect(jsonPath("$[0].orderId").value(orderId))
                .andExpect(jsonPath("$[0].body").doesNotExist());
    }

    @Test
    void backoffDoublesUpToAnHour() {
        assertThat(NotificationWorker.backoff(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(NotificationWorker.backoff(3)).isEqualTo(Duration.ofMinutes(8));
        assertThat(NotificationWorker.backoff(29)).isEqualTo(Duration.ofMinutes(60));
    }
}
