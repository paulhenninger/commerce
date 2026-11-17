package com.thirdstone.commerce.catalog;

import com.thirdstone.commerce.platform.web.ApiException;
import com.thirdstone.commerce.platform.web.PageRequest;
import com.thirdstone.commerce.platform.web.PageResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/** The shop window: public, read-only, and cacheable by anyone for a few seconds. */
@RestController
@RequestMapping("/api/v1/catalog")
class CatalogController {

    static final Pattern SKU = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,39}$");
    private static final CacheControl SHORT = CacheControl.maxAge(Duration.ofSeconds(5)).cachePublic();

    private final CatalogService catalog;

    CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    ResponseEntity<PageResponse<Product>> products(@RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        if ((category != null && category.length() > 64) || (q != null && q.length() > 100)) {
            throw ApiException.badRequest("Search text is too long");
        }
        return ResponseEntity.ok().cacheControl(SHORT).body(catalog.browse(category, q, PageRequest.of(page, size)));
    }

    @GetMapping("/products/{sku}")
    ResponseEntity<Product> product(@PathVariable String sku) {
        if (!SKU.matcher(sku).matches()) {
            throw ApiException.notFound("Product");
        }
        return ResponseEntity.ok().cacheControl(SHORT).body(catalog.product(sku));
    }

    @GetMapping("/categories")
    ResponseEntity<List<String>> categories() {
        return ResponseEntity.ok().cacheControl(SHORT).body(catalog.categories());
    }
}
