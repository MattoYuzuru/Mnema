package app.mnema.learning.usage;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** The entitlement inbox is consumed: the newest valid snapshot wins, a year grants per month, and only the inbox can change it. */
class InboxEntitlementSourceTest extends UsageIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private InboxEntitlementSource source;
    @Autowired private EntitlementInbox inbox;
    @Autowired private PlanSettings settings;
    @Autowired private AllowanceCatalog catalog;
    @Autowired private app.mnema.learning.experiment.ExperimentAssignments experiments;
    @Autowired private app.mnema.learning.promo.PromoDiscounts discounts;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    private EntitlementInbox.Snapshot snapshot(String id, UUID owner, Plan plan, String kind, String start, String end) {
        return new EntitlementInbox.Snapshot(id + "-" + owner, owner, plan, kind, Instant.parse(start), Instant.parse(end),
                JSON.readTree("{}"), Instant.parse(end));
    }

    @Test
    void anOwnerWithoutASnapshotIsOnTheConfiguredPlan() {
        Entitlement entitlement = source.current(UUID.randomUUID(), now());

        assertThat(entitlement.plan()).isEqualTo(Plan.FREE);
        assertThat(entitlement.source()).isEqualTo(Entitlement.Source.CONFIG);
        assertThat(entitlement.period()).isEqualTo(Entitlement.Period.MONTH);
    }

    @Test
    void theNewestValidSnapshotWinsAndAnExpiredOrFutureOneIsIgnored() {
        UUID owner = UUID.randomUUID();
        inbox.accept(snapshot("old", owner, Plan.PLUS, "BILLING", "2026-08-31T21:00:00Z", "2026-09-30T21:00:00Z"));
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.FREE);

        inbox.accept(snapshot("month", owner, Plan.PLUS, "BILLING", "2026-09-30T21:00:00Z", "2026-10-31T21:00:00Z"));
        Entitlement plus = source.current(owner, now());
        assertThat(plus.plan()).isEqualTo(Plan.PLUS);
        assertThat(plus.source()).isEqualTo(Entitlement.Source.BILLING);
        assertThat(plus.validUntil()).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));

        inbox.accept(snapshot("future", owner, Plan.MAX, "BILLING", "2026-11-30T21:00:00Z", "2026-12-31T21:00:00Z"));
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.PLUS);

        inbox.accept(snapshot("promo", owner, Plan.PRO, "PROMO", "2026-09-30T21:00:00Z", "2026-10-31T21:00:00Z"));
        Entitlement promo = source.current(owner, now());
        assertThat(promo.plan()).isEqualTo(Plan.PRO);
        assertThat(promo.source()).isEqualTo(Entitlement.Source.PROMO);

        clock.set("2026-11-01T09:00:00Z");
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void aLowerPromoNeverMasksAValidHigherBillingSnapshotAndEqualPlansFallToTheLatestReceived() {
        UUID owner = UUID.randomUUID();
        inbox.accept(snapshot("billing", owner, Plan.PRO, "BILLING", "2026-09-30T21:00:00Z", "2026-10-31T21:00:00Z"));
        clock.set("2026-10-02T09:30:00Z");
        inbox.accept(snapshot("promo", owner, Plan.PLUS, "PROMO", "2026-09-30T21:00:00Z", "2026-10-31T21:00:00Z"));

        Entitlement picked = source.current(owner, now());
        assertThat(picked.plan()).isEqualTo(Plan.PRO);
        assertThat(picked.source()).isEqualTo(Entitlement.Source.BILLING);

        clock.set("2026-10-02T10:00:00Z");
        inbox.accept(snapshot("promo-pro", owner, Plan.PRO, "PROMO", "2026-09-30T21:00:00Z", "2026-10-31T21:00:00Z"));
        assertThat(source.current(owner, now()).source()).isEqualTo(Entitlement.Source.PROMO);

        inbox.accept(snapshot("max", owner, Plan.MAX, "PROMO", "2026-09-30T21:00:00Z", "2026-10-02T12:00:00Z"));
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.MAX);
        clock.set("2026-10-02T13:00:00Z");
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.PRO);
    }

    @Test
    void anotherOwnersSnapshotNeverApplies() {
        inbox.accept(snapshot("a", UUID.randomUUID(), Plan.PRO, "BILLING", "2026-09-30T21:00:00Z", "2026-10-31T21:00:00Z"));

        assertThat(source.current(UUID.randomUUID(), now()).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void aYearSnapshotGrantsTheMonthlyAllowanceEachMonthNeverTwelveAtOnce() {
        UUID owner = UUID.randomUUID();
        inbox.accept(snapshot("year", owner, Plan.PLUS, "BILLING", "2026-09-30T21:00:00Z", "2027-09-30T21:00:00Z"));

        Entitlement october = source.current(owner, now());
        assertThat(october.period()).isEqualTo(Entitlement.Period.YEAR);
        assertThat(october.validUntil()).isEqualTo(Instant.parse("2027-09-30T21:00:00Z"));
        Allowance allowance = catalog.allowance(october.plan());
        assertThat(allowance.credits()).isEqualTo(360);
        // The period of the allowance is the calendar month, whatever the snapshot spans.
        assertThat(calendar.period(now()).id()).isEqualTo("2026-10");

        clock.set("2027-03-15T09:00:00Z");
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.PLUS);
        assertThat(calendar.period(now()).id()).isEqualTo("2027-03");
        clock.set("2027-10-01T09:00:00Z");
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.FREE);
    }

    @Test
    void multiMonthPromosKeepMonthlyQuotaCadenceWithoutClaimingAnAnnualPurchase() {
        for (int months : new int[] {3, 6, 12}) {
            UUID owner = UUID.randomUUID();
            Instant start = Instant.parse("2026-09-30T21:00:00Z");
            Instant end = start.atZone(java.time.ZoneOffset.UTC).plusMonths(months).toInstant();
            inbox.accept(new EntitlementInbox.Snapshot("promo-" + owner, owner, Plan.PLUS, "PROMO", start, end,
                    JSON.readTree("{}"), end));

            Entitlement entitlement = source.current(owner, now());

            assertThat(entitlement.source()).isEqualTo(Entitlement.Source.PROMO);
            assertThat(entitlement.period()).isEqualTo(Entitlement.Period.MONTH);
            assertThat(entitlement.validUntil()).isEqualTo(end);
            assertThat(catalog.allowance(entitlement.plan()).credits()).isEqualTo(360);
        }
    }

    @Test
    void aSnapshotChangesTheBudgetOfTheOwnerThroughTheLedger() {
        UUID owner = UUID.randomUUID();
        var free = usage.read(owner);
        assertThat(free.plan()).isEqualTo("FREE");

        // The ledger and usage read the effective source; the test context replaces it by TestEntitlements, so drive the
        // real source through the state it feeds.
        UsageState realState = new UsageState(source, calendar, catalog, repository);
        inbox.accept(snapshot("year", owner, Plan.PRO, "PROMO", "2026-09-30T21:00:00Z", "2027-09-30T21:00:00Z"));
        var resolved = realState.resolve(owner, now());

        assertThat(resolved.allowance().plan()).isEqualTo(Plan.PRO);
        assertThat(resolved.entitlement().source()).isEqualTo(Entitlement.Source.PROMO);
        assertThat(realState.credits(resolved).total()).isEqualTo(820);
    }

    @Test
    void noRequestCanChangeTheEntitlementOnlyTheInboxCan() throws Exception {
        UUID owner = UUID.randomUUID();
        long before = countOf("entitlement_inbox", owner);
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlansController(
                        new PlansService(source, catalog, settings, clock, experiments, discounts))).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();

        var response = mvc.perform(get("/plans?plan=PRO&period=YEAR&returnUrl=https://pay.example/success&status=paid")
                .header("X-Plan", "PRO").header("Referer", "https://pay.example/success?plan=PRO")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.path("current").path("plan").stringValue(null)).isEqualTo("FREE");
        assertThat(body.path("current").path("autoRenew").booleanValue()).isFalse();
        assertThat(mvc.perform(post("/plans?plan=PRO")).andReturn().getResponse().getStatus()).isEqualTo(405);
        assertThat(mvc.perform(put("/plans?plan=PRO")).andReturn().getResponse().getStatus()).isEqualTo(405);

        assertThat(countOf("entitlement_inbox", owner)).isEqualTo(before);
        assertThat(source.current(owner, now()).plan()).isEqualTo(Plan.FREE);
        inbox.accept(snapshot("paid", owner, Plan.PRO, "BILLING", "2026-09-30T21:00:00Z", "2026-10-31T21:00:00Z"));
        var after = JSON.readTree(mvc.perform(get("/plans")).andReturn().getResponse().getContentAsString());
        assertThat(after.path("current").path("plan").stringValue(null)).isEqualTo("PRO");
        assertThat(after.path("current").path("source").stringValue(null)).isEqualTo("BILLING");
    }

    @Test
    void theInboxHasNoWriterOutsideItsOwnAcceptMethod() throws IOException {
        Path main = Path.of("src/main/java");
        if (!Files.isDirectory(main)) main = Path.of("services/learning/src/main/java");
        try (Stream<Path> files = Files.walk(main)) {
            var writers = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    return Files.readString(path).contains("insertSnapshot(");
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }).map(path -> path.getFileName().toString()).toList();
            assertThat(writers).containsExactlyInAnyOrder("EntitlementInbox.java", "UsageRepository.java");
        }
    }
}
