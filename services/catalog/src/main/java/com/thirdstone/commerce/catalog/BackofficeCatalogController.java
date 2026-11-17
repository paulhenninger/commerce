package com.thirdstone.commerce.catalog;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.Caller;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product management for administrators. The edge has already required an
 * administrator with a two-factor session for this path; the role is checked
 * again here so the service does not depend on the edge's routing being right.
 */
@RestController
@RequestMapping("/api/v1/backoffice/catalog")
class BackofficeCatalogController {

    private final CatalogService catalog;

    BackofficeCatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    PageResponse<Product> products(Caller caller, @RequestParam(required = false) String q,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        caller.requireAdmin();
        return catalog.all(q, PageRequest.of(page, size));
    }

    @PutMapping("/products/{sku}")
    Product upsert(Caller caller, @PathVariable String sku, @Valid @RequestBody ProductInput input) {
        caller.requireAdmin();
        if (!CatalogController.SKU.matcher(sku).matches()) {
            throw ApiException.badRequest("A SKU is up to 40 capital letters, digits and hyphens");
        }
        return catalog.upsert(caller, sku, input);
    }
}
