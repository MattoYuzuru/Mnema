package app.mnema.learning.billing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A loopback T-Bank: {@code /v2/Init} and {@code /v2/GetState} answer from the recorded fixtures, adapted to the request. It verifies the {@code Token} of
 * every request with the fixture password (a request that fails is counted in {@link #badSignatures}), remembers each payment it opened and lets a test move
 * a payment to any bank status or break the next answers. Real money, a real terminal and the network are never involved.
 */
final class FakeBank implements AutoCloseable {
    /** A payment the bank opened. */
    static final class Payment {
        final String paymentId;
        final String orderId;
        final long amount;
        volatile String status = "NEW";
        volatile String terminalKey = BillingFixtures.TERMINAL;
        volatile String answeredOrderId;
        volatile Long answeredAmount;

        Payment(String paymentId, String orderId, long amount) {
            this.paymentId = paymentId;
            this.orderId = orderId;
            this.amount = amount;
        }
    }

    private final HttpServer server;
    private final AtomicLong sequence = new AtomicLong(8_000_000_000L);
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    final List<ObjectNode> inits = new CopyOnWriteArrayList<>();
    final List<ObjectNode> getStates = new CopyOnWriteArrayList<>();
    /** Requests that reached the bank, counted before any delay: a test can wait for one that is still being answered. */
    final java.util.concurrent.atomic.AtomicInteger arrivals = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger badSignatures = new java.util.concurrent.atomic.AtomicInteger();
    /** Answer {@code Init} with the recorded error 204 while true. */
    volatile boolean refuseInit;
    /** Answer {@code GetState} with HTTP 500 while true. */
    volatile boolean breakGetState;
    /** Replaces the {@code PaymentURL} of the next answers when not null. */
    volatile String paymentUrl = "https://pay.tbank-online.com/So6mQeQB";
    volatile long delayMillis;

    FakeBank() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v2/Init", exchange -> answer(exchange, this::init));
        server.createContext("/v2/GetState", exchange -> answer(exchange, this::getState));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2";
    }

    Payment paymentOf(Object orderId) {
        return payments.values().stream().filter(payment -> payment.orderId.equals(orderId.toString())).findFirst().orElseThrow();
    }

    /** Moves a payment to {@code status}, as the bank would after the payer acted. */
    void move(Object orderId, String status) {
        paymentOf(orderId).status = status;
    }

    void reset() {
        refuseInit = false;
        breakGetState = false;
        delayMillis = 0;
        paymentUrl = "https://pay.tbank-online.com/So6mQeQB";
        inits.clear();
        getStates.clear();
        badSignatures.set(0);
        arrivals.set(0);
    }

    private interface Handler {
        ObjectNode handle(ObjectNode request) throws IOException;
    }

    private void answer(HttpExchange exchange, Handler handler) throws IOException {
        try (exchange) {
            byte[] request = exchange.getRequestBody().readAllBytes();
            ObjectNode parsed = (ObjectNode) BillingFixtures.JSON.readTree(new String(request, StandardCharsets.UTF_8));
            if (!TBankToken.verify(parsed, BillingFixtures.PASSWORD)) badSignatures.incrementAndGet();
            arrivals.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
            ObjectNode body;
            try {
                body = handler.handle(parsed);
            } catch (IllegalStateException failure) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private ObjectNode init(ObjectNode request) {
        inits.add(request);
        if (refuseInit) return BillingFixtures.tbank("init-response-error.json");
        String paymentId = Long.toString(sequence.incrementAndGet());
        Payment payment = new Payment(paymentId, request.path("OrderId").stringValue(null), request.path("Amount").longValue());
        payments.put(paymentId, payment);
        ObjectNode body = BillingFixtures.tbank("init-response-success.json");
        body.put("PaymentId", paymentId);
        body.put("OrderId", payment.orderId);
        body.put("Amount", payment.amount);
        body.put("PaymentURL", paymentUrl);
        return body;
    }

    private ObjectNode getState(ObjectNode request) {
        getStates.add(request);
        if (breakGetState) throw new IllegalStateException("bank down");
        Payment payment = payments.get(request.path("PaymentId").stringValue(null));
        if (payment == null) return BillingFixtures.tbank("get-state-response-not-found.json");
        ObjectNode body = BillingFixtures.tbank("get-state-response-confirmed.json");
        boolean refused = payment.status.equals("REJECTED") || payment.status.equals("AUTH_FAIL");
        body.put("Success", !refused);
        body.put("ErrorCode", refused ? "1051" : "0");
        body.put("TerminalKey", payment.terminalKey);
        body.put("Status", payment.status);
        body.put("PaymentId", payment.paymentId);
        body.put("OrderId", payment.answeredOrderId == null ? payment.orderId : payment.answeredOrderId);
        body.put("Amount", payment.answeredAmount == null ? payment.amount : payment.answeredAmount);
        return body;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** The members of the JSON the bank last received for {@code orderId}'s Init, for assertions. */
    JsonNode lastInit() {
        return inits.getLast();
    }
}
