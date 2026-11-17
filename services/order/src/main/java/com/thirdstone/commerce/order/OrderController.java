package com.thirdstone.commerce.order;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** A customer's orders. Everything is scoped to the caller: someone else's order does not exist. */
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    static final Pattern ID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * 202, not 201: the order exists but is not yet decided. The client polls it.
     * {@code Idempotency-Key} (one per checkout attempt) makes a retried request
     * return the first order instead of placing a second.
     */
    @PostMapping
    ResponseEntity<OrderService.Placed> place(Caller caller, @RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody PlaceOrderRequest request) {
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("Idempotency-Key must be 8-64 letters, digits or hyphens");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(orders.place(caller, key, request));
    }

    @GetMapping
    PageResponse<OrderView.Summary> list(Caller caller, @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return orders.list(caller, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    OrderView get(Caller caller, @PathVariable String id) {
        return orders.get(caller, checked(id));
    }

    @PostMapping("/{id}/cancel")
    OrderView cancel(Caller caller, @PathVariable String id) {
        return orders.cancel(caller, checked(id));
    }

    record ReturnRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/{id}/return")
    OrderView requestReturn(Caller caller, @PathVariable String id, @Valid @RequestBody ReturnRequest request) {
        return orders.requestReturn(caller, checked(id), request.reason().strip());
    }

    static String checked(String id) {
        if (!ID.matcher(id).matches()) {
            throw ApiException.notFound("Order");
        }
        return id;
    }

    /** 409 with the current prices, so the client can show them and ask again. */
    @ExceptionHandler(PriceChangedException.class)
    ResponseEntity<Map<String, Object>> priceChanged(PriceChangedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        body.put("code", "PRICE_CHANGED");
        body.put("lines", e.lines);
        body.put("totalCents", e.totalCents);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Load shedding carries a Retry-After the edge passes on. */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", e.getMessage());
        if (e.code() != null) {
            body.put("code", e.code());
        }
        var response = ResponseEntity.status(e.status());
        if (e.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header("Retry-After", "5");
        }
        return response.body(body);
    }
}
