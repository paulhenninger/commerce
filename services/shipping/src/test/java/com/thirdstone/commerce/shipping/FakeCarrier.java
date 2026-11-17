package com.thirdstone.commerce.shipping;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A carrier for tests. Each status request moves a parcel one step on
 * (outbound BOOKED → DISPATCHED → DELIVERED, return → IN_TRANSIT → RECEIVED),
 * unless it is {@link #hold held}; {@link #down} makes every call a 503.
 */
final class FakeCarrier {

    static final AtomicBoolean down = new AtomicBoolean();
    static final Map<String, Boolean> hold = new ConcurrentHashMap<>();
    static final List<String> bookings = new CopyOnWriteArrayList<>();
    private static final Map<String, String> statusByTracking = new ConcurrentHashMap<>();
    private static final Map<String, String> trackingByKey = new ConcurrentHashMap<>();
    private static final AtomicInteger next = new AtomicInteger(1000);
    private static final HttpServer SERVER;

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String answer;
            if (down.get()) {
                status = 503;
                answer = "{}";
            } else if (exchange.getRequestMethod().equals("POST")) {
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                bookings.add(key);
                boolean ret = body.contains("RETURN");
                String tracking = trackingByKey.computeIfAbsent(key, k -> {
                    String t = (ret ? "RET" : "TRK") + next.incrementAndGet();
                    statusByTracking.put(t, "BOOKED");
                    return t;
                });
                status = 201;
                answer = "{\"carrier\":\"FakeShip\",\"trackingNumber\":\"" + tracking + "\"}";
            } else {
                String tracking = path.substring(path.lastIndexOf('/') + 1);
                String current = statusByTracking.getOrDefault(tracking, "BOOKED");
                if (!hold.getOrDefault(tracking, false)) {
                    boolean ret = tracking.startsWith("RET");
                    current = switch (current) {
                        case "BOOKED" -> ret ? "IN_TRANSIT" : "DISPATCHED";
                        case "DISPATCHED" -> "DELIVERED";
                        case "IN_TRANSIT" -> "RECEIVED";
                        default -> current;
                    };
                    statusByTracking.put(tracking, current);
                }
                answer = "{\"status\":\"" + current + "\"}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        SERVER.start();
    }

    static String url() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort();
    }

    private FakeCarrier() {
    }
}
