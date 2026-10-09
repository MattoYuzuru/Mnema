package app.mnema.learning.billing;

import app.mnema.learning.billing.MyTaxException.Outcome;
import app.mnema.learning.platform.json.ContentJsonReader;
import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The unofficial web API of the «Мой налог» personal account ({@code https://lknpd.nalog.ru/api/v1}) over the JDK {@link HttpClient}: HTTP/1.1, the JVM trust
 * store (the chain is GlobalSign), no redirects, one deadline for the whole exchange, a response of at most 256 KiB. The calls are the taxpayer's personal
 * account web app's: login ({@code /auth/lkfl}), refresh ({@code /auth/token}), register an income ({@code /income}), annul it ({@code /cancel}), list the
 * incomes ({@code /incomes}) and read one receipt ({@code /receipt/{inn}/{uuid}/json}). The API is not documented by the tax service and can change without
 * notice; every answer is therefore checked strictly and a surprise is a failure, never a guess.
 *
 * <p><b>Payloads.</b> Decided against the reference implementations that are open source and maintained: {@code loolzaaa/mytax-client} (Java, MIT; income,
 * login, refresh, no cancel), {@code shoman4eg/moy-nalog} (PHP, MIT; income, cancel, the exact {@code comment} values {@code «Возврат средств»} and
 * {@code «Чек сформирован ошибочно»}, {@code CASH|ACCOUNT}, incomes list) and {@code varrcan/lknpd} (TypeScript, MIT; the no-duplicate recipe around
 * {@code /incomes}, 401/refresh/re-login behaviour, {@code tokenExpireIn}). Money is kopecks internally and a two-decimal {@link BigDecimal} on the wire (the service
 * line as a JSON number, {@code totalAmount} as a string), never a double. {@code paymentType} is {@code ACCOUNT}: the buyer pays by card through the bank's
 * acquiring and the money reaches the seller's bank account, it is not cash (the clients default to {@code CASH}). {@code operationTime} is the payment's
 * confirmation instant in the Europe/Moscow offset, {@code requestTime} is now.
 *
 * <p><b>Session.</b> The access token and the refresh token live in memory only. A token that expires within a minute, or a 401, is renewed once with the
 * refresh token; if that fails with a 4xx the INN and password log in again, once; if that is refused the login is blocked for {@link #LOGIN_BACKOFF} so a wrong
 * password is not hammered into an account lock. Nothing is logged but the operation and a short code: not the INN, the password, a token or a body.
 */
@Component
class MyTaxClient implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(MyTaxClient.class);
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_RESPONSE_BYTES, 8, 20_000);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter OPERATION_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(MOSCOW);
    private static final DateTimeFormatter FILTER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).withZone(MOSCOW);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final Pattern RECEIPT_UUID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** The «Мой налог» cancellation reasons are exactly two strings; a refund uses the first. */
    static final String REFUND_COMMENT = "Возврат средств";
    /** The window around the operation time in which a receipt registered by a lost request is looked for. */
    private static final Duration LOOKUP_WINDOW = Duration.ofSeconds(60);
    private static final int LOOKUP_PAGE = 100;
    private static final int LOOKUP_PAGES = 3;
    /** A token that expires within this is renewed before it is used. */
    private static final Duration RENEW_AHEAD = Duration.ofSeconds(60);
    /** After the personal account refused the login, no new login is tried for this long. */
    static final Duration LOGIN_BACKOFF = Duration.ofMinutes(15);

    /** A receipt found by {@link #findIncomes}. */
    record Found(String receiptUuid, boolean cancelled) { }

    private record Session(String token, String refreshToken, Instant expiresAt) { }

    private record Answer(int status, byte[] body) { }

    private final NpdSettings settings;
    private final UsageClock clock;
    private final HttpClient http;
    private Session session;
    private Instant loginBlockedUntil;

    MyTaxClient(NpdSettings settings, UsageClock clock) {
        this.settings = settings;
        this.clock = clock;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(settings.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Registers an income of {@code amountKopecks} for {@code serviceName} received at {@code operationTime} from an individual.
     *
     * @return the receipt number ({@code approvedReceiptUuid})
     */
    String registerIncome(String serviceName, long amountKopecks, Instant operationTime) {
        BigDecimal amount = rubles(amountKopecks);
        ObjectNode body = JSON.createObjectNode();
        body.put("operationTime", OPERATION_TIME.format(operationTime.truncatedTo(ChronoUnit.SECONDS)));
        body.put("requestTime", OPERATION_TIME.format(clock.now().truncatedTo(ChronoUnit.SECONDS)));
        ObjectNode line = body.putArray("services").addObject();
        line.put("name", serviceName);
        line.put("amount", amount);
        line.put("quantity", 1);
        body.put("totalAmount", amount.toPlainString());
        ObjectNode client = body.putObject("client");
        client.putNull("contactPhone");
        client.putNull("displayName");
        client.putNull("inn");
        client.put("incomeType", "FROM_INDIVIDUAL");
        body.put("paymentType", "ACCOUNT");
        body.put("ignoreMaxTotalIncomeRestriction", false);
        JsonNode answer = authorized("income", "POST", "/income", body.toString());
        String uuid = text(answer.path("approvedReceiptUuid"));
        if (uuid == null || !RECEIPT_UUID.matcher(uuid).matches()) throw failed("income", Outcome.MAYBE_SENT, "INVALID_RESPONSE", 200);
        return uuid;
    }

    /** Annuls the receipt {@code receiptUuid} as a refund. */
    void cancelIncome(String receiptUuid) {
        requireUuid(receiptUuid);
        ObjectNode body = JSON.createObjectNode();
        String now = OPERATION_TIME.format(clock.now().truncatedTo(ChronoUnit.SECONDS));
        body.put("operationTime", now);
        body.put("requestTime", now);
        body.put("comment", REFUND_COMMENT);
        body.put("receiptUuid", receiptUuid);
        body.putNull("partnerCode");
        JsonNode answer = authorized("cancel", "POST", "/cancel", body.toString());
        if (!answer.path("incomeInfo").isObject()) throw failed("cancel", Outcome.MAYBE_SENT, "INVALID_RESPONSE", 200);
    }

    /**
     * The receipts of this taxpayer whose operation second, total and service name are those of a request that may have been lost. «Мой налог» has no
     * idempotency key, so this fingerprint is the only way to tell "the request was lost" from "the receipt exists".
     */
    List<Found> findIncomes(Instant operationTime, long amountKopecks, String serviceName) {
        Instant at = operationTime.truncatedTo(ChronoUnit.SECONDS);
        BigDecimal amount = rubles(amountKopecks);
        List<Found> matches = new ArrayList<>();
        for (int page = 0; page < LOOKUP_PAGES; page++) {
            String query = "?from=" + encode(FILTER_TIME.format(at.minus(LOOKUP_WINDOW))) + "&to=" + encode(FILTER_TIME.format(at.plus(LOOKUP_WINDOW)))
                    + "&offset=" + page * LOOKUP_PAGE + "&limit=" + LOOKUP_PAGE + "&sortBy=" + encode("operation_time:asc");
            JsonNode answer = authorized("incomes", "GET", "/incomes" + query, null);
            JsonNode content = answer.path("content");
            if (!content.isArray() || !answer.path("hasMore").isBoolean()) throw failed("incomes", Outcome.MAYBE_SENT, "INVALID_RESPONSE", 200);
            for (JsonNode item : content) {
                String uuid = text(item.path("approvedReceiptUuid"));
                BigDecimal total = number(item.path("totalAmount"));
                Instant time = time(item.path("operationTime"));
                if (uuid == null || !RECEIPT_UUID.matcher(uuid).matches() || total == null || time == null || !item.path("services").isArray()) {
                    throw failed("incomes", Outcome.MAYBE_SENT, "INVALID_RESPONSE", 200);
                }
                if (time.truncatedTo(ChronoUnit.SECONDS).equals(at) && total.compareTo(amount) == 0 && onlyService(item.path("services"), serviceName)) {
                    matches.add(new Found(uuid, item.path("cancellationInfo").isObject()));
                }
            }
            if (!answer.path("hasMore").booleanValue()) return matches;
            if (content.isEmpty()) throw failed("incomes", Outcome.MAYBE_SENT, "INVALID_RESPONSE", 200);
        }
        throw failed("incomes", Outcome.MAYBE_SENT, "TOO_MANY_RECEIPTS", 200);
    }

    /** Whether the receipt exists and has been annulled. */
    boolean isCancelled(String receiptUuid) {
        requireUuid(receiptUuid);
        JsonNode answer = authorized("receipt", "GET", "/receipt/" + settings.inn() + "/" + receiptUuid + "/json", null);
        return answer.path("cancellationInfo").isObject();
    }

    /** Whether the personal account refused the login recently: nothing is sent until the backoff ends, so a wrong password is not hammered. */
    synchronized boolean loginBlocked() {
        return session == null && loginBlockedUntil != null && loginBlockedUntil.isAfter(clock.now());
    }

    @Override
    public void destroy() {
        http.close();
    }

    private static void requireUuid(String receiptUuid) {
        if (receiptUuid == null || !RECEIPT_UUID.matcher(receiptUuid).matches()) throw new IllegalArgumentException("Invalid receipt number");
    }

    /** Rubles with two decimals: the only number format that is exact for kopecks. */
    static BigDecimal rubles(long kopecks) {
        return BigDecimal.valueOf(kopecks, 2);
    }

    private static boolean onlyService(JsonNode services, String name) {
        if (services.size() != 1) return false;
        JsonNode line = services.get(0);
        return line.path("name").isString() && name.equals(line.path("name").stringValue(null));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.stringValue(null) : null;
    }

    private static BigDecimal number(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        if (node.isString()) {
            try {
                return new BigDecimal(node.stringValue(null));
            } catch (NumberFormatException failure) {
                return null;
            }
        }
        return null;
    }

    private static Instant time(JsonNode node) {
        if (!node.isString()) return null;
        try {
            return OffsetDateTime.parse(node.stringValue(null)).toInstant();
        } catch (DateTimeParseException failure) {
            return null;
        }
    }

    /** A call with the session's token: renewed once on a 401, then given up as {@link Outcome#AUTH}. */
    private JsonNode authorized(String operation, String method, String path, String body) {
        String token = token(null);
        try {
            return json(operation, call(operation, method, path, body, token));
        } catch (MyTaxException failure) {
            if (failure.status() != 401) throw failure;
        }
        token = token(token);
        try {
            return json(operation, call(operation, method, path, body, token));
        } catch (MyTaxException failure) {
            if (failure.status() != 401) throw failure;
            throw failed(operation, Outcome.AUTH, "HTTP_401", 401);
        }
    }

    private static JsonNode json(String operation, Answer answer) {
        try {
            return READER.read(answer.body());
        } catch (IllegalArgumentException failure) {
            throw failed(operation, Outcome.MAYBE_SENT, "INVALID_RESPONSE", answer.status());
        }
    }

    /**
     * The current access token. {@code stale} is the token the caller just saw refused (null when none): another caller may already have renewed it, and
     * then its token is used instead of a second renewal.
     */
    private synchronized String token(String stale) {
        Instant now = clock.now();
        if (session != null && !session.token().equals(stale) && session.expiresAt().isAfter(now.plus(RENEW_AHEAD))) return session.token();
        if (session != null && session.refreshToken() != null) {
            try {
                session = authenticate("refresh", "/auth/token", refreshBody(session.refreshToken()), session.refreshToken(), now);
                return session.token();
            } catch (MyTaxException failure) {
                // Only a refusal of the refresh token itself leads to a password login; a timeout or a broken connection is the caller's to retry.
                if (failure.outcome() != Outcome.REJECTED || failure.status() == 429) throw failure;
            }
        }
        if (loginBlockedUntil != null && loginBlockedUntil.isAfter(now)) throw failed("login", Outcome.AUTH, "AUTH_BLOCKED", 0);
        try {
            session = authenticate("login", "/auth/lkfl", loginBody(), null, now);
            loginBlockedUntil = null;
            return session.token();
        } catch (MyTaxException failure) {
            session = null;
            if (failure.outcome() == Outcome.REJECTED && failure.status() != 429) {
                loginBlockedUntil = now.plus(LOGIN_BACKOFF);
                throw failed("login", Outcome.AUTH, "AUTH_REJECTED", failure.status());
            }
            throw failure;
        }
    }

    private Session authenticate(String operation, String path, String body, String previousRefresh, Instant now) {
        JsonNode answer = json(operation, call(operation, "POST", path, body, null));
        String token = text(answer.path("token"));
        String refresh = text(answer.path("refreshToken"));
        Instant expires = time(answer.path("tokenExpireIn"));
        if (token == null || token.isBlank() || expires == null || expires.isBefore(now)) throw failed(operation, Outcome.MAYBE_SENT, "INVALID_RESPONSE", 200);
        // The service does not rotate the refresh token (varrcan/lknpd, spike 2.4); keep the one we hold when the answer carries none.
        return new Session(token, refresh != null && !refresh.isBlank() ? refresh : previousRefresh, expires);
    }

    private String loginBody() {
        ObjectNode body = JSON.createObjectNode();
        body.put("username", settings.inn());
        body.put("password", settings.password());
        body.set("deviceInfo", deviceInfo());
        return body.toString();
    }

    private String refreshBody(String refreshToken) {
        ObjectNode body = JSON.createObjectNode();
        body.set("deviceInfo", deviceInfo());
        body.put("refreshToken", refreshToken);
        return body.toString();
    }

    private ObjectNode deviceInfo() {
        ObjectNode info = JSON.createObjectNode();
        info.put("sourceDeviceId", settings.deviceId());
        info.put("sourceType", "WEB");
        info.put("appVersion", "1.0.0");
        info.putObject("metaDetails").put("userAgent", USER_AGENT);
        return info;
    }

    /** One HTTP exchange; the answer is a 2xx or a {@link MyTaxException}. */
    private Answer call(String operation, String method, String path, String body, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.baseUrl + path)).timeout(settings.requestTimeout)
                .header("Accept", "application/json, text/plain, */*").header("Accept-Language", "ru-RU,ru;q=0.9").header("User-Agent", USER_AGENT);
        if (path.startsWith("/auth/")) request.header("Referer", "https://lknpd.nalog.ru/auth/login");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        CompletableFuture<HttpResponse<byte[]>> exchange = http.sendAsync(request.build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
        HttpResponse<byte[]> response;
        try {
            // One deadline for the whole exchange, connect and body included (the JDK request timeout covers the headers only).
            response = exchange.get(settings.requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            exchange.cancel(true);
            throw failed(operation, Outcome.MAYBE_SENT, "TIMEOUT", 0);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof HttpConnectTimeoutException || cause instanceof java.net.ConnectException) throw failed(operation, Outcome.NOT_SENT, "UNREACHABLE", 0);
            throw failed(operation, Outcome.MAYBE_SENT, cause instanceof HttpTimeoutException ? "TIMEOUT" : "BROKEN_CONNECTION", 0);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failed(operation, Outcome.MAYBE_SENT, "INTERRUPTED", 0);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) return new Answer(status, response.body());
        if (status == 408 || status == 429) throw failed(operation, Outcome.NOT_SENT, "HTTP_" + status, status);
        if (status >= 400 && status < 500) throw failed(operation, Outcome.REJECTED, "HTTP_" + status, status);
        throw failed(operation, Outcome.MAYBE_SENT, "HTTP_" + status, status);
    }

    private static MyTaxException failed(String operation, Outcome outcome, String code, int status) {
        log.warn("npd call failed operation={} outcome={} code={}", operation, outcome, code);
        return new MyTaxException(outcome, code, status);
    }
}
