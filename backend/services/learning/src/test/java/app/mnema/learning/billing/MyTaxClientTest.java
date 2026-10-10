package app.mnema.learning.billing;

import app.mnema.learning.billing.MyTaxException.Outcome;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The «Мой налог» client against a loopback service: payloads, the session, every failure class and the log lines. */
class MyTaxClientTest {
    private static final BillingTestConfiguration.MutableClock CLOCK = new BillingTestConfiguration.MutableClock();
    private static final FakeMyTax SERVICE = new FakeMyTax(CLOCK::now);
    private static final String NAME = "Подписка Мнема Plus на 1 месяц";
    private static final Instant PAID = Instant.parse("2026-10-09T09:00:00.789Z");

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private Level previousLevel;
    private MyTaxClient client;

    private static NpdSettings settings(String baseUrl, Duration requestTimeout) {
        return new NpdSettings("ON", FakeMyTax.INN, FakeMyTax.PASSWORD_BASE64, baseUrl, Duration.ofMinutes(1), Duration.ofSeconds(2), requestTimeout);
    }

    @BeforeEach
    void start() {
        CLOCK.set(BillingTestConfiguration.START);
        SERVICE.reset();
        client = new MyTaxClient(settings(SERVICE.baseUrl(), Duration.ofMillis(700)), CLOCK);
        previousLevel = root.getLevel();
        root.setLevel(Level.DEBUG);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void stop() {
        root.detachAppender(logs);
        root.setLevel(previousLevel);
        client.destroy();
    }

    @AfterAll
    static void close() {
        SERVICE.close();
    }

    @Test
    void anIncomeCarriesTheDocumentedPayloadWithKopecksAsTwoDecimalRubles() {
        String uuid = client.registerIncome(NAME, 49_950, PAID);

        assertThat(uuid).isEqualTo(SERVICE.receipts.getFirst().uuid);
        var request = SERVICE.incomes.getFirst();
        assertThat(request.path("operationTime").stringValue(null)).as("the confirmation instant in the Moscow offset, seconds only").isEqualTo("2026-10-09T12:00:00+03:00");
        assertThat(request.path("requestTime").stringValue(null)).isEqualTo("2026-10-09T12:00:00+03:00");
        assertThat(request.path("services")).hasSize(1);
        assertThat(request.path("services").path(0).path("name").stringValue(null)).isEqualTo(NAME);
        assertThat(request.path("services").path(0).path("quantity").intValue(0)).isEqualTo(1);
        assertThat(request.path("services").path(0).path("amount").isNumber()).isTrue();
        assertThat(request.path("services").path(0).path("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("499.50"));
        assertThat(request.path("totalAmount").stringValue(null)).as("an exact string, never a double").isEqualTo("499.50");
        assertThat(request.path("paymentType").stringValue(null)).as("card money reaches the bank account: not cash").isEqualTo("ACCOUNT");
        assertThat(request.path("ignoreMaxTotalIncomeRestriction").booleanValue()).isFalse();
        assertThat(request.path("client").path("incomeType").stringValue(null)).isEqualTo("FROM_INDIVIDUAL");
        assertThat(request.path("client").path("inn").isNull()).isTrue();
        assertThat(request.path("client").path("displayName").isNull()).isTrue();
        assertThat(request.path("client").path("contactPhone").isNull()).isTrue();
    }

    @Test
    void wholeRublesStillGoWithTwoDecimals() {
        client.registerIncome(NAME, 49_900, PAID);

        assertThat(SERVICE.incomes.getFirst().path("totalAmount").stringValue(null)).isEqualTo("499.00");
        assertThat(SERVICE.receipts.getFirst().amount).isEqualByComparingTo("499");
    }

    @Test
    void theSessionIsKeptInMemoryAndLogsInOnceWithAStableDevice() {
        client.registerIncome(NAME, 49_900, PAID);
        client.registerIncome(NAME, 49_900, PAID.plusSeconds(5));

        assertThat(SERVICE.logins).hasSize(1);
        var login = SERVICE.logins.getFirst();
        assertThat(login.path("username").stringValue(null)).isEqualTo(FakeMyTax.INN);
        assertThat(login.path("deviceInfo").path("sourceType").stringValue(null)).isEqualTo("WEB");
        assertThat(login.path("deviceInfo").path("sourceDeviceId").stringValue(null)).isEqualTo(settings(SERVICE.baseUrl(), Duration.ofSeconds(1)).deviceId());
        assertThat(SERVICE.refreshes).isEmpty();
        assertThat(SERVICE.userAgents).allMatch(agent -> agent.startsWith("Mozilla/5.0"));
    }

    @Test
    void aTokenAboutToExpireIsRefreshedBeforeUseAndTheRefreshTokenIsKept() {
        client.registerIncome(NAME, 49_900, PAID);
        CLOCK.advance(Duration.ofMinutes(59).plusSeconds(30));

        client.registerIncome(NAME, 49_900, PAID.plusSeconds(5));
        CLOCK.advance(Duration.ofMinutes(59).plusSeconds(30));
        client.registerIncome(NAME, 49_900, PAID.plusSeconds(10));

        assertThat(SERVICE.logins).hasSize(1);
        assertThat(SERVICE.refreshes).hasSize(2);
        assertThat(SERVICE.refreshes.get(0).path("refreshToken").stringValue(null)).isEqualTo(SERVICE.refreshes.get(1).path("refreshToken").stringValue(null));
        assertThat(SERVICE.refreshes.getFirst().path("deviceInfo").path("sourceDeviceId").isString()).isTrue();
        assertThat(SERVICE.receipts).hasSize(3);
    }

    @Test
    void aRefusedTokenIsRefreshedOnceAndTheCallRepeated() {
        client.registerIncome(NAME, 49_900, PAID);
        SERVICE.revokeTokens();

        client.registerIncome(NAME, 49_900, PAID.plusSeconds(5));

        assertThat(SERVICE.refreshes).hasSize(1);
        assertThat(SERVICE.logins).hasSize(1);
        assertThat(SERVICE.receipts).hasSize(2);
    }

    @Test
    void whenTheRefreshTokenIsRefusedTheInnAndPasswordLogInAgain() {
        client.registerIncome(NAME, 49_900, PAID);
        SERVICE.revokeTokens();
        SERVICE.rejectRefresh = true;

        client.registerIncome(NAME, 49_900, PAID.plusSeconds(5));

        assertThat(SERVICE.refreshes).hasSize(1);
        assertThat(SERVICE.logins).hasSize(2);
        assertThat(SERVICE.receipts).hasSize(2);
    }

    @Test
    void aRefusedLoginIsAuthAndBlocksFurtherLoginsForFifteenMinutes() {
        SERVICE.rejectLogin = true;

        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOfSatisfying(MyTaxException.class, failure -> {
            assertThat(failure.outcome()).isEqualTo(Outcome.AUTH);
            assertThat(failure.code()).isEqualTo("AUTH_REJECTED");
        });
        assertThat(client.loginBlocked()).isTrue();
        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOfSatisfying(MyTaxException.class,
                failure -> assertThat(failure.code()).isEqualTo("AUTH_BLOCKED"));
        assertThat(SERVICE.logins).as("the second call did not reach the service").hasSize(1);

        CLOCK.advance(MyTaxClient.LOGIN_BACKOFF.plusSeconds(1));
        SERVICE.rejectLogin = false;
        assertThat(client.loginBlocked()).isFalse();
        assertThat(client.registerIncome(NAME, 49_900, PAID)).isNotBlank();
        assertThat(SERVICE.logins).hasSize(2);
    }

    @Test
    void everyRefusedLoginDoublesThePauseUpToADayAndASuccessResetsIt() {
        SERVICE.rejectLogin = true;
        for (int refusal = 1; refusal <= 3; refusal++) {
            assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOfSatisfying(MyTaxException.class,
                    failure -> assertThat(failure.code()).isEqualTo("AUTH_REJECTED"));
            Duration pause = MyTaxClient.LOGIN_BACKOFF.multipliedBy(1L << (refusal - 1));
            CLOCK.advance(pause.minusSeconds(1));
            assertThat(client.loginBlocked()).as("still blocked just before " + pause).isTrue();
            CLOCK.advance(Duration.ofSeconds(2));
            assertThat(client.loginBlocked()).isFalse();
        }
        assertThat(SERVICE.logins).hasSize(3);
        assertThat(MyTaxClient.loginBackoff(1)).isEqualTo(Duration.ofMinutes(15));
        assertThat(MyTaxClient.loginBackoff(2)).isEqualTo(Duration.ofMinutes(30));
        assertThat(MyTaxClient.loginBackoff(7)).isEqualTo(Duration.ofHours(16));
        assertThat(MyTaxClient.loginBackoff(8)).as("capped at a day").isEqualTo(Duration.ofHours(24));
        assertThat(MyTaxClient.loginBackoff(500)).isEqualTo(Duration.ofHours(24));
        assertThat(MyTaxClient.loginBackoff(0)).isEqualTo(Duration.ofMinutes(15));

        SERVICE.rejectLogin = false;
        client.registerIncome(NAME, 49_900, PAID);
        SERVICE.revokeTokens();
        SERVICE.rejectRefresh = true;
        SERVICE.rejectLogin = true;
        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOf(MyTaxException.class);
        CLOCK.advance(MyTaxClient.LOGIN_BACKOFF.plusSeconds(1));
        assertThat(client.loginBlocked()).as("the pause started again from 15 minutes").isFalse();
    }

    @Test
    void theCallsSendTheRefererOfThePageThatMakesThemInTheWebApp() {
        client.registerIncome(NAME, 49_900, PAID);
        CLOCK.advance(Duration.ofMinutes(59).plusSeconds(30));
        client.registerIncome(NAME, 49_900, PAID.plusSeconds(5));
        client.findIncomes(PAID, 49_900, NAME);

        assertThat(SERVICE.referers).containsExactly("/auth/lkfl https://lknpd.nalog.ru/auth/login", "/income https://lknpd.nalog.ru/sales/create",
                "/auth/token https://lknpd.nalog.ru/sales", "/income https://lknpd.nalog.ru/sales/create", "/incomes null");
    }

    @Test
    void aTokenTheServiceKeepsRefusingEndsAsAuthAfterOneRenewal() {
        client.registerIncome(NAME, 49_900, PAID);
        SERVICE.denyAll = true;

        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID.plusSeconds(5))).isInstanceOfSatisfying(MyTaxException.class, failure -> {
            assertThat(failure.outcome()).isEqualTo(Outcome.AUTH);
            assertThat(failure.code()).isEqualTo("HTTP_401");
        });
        assertThat(SERVICE.refreshes).hasSize(1);
        assertThat(SERVICE.receipts).hasSize(1);
    }

    @Test
    void everyFailureClassIsToldApart() {
        SERVICE.incomeMode = FakeMyTax.Mode.RATE_LIMITED;
        assertFailure(Outcome.NOT_SENT, "HTTP_429");
        SERVICE.incomeMode = FakeMyTax.Mode.REJECTED;
        assertFailure(Outcome.REJECTED, "HTTP_422");
        SERVICE.incomeMode = FakeMyTax.Mode.SERVER_ERROR_BEFORE;
        assertFailure(Outcome.MAYBE_SENT, "HTTP_500");
        SERVICE.incomeMode = FakeMyTax.Mode.LOST_ANSWER;
        assertFailure(Outcome.MAYBE_SENT, "TIMEOUT");
        assertThat(SERVICE.receipts).as("the lost answer left a receipt behind: why a resend must be preceded by a lookup").hasSize(1);
    }

    private void assertFailure(Outcome outcome, String code) {
        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOfSatisfying(MyTaxException.class, failure -> {
            assertThat(failure.outcome()).isEqualTo(outcome);
            assertThat(failure.code()).isEqualTo(code);
        });
    }

    @Test
    void aServiceThatIsNotThereIsNotSent() {
        MyTaxClient nobody = new MyTaxClient(settings("http://127.0.0.1:1/api/v1", Duration.ofSeconds(2)), CLOCK);
        try {
            assertThatThrownBy(() -> nobody.registerIncome(NAME, 49_900, PAID)).isInstanceOfSatisfying(MyTaxException.class, failure -> {
                assertThat(failure.outcome()).isEqualTo(Outcome.NOT_SENT);
                assertThat(failure.code()).isEqualTo("UNREACHABLE");
            });
        } finally {
            nobody.destroy();
        }
    }

    @Test
    void anAnswerThatIsNotTheDocumentedOneIsAFailureNeverAGuess() {
        for (String body : new String[] {"{}", "{\"approvedReceiptUuid\":5}", "{\"approvedReceiptUuid\":\"../../x\"}", "{\"approvedReceiptUuid\":\"\"}", "[]", "not json"}) {
            SERVICE.incomeBody = body;
            assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).as(body).isInstanceOfSatisfying(MyTaxException.class, failure -> {
                assertThat(failure.outcome()).isEqualTo(Outcome.MAYBE_SENT);
                assertThat(failure.code()).isEqualTo("INVALID_RESPONSE");
            });
        }
    }

    @Test
    void aResponseOverTheLimitIsRefusedWhileItStreams() {
        SERVICE.incomeBody = "{\"approvedReceiptUuid\":\"a\",\"pad\":\"" + "x".repeat(MyTaxClient.MAX_RESPONSE_BYTES) + "\"}";

        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOfSatisfying(MyTaxException.class,
                failure -> assertThat(failure.outcome()).isEqualTo(Outcome.MAYBE_SENT));
    }

    @Test
    void aRedirectIsNeverFollowed() {
        SERVICE.redirectStatus = 302;

        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOfSatisfying(MyTaxException.class, failure -> {
            assertThat(failure.code()).isEqualTo("HTTP_302");
            assertThat(failure.outcome()).isNotEqualTo(Outcome.REJECTED);
        });
    }

    @Test
    void aCancellationIsARefundWithTheDocumentedComment() {
        String uuid = client.registerIncome(NAME, 49_900, PAID);

        client.cancelIncome(uuid);

        var request = SERVICE.cancels.getFirst();
        assertThat(request.path("comment").stringValue(null)).isEqualTo("Возврат средств");
        assertThat(request.path("receiptUuid").stringValue(null)).isEqualTo(uuid);
        assertThat(request.path("partnerCode").isNull()).isTrue();
        assertThat(request.path("operationTime").stringValue(null)).isEqualTo("2026-10-09T12:00:00+03:00");
        assertThat(request.path("requestTime").stringValue(null)).isEqualTo("2026-10-09T12:00:00+03:00");
        assertThat(client.isCancelled(uuid)).isTrue();
        assertThatThrownBy(() -> client.cancelIncome(uuid)).isInstanceOfSatisfying(MyTaxException.class, failure -> assertThat(failure.outcome()).isEqualTo(Outcome.REJECTED));
    }

    @Test
    void aCancelAnswerWithoutTheDocumentedBodyIsNotTrusted() {
        String uuid = client.registerIncome(NAME, 49_900, PAID);
        SERVICE.cancelMode = FakeMyTax.Mode.OK;
        SERVICE.receipts.getFirst().cancelled = false;

        client.cancelIncome(uuid);
        assertThat(client.isCancelled(uuid)).isTrue();
        assertThat(client.isCancelled(SERVICE.register("x", BigDecimal.ONE, "2026-10-09T12:00:00+03:00").uuid)).isFalse();
        assertThatThrownBy(() -> client.cancelIncome("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.isCancelled(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLookupFindsTheReceiptOfALostAnswerByOperationSecondTotalAndName() {
        SERVICE.incomeMode = FakeMyTax.Mode.LOST_ANSWER;
        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOf(MyTaxException.class);
        SERVICE.register(NAME, new BigDecimal("499.00"), "2026-10-09T12:00:01+03:00");
        SERVICE.register(NAME, new BigDecimal("500.00"), "2026-10-09T12:00:00+03:00");
        SERVICE.register("Другая услуга", new BigDecimal("499.00"), "2026-10-09T12:00:00+03:00");

        List<MyTaxClient.Found> found = client.findIncomes(PAID, 49_900, NAME);

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().receiptUuid()).isEqualTo(SERVICE.receipts.getFirst().uuid);
        assertThat(found.getFirst().cancelled()).isFalse();
        String query = SERVICE.incomeQueries.getFirst();
        assertThat(query).contains("from=2026-10-09T11%3A59%3A00.000%2B03%3A00").contains("to=2026-10-09T12%3A01%3A00.000%2B03%3A00")
                .contains("sortBy=operation_time%3Aasc").contains("limit=100").contains("offset=0");
        SERVICE.receipts.getFirst().cancelled = true;
        assertThat(client.findIncomes(PAID, 49_900, NAME).getFirst().cancelled()).isTrue();
        assertThat(client.findIncomes(PAID.plusSeconds(30), 49_900, NAME)).isEmpty();
    }

    @Test
    void aBrokenListIsAFailureNotAnEmptyAnswer() {
        SERVICE.listBroken = true;

        assertThatThrownBy(() -> client.findIncomes(PAID, 49_900, NAME)).isInstanceOfSatisfying(MyTaxException.class,
                failure -> assertThat(failure.outcome()).isEqualTo(Outcome.MAYBE_SENT));
    }

    @Test
    void noLogLineCarriesTheInnThePasswordATokenOrAReceiptBody() {
        SERVICE.rejectLogin = true;
        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOf(MyTaxException.class);
        SERVICE.rejectLogin = false;
        CLOCK.advance(MyTaxClient.LOGIN_BACKOFF.plusSeconds(1));
        String uuid = client.registerIncome(NAME, 49_900, PAID);
        SERVICE.revokeTokens();
        SERVICE.incomeMode = FakeMyTax.Mode.LOST_ANSWER;
        assertThatThrownBy(() -> client.registerIncome(NAME, 49_900, PAID)).isInstanceOf(MyTaxException.class);
        client.cancelIncome(uuid);

        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertThat(line).doesNotContain(FakeMyTax.INN, FakeMyTax.PASSWORD, FakeMyTax.PASSWORD_BASE64, "access-", "refresh-", "Bearer", NAME, "499");
        }
        assertThat(logs.list.stream().filter(event -> event.getLoggerName().startsWith("app.mnema.")).toList()).as("the client logs nothing: the worker logs each failure once").isEmpty();
    }
}
