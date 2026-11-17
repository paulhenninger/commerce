package com.thirdstone.commerce.inventory;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/** Stock management for administrators. */
@RestController
@RequestMapping("/api/v1/backoffice/inventory")
class BackofficeInventoryController {

    private static final Pattern SKU = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,39}$");
    private static final Pattern ORDER_ID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    private final InventoryService inventory;

    BackofficeInventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @GetMapping("/stock")
    PageResponse<StockLevel> stock(Caller caller, @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        caller.requireAdmin();
        return inventory.list(PageRequest.of(page, size));
    }

    record SetStock(@NotNull @Min(0) @Max(1_000_000) Integer onHand) {
    }

    @PutMapping("/stock/{sku}")
    StockLevel set(Caller caller, @PathVariable String sku, @Valid @RequestBody SetStock body) {
        caller.requireAdmin();
        if (!SKU.matcher(sku).matches()) {
            throw ApiException.badRequest("A SKU is up to 40 capital letters, digits and hyphens");
        }
        return inventory.setOnHand(caller, sku, body.onHand());
    }

    @GetMapping("/reservations")
    List<Reservation> reservations(Caller caller, @RequestParam String orderId) {
        caller.requireAdmin();
        if (!ORDER_ID.matcher(orderId).matches()) {
            throw ApiException.badRequest("Not an order id");
        }
        return inventory.reservations(orderId);
    }
}
