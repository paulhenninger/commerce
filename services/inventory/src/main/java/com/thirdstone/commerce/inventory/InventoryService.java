package com.thirdstone.commerce.inventory;

import com.thirdstone.commerce.platform.events.InventoryEvents;
import com.thirdstone.commerce.platform.events.Line;
import com.thirdstone.commerce.platform.events.OrderEvents;
import com.thirdstone.commerce.platform.events.Topics;
import com.thirdstone.commerce.platform.messaging.EventContext;
import com.thirdstone.commerce.platform.messaging.Outbox;
import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reserves stock for orders and gives it back. Every handler runs inside the
 * dispatcher's transaction, so the stock change, the reservation rows and the
 * events announcing them commit together.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final Logger audit = LoggerFactory.getLogger("commerce.audit");

    private final InventoryRepository stock;
    private final Outbox outbox;

    InventoryService(InventoryRepository stock, Outbox outbox) {
        this.stock = stock;
        this.outbox = outbox;
    }

    /** All of an order's lines, or none: a partial reservation would hold stock nobody can buy. */
    void reserve(OrderEvents.Placed order) {
        if (stock.hasReservations(order.orderId())) {
            EventContext.ignored("already reserved");
            return;
        }
        Map<String, Integer> wanted = new TreeMap<>();
        for (Line line : order.lines()) {
            wanted.merge(line.sku(), line.quantity(), Integer::sum);
        }
        Map<String, StockLevel> rows = stock.lock(wanted.keySet());
        for (var entry : wanted.entrySet()) {
            StockLevel row = rows.get(entry.getKey());
            if (row == null || row.available() < entry.getValue()) {
                outbox.append(Topics.INVENTORY, InventoryEvents.REJECTED, order.orderId(), new InventoryEvents.Rejected(
                        order.orderId(), InventoryEvents.REASON_OUT_OF_STOCK, entry.getKey()));
                log.atInfo().addKeyValue("orderId", order.orderId()).addKeyValue("sku", entry.getKey())
                        .log("Order rejected: not enough stock");
                return;
            }
        }
        for (var entry : wanted.entrySet()) {
            stock.adjust(entry.getKey(), 0, entry.getValue());
            stock.reserve(order.orderId(), entry.getKey(), entry.getValue());
            announce(entry.getKey(), rows.get(entry.getKey()).available() - entry.getValue());
        }
        outbox.append(Topics.INVENTORY, InventoryEvents.RESERVED, order.orderId(),
                new InventoryEvents.Reserved(order.orderId()));
        log.atInfo().addKeyValue("orderId", order.orderId()).log("Stock reserved");
    }

    /** The order is paid for: the units leave the shelf for good. Available does not change. */
    void commit(String orderId) {
        boolean any = false;
        for (Reservation r : stock.reservations(orderId)) {
            if (stock.moveReservation(orderId, r.sku(), "RESERVED", "COMMITTED") == 1) {
                stock.lock(r.sku());
                stock.adjust(r.sku(), -r.quantity(), -r.quantity());
                any = true;
            }
        }
        if (!any) {
            EventContext.ignored("nothing reserved");
        }
    }

    /** Undo whatever this order took: a held reservation is released, committed units go back on the shelf. */
    void release(String orderId) {
        boolean any = false;
        for (Reservation r : stock.reservations(orderId)) {
            if (stock.moveReservation(orderId, r.sku(), "RESERVED", "RELEASED") == 1) {
                stock.lock(r.sku());
                stock.adjust(r.sku(), 0, -r.quantity());
            } else if (stock.moveReservation(orderId, r.sku(), "COMMITTED", "RELEASED") == 1) {
                stock.lock(r.sku());
                stock.adjust(r.sku(), r.quantity(), 0);
            } else {
                continue;
            }
            any = true;
            announce(r.sku());
        }
        if (!any) {
            EventContext.ignored("nothing to release");
        }
    }

    /** Returned goods are back in the warehouse. Only units that were sold can come back, once. */
    void restock(String orderId) {
        boolean any = false;
        for (Reservation r : stock.reservations(orderId)) {
            if (stock.moveReservation(orderId, r.sku(), "COMMITTED", "RELEASED") == 1) {
                stock.lock(r.sku());
                stock.adjust(r.sku(), r.quantity(), 0);
                announce(r.sku());
                any = true;
            }
        }
        if (!any) {
            EventContext.ignored("nothing sold to restock");
        }
    }

    void productKnown(String sku) {
        stock.ensureStock(sku);
    }

    @Transactional
    public StockLevel setOnHand(Caller admin, String sku, int onHand) {
        var current = stock.lock(sku);
        if (current.isPresent() && onHand < current.get().reserved()) {
            throw ApiException.conflict(current.get().reserved() + " units are reserved for open orders",
                    "BELOW_RESERVED");
        }
        stock.setOnHand(sku, onHand);
        StockLevel level = stock.stock(sku).orElseThrow();
        outbox.append(Topics.STOCK, InventoryEvents.STOCK_CHANGED, sku,
                new InventoryEvents.StockChanged(sku, level.available()));
        audit.atInfo().addKeyValue("action", "STOCK_SET").addKeyValue("actor", admin.userId()).addKeyValue("sku", sku)
                .addKeyValue("onHand", onHand).log("Stock level set");
        return level;
    }

    public PageResponse<StockLevel> list(PageRequest page) {
        return PageResponse.of(stock.list(page), page, stock.count());
    }

    public List<Reservation> reservations(String orderId) {
        return stock.reservations(orderId);
    }

    private void announce(String sku) {
        announce(sku, stock.stock(sku).orElseThrow().available());
    }

    private void announce(String sku, int available) {
        outbox.append(Topics.STOCK, InventoryEvents.STOCK_CHANGED, sku, new InventoryEvents.StockChanged(sku, available));
    }
}
