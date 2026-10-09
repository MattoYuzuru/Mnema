package app.mnema.learning.billing;

import app.mnema.learning.billing.PaymentProviderException.Reason;
import app.mnema.learning.usage.Plan;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The T-Bank client against a loopback bank that serves the recorded fixtures: the requests it sends are the recorded ones (the same JSON, the same
 * {@code Token}), the answers it accepts are the recorded ones, and every other answer is a provider failure.
 */
class TBankClientTest {
    private static final BillingOrder ORDER = BillingOrder.created(UUID.fromString(BillingFixtures.ORDER_ID), UUID.randomUUID(), Plan.PLUS, 44_900, 44_900, null,
            null, Instant.parse("2026-10-09T09:00:00Z"), Instant.parse("2026-10-09T10:00:00Z"));

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> headers = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Function<String, byte[]> answer = path -> new byte[0];
    private volatile int status = 200;
    private volatile long delay;
    private TBankClient client;

    @BeforeEach
    void startBank() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v2", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                paths.add(exchange.getRequestURI().getPath());
                headers.add(exchange.getRequestHeaders().getFirst("Content-Type") + "|" + exchange.getRequestHeaders().getFirst("Accept"));
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = answer.apply(exchange.getRequestURI().getPath());
                if (status == 302) exchange.getResponseHeaders().set("Location", "/v2/elsewhere");
                exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
                if (status == 200) exchange.getResponseBody().write(body);
            } catch (java.io.IOException ignored) {
                // the client gave up (timeout test)
            }
        });
        server.start();
        client = client(Duration.ofSeconds(5));
    }

    @AfterEach
    void stopBank() {
        client.destroy();
        server.stop(0);
    }

    private TBankClient client(Duration requestTimeout) {
        BillingSettings settings = new BillingSettings("ON", "", "https://mnema.app", "http://127.0.0.1:" + server.getAddress().getPort() + "/v2",
                BillingFixtures.TERMINAL, BillingFixtures.PASSWORD_BASE64, Duration.ofHours(1), Duration.ofSeconds(10), 10, Duration.ofMinutes(2),
                Duration.ofSeconds(2), requestTimeout);
        return new TBankClient(settings, new TBankTrust());
    }

    private void answers(String fixture) {
        byte[] body = BillingFixtures.tbank(fixture).toString().getBytes(StandardCharsets.UTF_8);
        answer = path -> body;
    }

    private void answers(ObjectNode body) {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        answer = path -> bytes;
    }

    private PaymentProviderException failureOf(Runnable call) {
        try {
            call.run();
        } catch (PaymentProviderException failure) {
            return failure;
        }
        throw new AssertionError("expected a provider failure");
    }

    @Test
    void initSendsTheRecordedRequestAndReadsTheRecordedAnswer() throws Exception {
        answers("init-response-success.json");

        TBankClient.InitResult result = client.init(ORDER);

        assertThat(BillingFixtures.JSON.readTree(bodies.getFirst())).isEqualTo(BillingFixtures.tbank("init-request.json"));
        assertThat(paths).containsExactly("/v2/Init");
        assertThat(headers.getFirst()).isEqualTo("application/json; charset=utf-8|application/json");
        assertThat(result.paymentId()).isEqualTo(BillingFixtures.PAYMENT_ID);
        assertThat(result.paymentUrl()).isEqualTo("https://pay.tbank-online.com/So6mQeQB");
        assertThat(result.status()).isEqualTo("NEW");
    }

    @Test
    void aProProductIsNamedInTheDescriptionAndAPaymentIdMayBeANumber() throws Exception {
        ObjectNode success = BillingFixtures.tbank("init-response-success.json");
        success.put("PaymentId", 9407493286L);
        answers(success);
        BillingOrder pro = BillingOrder.created(ORDER.orderId(), ORDER.owner(), Plan.PRO, 44_900, 99_000, 20, UUID.randomUUID(), ORDER.createdAt(),
                ORDER.expiresAt());

        assertThat(client.init(pro).paymentId()).isEqualTo(BillingFixtures.PAYMENT_ID);

        assertThat(BillingFixtures.JSON.readTree(bodies.getFirst()).path("Description").stringValue(null)).isEqualTo("Mnema Pro — 1 месяц");
    }

    @Test
    void theBanksErrorBecomesAFailureCarryingOnlyItsCode() {
        answers("init-response-error.json");

        PaymentProviderException failure = failureOf(() -> client.init(ORDER));

        assertThat(failure.reason()).isEqualTo(Reason.REFUSED);
        assertThat(failure.errorCode()).isEqualTo("204");
        assertThat(failure.getMessage()).doesNotContain("Неверный").doesNotContain("токен");
    }

    @Test
    void anInitAnswerThatDoesNotMatchTheRequestOrNamesAnotherHostIsAFailure() {
        for (String url : new String[] {"http://pay.tbank-online.com/x", "https://evil.example/x", "https://pay.tbank-online.com.evil.example/x",
                "https://user@pay.tbank-online.com/x", "https://pay.tbank-online.com:8443/x", "javascript:alert(1)", "/relative"}) {
            ObjectNode success = BillingFixtures.tbank("init-response-success.json");
            success.put("PaymentURL", url);
            answers(success);
            assertThat(failureOf(() -> client.init(ORDER)).reason()).as(url).isEqualTo(Reason.INVALID_RESPONSE);
        }
        for (String member : new String[] {"OrderId", "Amount", "PaymentId", "PaymentURL", "Status", "TerminalKey"}) {
            ObjectNode success = BillingFixtures.tbank("init-response-success.json");
            switch (member) {
                case "OrderId" -> success.put(member, UUID.randomUUID().toString());
                case "Amount" -> success.put(member, 100);
                case "PaymentId" -> success.put(member, "12ab");
                case "TerminalKey" -> success.put(member, "1700000000999DEMO");
                case "Status" -> success.put(member, "new status");
                default -> success.remove(member);
            }
            answers(success);
            assertThat(failureOf(() -> client.init(ORDER)).reason()).as(member).isEqualTo(Reason.INVALID_RESPONSE);
        }
    }

    @Test
    void allThreeBankHostsAreAcceptedAndNothingIsFollowed() {
        for (String host : new String[] {"pay.tbank-online.com", "securepay.tinkoff.ru", "securepay.tbank.ru"}) {
            ObjectNode success = BillingFixtures.tbank("init-response-success.json");
            success.put("PaymentURL", "https://" + host + "/So6mQeQB");
            answers(success);
            assertThat(client.init(ORDER).paymentUrl()).isEqualTo("https://" + host + "/So6mQeQB");
        }
        status = 302;
        int before = calls.get();
        assertThat(failureOf(() -> client.init(ORDER)).reason()).isEqualTo(Reason.HTTP_STATUS);
        assertThat(calls.get() - before).isEqualTo(1);
    }

    @Test
    void getStateSendsTheRecordedRequestAndReadsEveryRecordedState() throws Exception {
        answers("get-state-response-confirmed.json");
        BankState confirmed = client.getState(BillingFixtures.PAYMENT_ID);

        assertThat(BillingFixtures.JSON.readTree(bodies.getFirst())).isEqualTo(BillingFixtures.tbank("get-state-request.json"));
        assertThat(paths).containsExactly("/v2/GetState");
        assertThat(confirmed).isEqualTo(new BankState(BillingFixtures.TERMINAL, BillingFixtures.ORDER_ID, BillingFixtures.PAYMENT_ID, "CONFIRMED", 44_900L, true, "0"));

        answers("get-state-response-new.json");
        assertThat(client.getState(BillingFixtures.PAYMENT_ID).status()).isEqualTo("NEW");
        answers("get-state-response-refunded.json");
        assertThat(client.getState(BillingFixtures.PAYMENT_ID).status()).isEqualTo("REFUNDED");
    }

    @Test
    void aRefusedPaymentIsAStateWithSuccessFalseButAnUnknownPaymentIsAFailure() {
        answers("get-state-response-rejected.json");

        BankState rejected = client.getState(BillingFixtures.PAYMENT_ID);

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.errorCode()).isEqualTo("1051");

        answers("get-state-response-not-found.json");
        PaymentProviderException failure = failureOf(() -> client.getState(BillingFixtures.PAYMENT_ID));
        assertThat(failure.reason()).isEqualTo(Reason.REFUSED);
        assertThat(failure.errorCode()).isEqualTo("325");
    }

    @Test
    void anAnswerAboutAnotherPaymentThanTheRequestedOneIsInvalid() {
        answers("get-state-response-confirmed.json");

        PaymentProviderException failure = failureOf(() -> client.getState("1111111111"));

        assertThat(failure.reason()).isEqualTo(Reason.INVALID_RESPONSE);
    }

    @Test
    void aStateWithoutAStatusOrAPaymentIsInvalid() {
        for (String member : new String[] {"Status", "PaymentId"}) {
            ObjectNode state = BillingFixtures.tbank("get-state-response-confirmed.json");
            state.remove(member);
            answers(state);
            assertThat(failureOf(() -> client.getState(BillingFixtures.PAYMENT_ID)).reason()).as(member).isEqualTo(Reason.INVALID_RESPONSE);
        }
        ObjectNode noAmount = BillingFixtures.tbank("get-state-response-confirmed.json");
        noAmount.remove("Amount");
        answers(noAmount);
        assertThat(client.getState(BillingFixtures.PAYMENT_ID).amount()).isNull();
    }

    @Test
    void httpErrorsMalformedJsonAndOversizedAnswersAreFailures() {
        status = 500;
        assertThat(failureOf(() -> client.getState(BillingFixtures.PAYMENT_ID)).reason()).isEqualTo(Reason.HTTP_STATUS);

        status = 200;
        answer = path -> "<html>nope".getBytes(StandardCharsets.UTF_8);
        assertThat(failureOf(() -> client.getState(BillingFixtures.PAYMENT_ID)).reason()).isEqualTo(Reason.INVALID_RESPONSE);
        answer = path -> "[1,2]".getBytes(StandardCharsets.UTF_8);
        assertThat(failureOf(() -> client.getState(BillingFixtures.PAYMENT_ID)).reason()).isEqualTo(Reason.INVALID_RESPONSE);
        answer = path -> ("{\"Success\":true,\"Message\":\"" + "x".repeat(70_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(failureOf(() -> client.getState(BillingFixtures.PAYMENT_ID)).reason()).isIn(Reason.INVALID_RESPONSE, Reason.UNREACHABLE);
    }

    @Test
    void aBankThatDoesNotAnswerInTimeIsATimeoutAndAClosedPortIsUnreachable() {
        TBankClient impatient = client(Duration.ofMillis(300));
        answers("get-state-response-confirmed.json");
        delay = 1_500;
        try {
            long started = System.nanoTime();
            assertThat(failureOf(() -> impatient.getState(BillingFixtures.PAYMENT_ID)).reason()).isEqualTo(Reason.TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        } finally {
            impatient.destroy();
        }

        server.stop(0);
        assertThat(failureOf(() -> client.getState(BillingFixtures.PAYMENT_ID)).reason()).isEqualTo(Reason.UNREACHABLE);
    }

    @Test
    void withoutCredentialsNothingIsSent() {
        BillingSettings unset = new BillingSettings("ON", "", "", "http://127.0.0.1:" + server.getAddress().getPort() + "/v2", "", "", Duration.ofHours(1),
                Duration.ofSeconds(10), 10, Duration.ofMinutes(2), Duration.ofSeconds(2), Duration.ofSeconds(5));
        TBankClient bare = new TBankClient(unset, new TBankTrust());
        try {
            assertThat(failureOf(() -> bare.init(ORDER)).reason()).isEqualTo(Reason.NOT_CONFIGURED);
            assertThat(failureOf(() -> bare.getState(BillingFixtures.PAYMENT_ID)).reason()).isEqualTo(Reason.NOT_CONFIGURED);
        } finally {
            bare.destroy();
        }
        assertThat(calls).hasValue(0);
    }
}
