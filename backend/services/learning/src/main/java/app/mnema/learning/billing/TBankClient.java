package app.mnema.learning.billing;

import app.mnema.learning.billing.PaymentProviderException.Reason;
import app.mnema.learning.platform.json.ContentJsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The T-Bank internet-acquiring API ({@code Init}, {@code GetState}; {@code contracts/billing/tbank}) over the JDK {@link HttpClient}: HTTP/1.1, the bank's
 * own trust store ({@link TBankTrust}), no redirects, a connect and a request timeout, a response of at most 64 KiB. Blocking calls are fine on a virtual
 * thread. Every request is signed ({@link TBankToken}); no request body, response body, {@code Message}, {@code Details} or credential is ever logged,
 * only the operation, the failure reason and the bank's numeric {@code ErrorCode}.
 *
 * <p>The hosted payment form belongs to the bank, so the only URL the client trusts the bank to name is {@code PaymentURL}, and only on an allowlisted
 * host over https; anything else is a provider failure.
 */
@Component
class TBankClient implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(TBankClient.class);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_RESPONSE_BYTES, 6, 2_000);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PAYMENT_HOSTS = Set.of("pay.tbank-online.com", "securepay.tinkoff.ru", "securepay.tbank.ru");
    private static final DateTimeFormatter DUE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
            .withZone(ZoneId.of("Europe/Moscow"));
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,20}");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_]{1,20}");
    private static final Pattern STATUS = Pattern.compile("[A-Z0-9_]{1,40}");

    /** What {@code Init} returns: the bank's payment and the link of its hosted form. */
    record InitResult(String paymentId, String paymentUrl, String status) { }

    private final BillingSettings settings;
    private final HttpClient http;

    TBankClient(BillingSettings settings, TBankTrust trust) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(settings.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).sslContext(trust.sslContext()).build();
    }

    /** Opens a payment for {@code order}. */
    InitResult init(BillingOrder order) {
        requireConfigured("init");
        ObjectNode body = JSON.createObjectNode();
        body.put("TerminalKey", settings.terminalKey);
        body.put("Amount", order.amountKopecks());
        body.put("OrderId", order.orderId().toString());
        body.put("Description", "Mnema " + label(order) + " — 1 месяц");
        body.put("Language", "ru");
        body.put("PayType", "O");
        body.put("NotificationURL", settings.publicBaseUrl + "/api/billing/tbank/notifications");
        String returnUrl = settings.publicBaseUrl + "/plans/payment/" + order.orderId();
        body.put("SuccessURL", returnUrl);
        body.put("FailURL", returnUrl);
        body.put("RedirectDueDate", DUE_DATE.format(order.expiresAt()));
        JsonNode answer = post("init", "Init", body);
        requireSuccess("init", answer);
        String paymentId = paymentId(answer.path("PaymentId"));
        String url = answer.path("PaymentURL").isString() ? answer.path("PaymentURL").stringValue(null) : null;
        String status = answer.path("Status").isString() ? answer.path("Status").stringValue(null) : null;
        boolean same = paymentId != null && allowedPaymentUrl(url) && status != null && STATUS.matcher(status).matches()
                && order.orderId().toString().equals(answer.path("OrderId").stringValue(null))
                && answer.path("Amount").isIntegralNumber() && answer.path("Amount").canConvertToLong()
                && answer.path("Amount").longValue() == order.amountKopecks()
                && (answer.path("TerminalKey").isMissingNode() || settings.isOwnTerminal(answer.path("TerminalKey").stringValue(null)));
        if (!same) throw failed("init", Reason.INVALID_RESPONSE, null);
        return new InitResult(paymentId, url, status);
    }

    /** Asks the bank for the state of {@code paymentId}. */
    BankState getState(String paymentId) {
        requireConfigured("get_state");
        ObjectNode body = JSON.createObjectNode();
        body.put("TerminalKey", settings.terminalKey);
        body.put("PaymentId", paymentId);
        JsonNode answer = post("get_state", "GetState", body);
        boolean success = answer.path("Success").isBoolean() && answer.path("Success").booleanValue();
        // A payment the bank refused (REJECTED, AUTH_FAIL) is answered with Success:false and its Status; only an answer without a payment is a failure.
        if (!success && !answer.path("Status").isString()) requireSuccess("get_state", answer);
        String status = answer.path("Status").isString() ? answer.path("Status").stringValue(null) : null;
        String state = paymentId(answer.path("PaymentId"));
        if (status == null || !STATUS.matcher(status).matches() || state == null || !state.equals(paymentId)) throw failed("get_state", Reason.INVALID_RESPONSE, null);
        Long amount = answer.path("Amount").isIntegralNumber() && answer.path("Amount").canConvertToLong() ? answer.path("Amount").longValue() : null;
        return new BankState(text(answer.path("TerminalKey")), text(answer.path("OrderId")), state, status, amount, success,
                errorCode(answer));
    }

    @Override
    public void destroy() {
        http.close();
    }

    private static String label(BillingOrder order) {
        return order.plan().name().charAt(0) + order.plan().name().substring(1).toLowerCase(Locale.ROOT);
    }

    private void requireConfigured(String operation) {
        if (!settings.configured()) throw failed(operation, Reason.NOT_CONFIGURED, null);
    }

    private JsonNode post(String operation, String path, ObjectNode body) {
        TBankToken.sign(body, settings.password());
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.bankBaseUrl + "/" + path)).timeout(settings.requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        try {
            // The whole exchange, body included, has one deadline (the request timeout of the JDK covers the headers only), and the body is capped while it streams.
            CompletableFuture<HttpResponse<byte[]>> exchange = http.sendAsync(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
            HttpResponse<byte[]> response;
            try {
                response = exchange.get(settings.requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException failure) {
                exchange.cancel(true);
                throw failed(operation, Reason.TIMEOUT, null);
            }
            if (response.statusCode() != 200) throw failed(operation, Reason.HTTP_STATUS, null);
            return READER.read(response.body());
        } catch (ExecutionException failure) {
            throw failed(operation, failure.getCause() instanceof HttpTimeoutException ? Reason.TIMEOUT : Reason.UNREACHABLE, null);
        } catch (IllegalArgumentException failure) {
            throw failed(operation, Reason.INVALID_RESPONSE, null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failed(operation, Reason.UNREACHABLE, null);
        }
    }

    private void requireSuccess(String operation, JsonNode answer) {
        if (!answer.path("Success").isBoolean() || !answer.path("Success").booleanValue()) throw failed(operation, Reason.REFUSED, errorCode(answer));
    }

    private static PaymentProviderException failed(String operation, Reason reason, String errorCode) {
        log.warn("billing bank call failed operation={} reason={} error_code={}", operation, reason, errorCode == null ? "-" : errorCode);
        return new PaymentProviderException(reason, errorCode);
    }

    /** The bank's {@code PaymentId}: digits as a string or a number (the notifications send a number). */
    static String paymentId(JsonNode node) {
        String text = node.isIntegralNumber() ? node.bigIntegerValue().toString() : node.isString() ? node.stringValue(null) : null;
        return text != null && DIGITS.matcher(text).matches() ? text : null;
    }

    static String errorCode(JsonNode answer) {
        JsonNode code = answer.path("ErrorCode");
        String text = code.isIntegralNumber() ? code.bigIntegerValue().toString() : code.isString() ? code.stringValue(null) : null;
        return text != null && CODE.matcher(text).matches() ? text : null;
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.stringValue(null) : null;
    }

    private static boolean allowedPaymentUrl(String value) {
        if (value == null || value.length() > 2_048) return false;
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getHost() != null
                    && PAYMENT_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    /** The bank's order id as a UUID, or null when it is not one (the bank echoes what Init sent, so only a foreign value fails). */
    static UUID orderId(String value) {
        try {
            UUID id = value == null ? null : UUID.fromString(value);
            return id != null && id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }
}
