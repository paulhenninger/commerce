package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.data.Ids;
import com.thirdstone.commerce.platform.events.Address;
import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.Outbox;
import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public record Placed(String id, String status) {
    }

    private final OrderRepository orders;
    private final OrderSaga saga;
    private final Outbox outbox;
    private final OrderMetrics metrics;
    private final OrderProperties props;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    OrderService(OrderRepository orders, OrderSaga saga, Outbox outbox, OrderMetrics metrics, OrderProperties props,
                 JsonMapper json, PlatformTransactionManager transactions) {
        this.orders = orders;
        this.saga = saga;
        this.outbox = outbox;
        this.metrics = metrics;
        this.props = props;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Accepts an order: priced from the local catalog copy, saved as PENDING with
     * its {@code order.placed} event in one transaction. The rest of the saga runs
     * asynchronously; the caller polls the order.
     */
    public Placed place(Caller caller, String idempotencyKey, PlaceOrderRequest request) {
        var existing = orders.findByIdempotencyKey(caller.userId(), idempotencyKey);
        if (existing.isPresent()) {
            return new Placed(existing.get().id(), existing.get().status());
        }
        long inFlight = metrics.inFlight();
        if (inFlight >= props.maxInFlight()) {
            // Joining a queue this long would only end in a timeout: say so now.
            log.atWarn().addKeyValue("inFlight", inFlight).log("Shedding load: order refused");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "We are very busy; please try again shortly",
                    "BUSY");
        }
        Priced priced = price(request);
        List<Line> lines = priced.lines();
        String currency = priced.currency();
        long total = lines.stream().mapToLong(l -> l.unitPriceCents() * l.quantity()).sum();
        if (total != request.expectedTotalCents()) {
            throw new PriceChangedException(lines, total);
        }
        Address address = request.shippingAddress().toAddress();
        String id = Ids.next();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            tx.executeWithoutResult(s -> {
                orders.insert(new OrderRow(id, caller.userId(), caller.email(), OrderStatus.PENDING, total, currency,
                        request.paymentMethod(), json.writeValueAsString(address), null, null, null, now, now, null),
                        idempotencyKey);
                orders.insertLines(id, lines);
                orders.history(id, OrderStatus.PENDING, "Order placed");
                outbox.append(Topics.ORDER, OrderEvents.PLACED, id, new OrderEvents.Placed(id, caller.userId(),
                        caller.email(), lines, total, currency));
            });
        } catch (DuplicateKeyException e) {
            // The same checkout retried concurrently: the other attempt won.
            OrderRow first = orders.findByIdempotencyKey(caller.userId(), idempotencyKey).orElseThrow(() -> e);
            return new Placed(first.id(), first.status());
        }
        metrics.placed();
        log.atInfo().addKeyValue("orderId", id).addKeyValue("totalCents", total).log("Order placed");
        return new Placed(id, OrderStatus.PENDING);
    }

    private record Priced(List<Line> lines, String currency) {
    }

    private Priced price(PlaceOrderRequest request) {
        Map<String, Integer> quantities = new TreeMap<>();
        for (var line : request.lines()) {
            quantities.merge(line.sku(), line.quantity(), Integer::sum);
        }
        Map<String, ProductSnapshot> products = orders.products(quantities.keySet());
        List<Line> lines = new ArrayList<>();
        for (var entry : quantities.entrySet()) {
            ProductSnapshot product = products.get(entry.getKey());
            if (product == null || !product.active()) {
                throw ApiException.badRequest("Product " + entry.getKey() + " is not available");
            }
            if (entry.getValue() > 20) {
                throw ApiException.badRequest("At most 20 of each product per order");
            }
            lines.add(new Line(product.sku(), product.name(), entry.getValue(), product.priceCents()));
        }
        var currencies = quantities.keySet().stream().map(sku -> products.get(sku).currency()).distinct().toList();
        if (currencies.size() != 1) {
            throw ApiException.badRequest("An order is paid in one currency");
        }
        return new Priced(lines, currencies.getFirst());
    }

    public OrderView get(Caller caller, String id) {
        OrderRow order = orders.findForUser(id, caller.userId()).orElseThrow(() -> ApiException.notFound("Order"));
        return view(order);
    }

    public PageResponse<OrderView.Summary> list(Caller caller, PageRequest page) {
        List<OrderView.Summary> items = orders.listForUser(caller.userId(), page).stream().map(this::summary).toList();
        return PageResponse.of(items, page, orders.countForUser(caller.userId()));
    }

    public OrderView cancel(Caller caller, String id) {
        tx.executeWithoutResult(s -> {
            orders.findForUser(id, caller.userId()).orElseThrow(() -> ApiException.notFound("Order"));
            if (!saga.cancel(id, OrderEvents.REASON_CUSTOMER, OrderStatus.CANCELLABLE, "Cancelled by the customer")) {
                throw ApiException.conflict("This order can no longer be cancelled", "NOT_CANCELLABLE");
            }
        });
        return get(caller, id);
    }

    public OrderView requestReturn(Caller caller, String id, String reason) {
        tx.executeWithoutResult(s -> {
            OrderRow order = orders.findForUser(id, caller.userId()).orElseThrow(() -> ApiException.notFound("Order"));
            Instant windowStart = Instant.now().minus(props.returnWindowDays(), ChronoUnit.DAYS);
            if (orders.requestReturn(id, reason, windowStart) == 0) {
                throw ApiException.conflict("Only delivered orders can be returned, within "
                        + props.returnWindowDays() + " days of delivery", "NOT_RETURNABLE");
            }
            orders.history(id, OrderStatus.RETURN_REQUESTED, "Return requested: " + reason);
            outbox.append(Topics.ORDER, OrderEvents.RETURN_REQUESTED, id, new OrderEvents.ReturnRequested(id,
                    order.userId(), order.email(), orders.lines(id), saga.address(order), reason));
        });
        return get(caller, id);
    }

    public OrderView adminGet(String id) {
        return view(orders.find(id).orElseThrow(() -> ApiException.notFound("Order")));
    }

    public PageResponse<OrderView.Summary> adminSearch(String q, String status, PageRequest page) {
        if (status != null && !status.isBlank() && !OrderStatus.ALL.contains(status)) {
            throw ApiException.badRequest("Unknown status");
        }
        List<OrderView.Summary> items = orders.search(q, status, page).stream().map(this::summary).toList();
        return PageResponse.of(items, page, orders.count(q, status));
    }

    private OrderView view(OrderRow o) {
        return new OrderView(o.id(), o.status(), o.totalCents(), o.currency(), saga.address(o), o.cancelReason(),
                o.trackingNumber(), o.returnTrackingNumber(), o.createdAt(), o.updatedAt(), o.deliveredAt(),
                orders.lines(o.id()), orders.history(o.id()));
    }

    private OrderView.Summary summary(OrderRow o) {
        int items = orders.lines(o.id()).stream().mapToInt(Line::quantity).sum();
        return new OrderView.Summary(o.id(), o.status(), o.totalCents(), o.currency(), items, o.createdAt());
    }
}
