package com.thirdstone.commerce.testkit.shop;

import com.thirdstone.commerce.testkit.TestkitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes a fresh DEV stack a shop worth browsing: the administrator and a
 * shopper account, about two dozen products and their stock — all through the
 * real sign-up flow and the back-office API, as people would. Idempotent: run
 * again, it changes nothing that is already there. Keeps stock topped up so the
 * simulation never runs dry, except for one product that is always sold out.
 */
@Component
public class Seeder {

    private static final Logger log = LoggerFactory.getLogger(Seeder.class);
    public static final String SHOPPER_EMAIL = "shopper@example.com";
    public static final String SOLD_OUT = "SOLD-OUT-LAMP";
    static final String ADMIN_IP = "198.18.255.1";
    static final String SHOPPER_IP = "198.18.255.2";

    record Product(String sku, String name, String category, long priceCents, String description) {
    }

    static final List<Product> PRODUCTS = List.of(
            new Product("MUG-BLUE", "Blue enamel mug", "kitchen", 1250, "A sturdy enamel mug, 350 ml."),
            new Product("MUG-RED", "Red enamel mug", "kitchen", 1250, "The same mug, in red."),
            new Product("PLATE-SET", "Stoneware plate set", "kitchen", 4800, "Four plates, glazed stoneware."),
            new Product("KNIFE-CHEF", "Chef's knife", "kitchen", 6900, "20 cm carbon steel blade."),
            new Product("BOARD-OAK", "Oak chopping board", "kitchen", 3500, "End-grain oak, oiled."),
            new Product("KETTLE-STEEL", "Stovetop kettle", "kitchen", 4200, "Stainless steel, 1.5 l."),
            new Product("TEE-WHITE-M", "White T-shirt (M)", "clothing", 1999, "Organic cotton, medium."),
            new Product("TEE-WHITE-L", "White T-shirt (L)", "clothing", 1999, "Organic cotton, large."),
            new Product("HOODIE-GREY", "Grey hoodie", "clothing", 5500, "Brushed fleece, unisex."),
            new Product("SOCKS-WOOL", "Wool socks", "clothing", 1400, "Merino blend, three pairs."),
            new Product("CAP-NAVY", "Navy cap", "clothing", 2200, "Cotton twill, adjustable."),
            new Product("SCARF-KNIT", "Knitted scarf", "clothing", 3100, "Lambswool, 180 cm."),
            new Product("BOOK-COOK", "Weeknight cookbook", "books", 2600, "120 recipes, 30 minutes each."),
            new Product("BOOK-MAPS", "Atlas of small places", "books", 3400, "Hand-drawn maps."),
            new Product("BOOK-PUZZLE", "Puzzle collection", "books", 1500, "Two hundred logic puzzles."),
            new Product("NOTEBOOK-A5", "A5 notebook", "stationery", 900, "Dot grid, 160 pages."),
            new Product("PEN-FOUNTAIN", "Fountain pen", "stationery", 3900, "Steel nib, refillable."),
            new Product("INK-BLUE", "Blue ink bottle", "stationery", 1100, "50 ml."),
            new Product("LAMP-DESK", "Desk lamp", "home", 6400, "Adjustable arm, warm LED."),
            new Product("CANDLE-PINE", "Pine candle", "home", 1800, "Soy wax, 40 hours."),
            new Product("PLANT-POT", "Terracotta pot", "home", 1600, "18 cm, with saucer."),
            new Product("THROW-WOOL", "Wool throw", "home", 8900, "130 × 170 cm."),
            new Product("CLOCK-WALL", "Wall clock", "home", 4500, "Silent sweep movement."),
            new Product(SOLD_OUT, "Sold-out lamp", "home", 9900, "Always out of stock: for the simulation."));

    private final Accounts accounts;
    private final Storefront storefront;
    private final TestkitProperties props;
    private final AtomicBoolean seeded = new AtomicBoolean();
    private volatile String adminToken;

    Seeder(Accounts accounts, Storefront storefront, TestkitProperties props) {
        this.accounts = accounts;
        this.storefront = storefront;
        this.props = props;
    }

    public boolean seeded() {
        return seeded.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        Thread.ofVirtual().name("testkit-seed").start(() -> {
            for (int attempt = 1; !seeded.get(); attempt++) {
                try {
                    seed();
                    seeded.set(true);
                    log.atInfo().addKeyValue("products", PRODUCTS.size()).log("DEV data seeded");
                } catch (RuntimeException e) {
                    log.atWarn().addKeyValue("attempt", attempt).log("Seeding not possible yet: {}", e.getMessage());
                    Accounts.sleep(Math.min(30_000, 2_000L * attempt));
                }
            }
        });
    }

    void seed() {
        if (props.adminPassword() == null || props.adminPassword().isBlank()
                || props.shopperPassword() == null || props.shopperPassword().isBlank()) {
            throw new IllegalStateException("DEV_ADMIN_PASSWORD and DEV_SHOPPER_PASSWORD must be set");
        }
        adminToken = accounts.signIn(props.adminEmail(), props.adminPassword(), ADMIN_IP);
        accounts.signIn(SHOPPER_EMAIL, props.shopperPassword(), SHOPPER_IP);
        for (Product p : PRODUCTS) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", p.name());
            body.put("description", p.description());
            body.put("category", p.category());
            body.put("priceCents", p.priceCents());
            body.put("currency", "USD");
            body.put("imageKey", null);
            body.put("active", true);
            require(storefront.put("/api/v1/backoffice/catalog/products/" + p.sku(), ADMIN_IP, adminToken, body),
                    "product " + p.sku());
        }
        topUp();
    }

    /** Keeps every product but the sold-out one well stocked. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    void topUp() {
        if (adminToken == null) {
            return;
        }
        Map<String, JsonNode> stock = new LinkedHashMap<>();
        for (int page = 0; page < 10; page++) {
            Storefront.Answer answer = storefront.get("/api/v1/backoffice/inventory/stock?size=50&page=" + page,
                    ADMIN_IP, admin());
            if (!answer.ok()) {
                return;
            }
            answer.body().path("items").forEach(s -> stock.put(s.path("sku").asString(), s));
            if (answer.body().path("items").size() < 50) {
                break;
            }
        }
        for (Product p : PRODUCTS) {
            JsonNode level = stock.get(p.sku());
            int onHand = level == null ? 0 : level.path("onHand").asInt();
            int available = level == null ? 0 : level.path("available").asInt();
            int wanted = p.sku().equals(SOLD_OUT) ? 0 : available < 200 ? onHand + 500 : -1;
            if (wanted >= 0 && (level == null || wanted != onHand)) {
                storefront.put("/api/v1/backoffice/inventory/stock/" + p.sku(), ADMIN_IP, admin(),
                        Map.of("onHand", wanted));
            }
        }
    }

    /** The administrator's token, signing in again when it has expired. */
    private String admin() {
        Storefront.Answer me = storefront.get("/api/v1/users/me", ADMIN_IP, adminToken);
        if (me.status() == 401) {
            adminToken = accounts.signIn(props.adminEmail(), props.adminPassword(), ADMIN_IP);
        }
        return adminToken;
    }

    private static void require(Storefront.Answer answer, String what) {
        if (!answer.ok()) {
            throw new IllegalStateException("Could not seed " + what + ": " + answer.status() + " " + answer.body());
        }
    }
}
