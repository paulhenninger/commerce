package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only order lookup for support and for the ledger audit. Administrators only. */
@RestController
@RequestMapping("/api/v1/backoffice/orders")
class BackofficeOrderController {

    private final OrderService orders;

    BackofficeOrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    PageResponse<OrderView.Summary> search(Caller caller, @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        caller.requireAdmin();
        return orders.adminSearch(q, status, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    OrderView get(Caller caller, @PathVariable String id) {
        caller.requireAdmin();
        return orders.adminGet(OrderController.checked(id));
    }
}
