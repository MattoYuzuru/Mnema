package app.mnema.learning.billing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A loopback «Мой налог» ({@code lknpd.nalog.ru/api/v1}): login, refresh, income, cancel, incomes list and receipt JSON, with the payload checks of the reference
 * clients. A test can break the next answers ({@link Mode}), lose an answer after the receipt was registered (the case that makes duplicates), or revoke
 * every token. No real taxpayer, password or network is ever involved.
 */
final class FakeMyTax implements AutoCloseable {
    static final String INN = "770123456789";
    static final String PASSWORD = "npd$Pa55word-Secret";
    static final String PASSWORD_BASE64 = java.util.Base64.getEncoder().encodeToString(PASSWORD.getBytes(StandardCharsets.UTF_8));

    /** How the next {@code /income} or {@code /cancel} ends. */
    enum Mode {
        OK,
        /** The receipt is registered but the answer takes longer than the client waits. */
        LOST_ANSWER,
        /** The receipt is registered, then the service answers 500. */
        SERVER_ERROR_AFTER,
        /** 500 and nothing registered. */
        SERVER_ERROR_BEFORE,
        /** 429 and nothing registered. */
        RATE_LIMITED,
        /** 422 and nothing registered. */
        REJECTED
    }

    static final class Receipt {
        final String uuid;
        final String name;
        final BigDecimal amount;
        final String operationTime;
        final String paymentType;
        volatile boolean cancelled;

        Receipt(String uuid, String name, BigDecimal amount, String operationTime, String paymentType) {
            this.uuid = uuid;
            this.name = name;
            this.amount = amount;
            this.operationTime = operationTime;
            this.paymentType = paymentType;
        }
    }

    private final HttpServer server;
    private final Supplier<Instant> now;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final Set<String> refreshTokens = ConcurrentHashMap.newKeySet();
    final List<Receipt> receipts = new CopyOnWriteArrayList<>();
    final List<ObjectNode> logins = new CopyOnWriteArrayList<>();
    final List<ObjectNode> refreshes = new CopyOnWriteArrayList<>();
    final List<ObjectNode> incomes = new CopyOnWriteArrayList<>();
    final List<ObjectNode> cancels = new CopyOnWriteArrayList<>();
    final List<String> incomeQueries = new CopyOnWriteArrayList<>();
    final List<String> userAgents = new CopyOnWriteArrayList<>();
    final AtomicInteger receiptReads = new AtomicInteger();
    volatile long tokenLifetimeSeconds = 3_600;
    volatile boolean rejectLogin;
    volatile boolean rejectRefresh;
    volatile boolean listBroken;
    /** Refuse every token, however fresh: the case that ends as AUTH after one renewal. */
    volatile boolean denyAll;
    volatile Mode incomeMode = Mode.OK;
    volatile Mode cancelMode = Mode.OK;
    /** Answer {@code /income} with this body instead of the receipt number when not null. */
    volatile String incomeBody;
    volatile long lostAnswerMillis = 1_500;
    volatile int redirectStatus;

    FakeMyTax(Supplier<Instant> now) {
        this.now = now;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/v1/auth/lkfl", exchange -> handle(exchange, false, this::login));
        server.createContext("/api/v1/auth/token", exchange -> handle(exchange, false, this::refresh));
        server.createContext("/api/v1/income", exchange -> handle(exchange, true, this::income));
        server.createContext("/api/v1/cancel", exchange -> handle(exchange, true, this::cancel));
        server.createContext("/api/v1/incomes", exchange -> handle(exchange, true, this::list));
        server.createContext("/api/v1/receipt/", exchange -> handle(exchange, true, this::receipt));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    void revokeTokens() {
        tokens.clear();
    }

    void reset() {
        rejectLogin = false;
        rejectRefresh = false;
        listBroken = false;
        denyAll = false;
        incomeMode = Mode.OK;
        cancelMode = Mode.OK;
        incomeBody = null;
        redirectStatus = 0;
        tokenLifetimeSeconds = 3_600;
        tokens.clear();
        refreshTokens.clear();
        receipts.clear();
        logins.clear();
        refreshes.clear();
        incomes.clear();
        cancels.clear();
        incomeQueries.clear();
        userAgents.clear();
        receiptReads.set(0);
    }

    /** Registers a receipt the way a request of another client (or of a lost answer) would have. */
    Receipt register(String name, BigDecimal amount, String operationTime) {
        Receipt receipt = new Receipt("r" + Integer.toString(sequence.incrementAndGet(), 36) + "x" + Long.toString(System.nanoTime() % 100_000, 36), name,
                amount, operationTime, "ACCOUNT");
        receipts.add(receipt);
        return receipt;
    }

    private interface Handler {
        Reply handle(HttpExchange exchange, ObjectNode request) throws Exception;
    }

    private record Reply(int status, String body, long delayMillis) {
        static Reply ok(JsonNode body) {
            return new Reply(200, body.toString(), 0);
        }

        static Reply status(int status) {
            return new Reply(status, "{\"code\":\"error\",\"message\":\"x\"}", 0);
        }
    }

    private void handle(HttpExchange exchange, boolean authorized, Handler handler) throws IOException {
        try (exchange) {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            ObjectNode request = raw.length == 0 ? BillingFixtures.JSON.createObjectNode() : (ObjectNode) BillingFixtures.JSON.readTree(new String(raw, StandardCharsets.UTF_8));
            String agent = exchange.getRequestHeaders().getFirst("User-Agent");
            if (agent != null) userAgents.add(agent);
            Reply reply;
            if (redirectStatus != 0) {
                exchange.getResponseHeaders().set("Location", "https://example.invalid/");
                exchange.sendResponseHeaders(redirectStatus, -1);
                return;
            }
            if (authorized && !validToken(exchange.getRequestHeaders().getFirst("Authorization"))) {
                reply = Reply.status(401);
            } else {
                try {
                    reply = handler.handle(exchange, request);
                } catch (IOException failure) {
                    throw failure;
                } catch (Exception failure) {
                    reply = Reply.status(400);
                }
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private boolean validToken(String header) {
        if (denyAll || header == null || !header.startsWith("Bearer ")) return false;
        Instant expires = tokens.get(header.substring("Bearer ".length()));
        return expires != null && expires.isAfter(now.get());
    }

    private ObjectNode session(boolean withRefresh) {
        Instant expires = now.get().plusSeconds(tokenLifetimeSeconds);
        String token = "access-" + sequence.incrementAndGet() + "-" + Long.toString(System.nanoTime(), 36);
        tokens.put(token, expires);
        ObjectNode body = BillingFixtures.JSON.createObjectNode();
        body.put("token", token);
        body.put("tokenExpireIn", expires.toString());
        if (withRefresh) {
            String refresh = "refresh-" + sequence.incrementAndGet() + "-" + Long.toString(System.nanoTime(), 36);
            refreshTokens.add(refresh);
            body.put("refreshToken", refresh);
            body.putObject("profile").put("inn", INN);
        }
        return body;
    }

    private Reply login(HttpExchange exchange, ObjectNode request) {
        logins.add(request);
        JsonNode device = request.path("deviceInfo");
        boolean shaped = device.path("sourceType").stringValue(null) != null && "WEB".equals(device.path("sourceType").stringValue(null))
                && !device.path("sourceDeviceId").stringValue("").isBlank() && device.path("metaDetails").path("userAgent").isString();
        if (rejectLogin || !shaped || !INN.equals(request.path("username").stringValue(null)) || !PASSWORD.equals(request.path("password").stringValue(null))) {
            return Reply.status(422);
        }
        return Reply.ok(session(true));
    }

    private Reply refresh(HttpExchange exchange, ObjectNode request) {
        refreshes.add(request);
        if (rejectRefresh || !refreshTokens.contains(request.path("refreshToken").stringValue(""))) return Reply.status(401);
        return Reply.ok(session(false));
    }

    private Reply income(HttpExchange exchange, ObjectNode request) {
        incomes.add(request);
        Mode mode = incomeMode;
        if (mode == Mode.RATE_LIMITED) return Reply.status(429);
        if (mode == Mode.REJECTED) return Reply.status(422);
        if (mode == Mode.SERVER_ERROR_BEFORE) return Reply.status(500);
        JsonNode line = request.path("services").path(0);
        Receipt receipt = register(line.path("name").stringValue(null), line.path("amount").decimalValue(), request.path("operationTime").stringValue(null));
        if (mode == Mode.LOST_ANSWER) return new Reply(200, "{\"approvedReceiptUuid\":\"" + receipt.uuid + "\"}", lostAnswerMillis);
        if (mode == Mode.SERVER_ERROR_AFTER) return Reply.status(500);
        if (incomeBody != null) return new Reply(200, incomeBody, 0);
        return Reply.ok(BillingFixtures.JSON.createObjectNode().put("approvedReceiptUuid", receipt.uuid));
    }

    private Reply cancel(HttpExchange exchange, ObjectNode request) {
        cancels.add(request);
        Mode mode = cancelMode;
        if (mode == Mode.RATE_LIMITED) return Reply.status(429);
        if (mode == Mode.REJECTED) return Reply.status(422);
        if (mode == Mode.SERVER_ERROR_BEFORE) return Reply.status(500);
        Receipt receipt = receipts.stream().filter(candidate -> candidate.uuid.equals(request.path("receiptUuid").stringValue(null))).findFirst().orElse(null);
        if (receipt == null || receipt.cancelled) return Reply.status(422);
        receipt.cancelled = true;
        if (mode == Mode.LOST_ANSWER) return new Reply(200, "{\"incomeInfo\":{}}", lostAnswerMillis);
        if (mode == Mode.SERVER_ERROR_AFTER) return Reply.status(500);
        ObjectNode body = BillingFixtures.JSON.createObjectNode();
        body.putObject("incomeInfo").put("approvedReceiptUuid", receipt.uuid);
        return Reply.ok(body);
    }

    private Reply list(HttpExchange exchange, ObjectNode request) {
        String query = exchange.getRequestURI().getRawQuery();
        incomeQueries.add(query);
        if (listBroken) return Reply.status(500);
        Map<String, String> parameters = new HashMap<>();
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        Instant from = OffsetDateTime.parse(parameters.get("from")).toInstant();
        Instant to = OffsetDateTime.parse(parameters.get("to")).toInstant();
        ArrayNode content = BillingFixtures.JSON.createArrayNode();
        for (Receipt receipt : receipts) {
            Instant at = OffsetDateTime.parse(receipt.operationTime).toInstant();
            if (at.isBefore(from) || at.isAfter(to)) continue;
            ObjectNode item = content.addObject();
            item.put("approvedReceiptUuid", receipt.uuid);
            item.put("name", receipt.name);
            ObjectNode service = item.putArray("services").addObject();
            service.put("name", receipt.name);
            service.put("amount", receipt.amount);
            service.put("quantity", 1);
            item.put("operationTime", receipt.operationTime);
            item.put("paymentType", receipt.paymentType);
            item.put("totalAmount", receipt.amount);
            if (receipt.cancelled) item.putObject("cancellationInfo").put("comment", "Возврат средств");
            else item.putNull("cancellationInfo");
        }
        ObjectNode body = BillingFixtures.JSON.createObjectNode();
        body.set("content", content);
        body.put("hasMore", false);
        body.put("currentOffset", 0);
        body.put("currentLimit", 100);
        return Reply.ok(body);
    }

    private Reply receipt(HttpExchange exchange, ObjectNode request) {
        receiptReads.incrementAndGet();
        String[] path = exchange.getRequestURI().getPath().split("/");
        // /api/v1/receipt/{inn}/{uuid}/json
        if (path.length != 7 || !INN.equals(path[4]) || !"json".equals(path[6])) return Reply.status(404);
        Receipt receipt = receipts.stream().filter(candidate -> candidate.uuid.equals(path[5])).findFirst().orElse(null);
        if (receipt == null) return Reply.status(404);
        ObjectNode body = BillingFixtures.JSON.createObjectNode();
        body.put("receiptId", receipt.uuid);
        if (receipt.cancelled) body.putObject("cancellationInfo").put("comment", "Возврат средств");
        else body.putNull("cancellationInfo");
        return Reply.ok(body);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
