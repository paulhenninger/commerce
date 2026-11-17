package com.thirdstone.commerce.billing;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A payment provider for tests: approves, except tok_decline (402) and tok_down
 * (503). Records every request so tests can count charges and refunds.
 */
final class FakeProvider {

    record Call(String path, String idempotencyKey, String body) {
    }

    static final List<Call> CALLS = new CopyOnWriteArrayList<>();
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
            CALLS.add(new Call(path, exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));
            int status;
            String answer;
            if (!"Bearer test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                status = 401;
                answer = "{}";
            } else if (body.contains("tok_down") || body.contains("ch_down")) {
                status = 503;
                answer = "{}";
            } else if (body.contains("tok_decline")) {
                status = 402;
                answer = "{\"code\":\"DECLINED\"}";
            } else if (path.endsWith("/refunds")) {
                status = 201;
                answer = "{\"id\":\"re_1\"}";
            } else {
                status = 201;
                answer = "{\"id\":\"ch_" + CALLS.size() + "\",\"status\":\"APPROVED\"}";
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

    static List<Call> callsFor(String orderId) {
        return CALLS.stream().filter(c -> c.idempotencyKey() != null && c.idempotencyKey().endsWith(orderId)).toList();
    }

    private FakeProvider() {
    }
}
