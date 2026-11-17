package com.thirdstone.commerce.catalog;

import com.thirdstone.commerce.platform.data.Sql;
import com.thirdstone.commerce.platform.web.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
class ProductRepository {

    private static final RowMapper<Product> PRODUCT = (rs, n) -> new Product(rs.getString("sku"),
            rs.getString("name"), rs.getString("description"), rs.getString("category"), rs.getLong("price_cents"),
            rs.getString("currency"), rs.getString("image_key"), rs.getBoolean("active"), rs.getInt("available"),
            Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Product> find(String sku) {
        return jdbc.sql("SELECT * FROM products WHERE sku = ?").param(sku).query(PRODUCT).optional();
    }

    /** @param activeOnly true for the shop; false for the back office, which also sees withdrawn products */
    List<Product> list(boolean activeOnly, String category, String q, PageRequest page) {
        List<Object> params = new ArrayList<>();
        String where = where(activeOnly, category, q, params);
        params.add(page.size());
        params.add(page.offset());
        return jdbc.sql("SELECT * FROM products" + where + " ORDER BY name, sku LIMIT ? OFFSET ?")
                .params(params).query(PRODUCT).list();
    }

    long count(boolean activeOnly, String category, String q) {
        List<Object> params = new ArrayList<>();
        String where = where(activeOnly, category, q, params);
        return jdbc.sql("SELECT COUNT(*) FROM products" + where).params(params).query(Long.class).single();
    }

    private static String where(boolean activeOnly, String category, String q, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        if (activeOnly) {
            conditions.add("active = TRUE");
        }
        if (category != null && !category.isBlank()) {
            conditions.add("category = ?");
            params.add(category);
        }
        if (q != null && !q.isBlank()) {
            // A substring match: fine for hundreds of products behind three caches,
            // and a known limit beyond that (architecture, section 15). The
            // wildcards a shopper types are matched literally.
            conditions.add("name LIKE ? ESCAPE '!'");
            params.add("%" + q.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    List<String> categories() {
        return jdbc.sql("SELECT DISTINCT category FROM products WHERE active = TRUE ORDER BY category")
                .query(String.class).list();
    }

    void upsert(String sku, ProductInput in) {
        jdbc.sql("""
                        INSERT INTO products (sku, name, description, category, price_cents, currency, image_key,
                                              active, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description),
                            category = VALUES(category), price_cents = VALUES(price_cents),
                            currency = VALUES(currency), image_key = VALUES(image_key), active = VALUES(active),
                            updated_at = VALUES(updated_at)""")
                .params(sku, in.name(), in.description() == null ? "" : in.description(), in.category(),
                        in.priceCents(), in.currency(), in.imageKey(), in.active())
                .update();
    }

    int setAvailable(String sku, int available) {
        return jdbc.sql("UPDATE products SET available = ? WHERE sku = ?").params(available, sku).update();
    }
}
