package app.mnema.learning.library;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.LibraryFixtures.Deck;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.security.ProfileLookupBudget;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/13 (#435) on a real PostgreSQL with a clock the test moves: invitation expiry, exhaustion and revocation, concurrent redemptions against the use
 * limit, the 30-day silence, the 20-open and 5-a-day limits of a requester and the once-a-day notification of the author.
 */
@SpringBootTest
class AccessServiceIntegrationTest extends PostgresIntegrationTest {
    /** A clock the test moves. */
    private static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant now) { this.now = now; }

        void advance(Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Autowired private DeckAccess access;
    @Autowired private DeckPublications publications;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AccessRepository repository;
    @Autowired private LibraryRepository library;
    @Autowired private NotificationPublisher notifications;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-11T09:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AccessMetrics metrics = new AccessMetrics(meters);
    private LibraryFixtures fixtures;
    private InviteService invites;
    private AccessRequestService requests;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void services() {
        fixtures = new LibraryFixtures(decks, items, exercises, sessions, media, jdbc, publications);
        invites = new InviteService(repository, library, metrics, transactions, clock, new SecureRandom());
        requests = new AccessRequestService(new PublicRouteSettings(true, 120, 600, 600, 600, 50_000, 3), access, repository, library, notifications, metrics, transactions, clock);
    }

    /** A published INVITE deck without content: all the access code needs is the publication row with a published revision. */
    private record Shared(Deck deck, String code) { UUID id() { return deck.id(); } }

    private Shared shared(UUID deckOwner, DeckVisibility level) {
        UUID id = UUID.fromString(decks.create(deckOwner, new DeckCommand(UUID.randomUUID(), "Колода " + UUID.randomUUID(), "Описание")).acknowledgement()
                .path("deck").path("deckId").stringValue(null));
        Deck deck = new Deck(deckOwner, id, null, null);
        return new Shared(deck, fixtures.publishAt(deck, level));
    }

    private String redeem(UUID account, String token) {
        try {
            return invites.redeem(account, token);
        } catch (ResourceNotFoundException refused) {
            return null;
        }
    }

    private long uses(UUID invite) { return jdbc.sql("SELECT uses FROM app_learning.deck_invite WHERE invite_id = :id").param("id", invite).query(Long.class).single(); }

    private long grants(UUID deck) { return jdbc.sql("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :deck").param("deck", deck).query(Long.class).single(); }

    private List<String> notificationParams(UUID recipient, String kind) {
        return jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id = :owner AND kind = :kind ORDER BY seq")
                .param("owner", recipient).param("kind", kind).query(String.class).list();
    }

    private boolean readable(UUID account, String code) {
        return access.resolve(Viewer.account(account, null), code).granted();
    }

    // ---- invitation lifecycle ----

    @Test
    void anInvitationExpiresAndIsThenTheSameNotFoundAsAnUnknownToken() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), null, 1);
        UUID early = UUID.randomUUID();
        UUID late = UUID.randomUUID();
        clock.advance(Duration.ofHours(23));
        assertThat(redeem(early, created.token())).isEqualTo(deck.code());
        assertThat(readable(early, deck.code())).isTrue();
        clock.advance(Duration.ofHours(2));
        assertThat(invites.status(created.invite())).isEqualTo(Invite.Status.EXPIRED);
        assertThat(redeem(late, created.token())).isNull();
        assertThat(readable(late, deck.code())).isFalse();
        // the grant of the early redeemer outlives the invitation
        assertThat(readable(early, deck.code())).isTrue();
        assertThat(uses(created.invite().inviteId())).isEqualTo(1);
    }

    @Test
    void aOneTimeInvitationServesOneAccountAndAnExhaustedOneIsNotFound() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), 1, null);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThat(redeem(first, created.token())).isEqualTo(deck.code());
        // the same account again consumes nothing and still gets in; another account is refused
        assertThat(redeem(first, created.token())).isEqualTo(deck.code());
        assertThat(uses(created.invite().inviteId())).isEqualTo(1);
        assertThat(redeem(second, created.token())).isNull();
        assertThat(grants(deck.id())).isEqualTo(1);
        assertThat(invites.list(owner, deck.id(), null, null).items().getFirst().uses()).isEqualTo(1);
        assertThat(invites.status(invites.list(owner, deck.id(), null, null).items().getFirst())).isEqualTo(Invite.Status.EXHAUSTED);
    }

    @Test
    void aRevokedInvitationStopsNewRedemptionsButKeepsTheGrantsItMade() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), 10, 7);
        UUID inside = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        assertThat(redeem(inside, created.token())).isNotNull();
        Invite revoked = invites.revoke(owner, deck.id(), created.invite().inviteId());
        assertThat(revoked.revokedAt()).isEqualTo(clock.instant());
        clock.advance(Duration.ofMinutes(5));
        assertThat(invites.revoke(owner, deck.id(), created.invite().inviteId()).revokedAt()).as("revoking twice changes nothing").isEqualTo(revoked.revokedAt());
        assertThat(invites.status(revoked)).isEqualTo(Invite.Status.REVOKED);
        assertThat(redeem(outside, created.token())).isNull();
        assertThat(readable(inside, deck.code())).isTrue();
        assertThat(readable(outside, deck.code())).isFalse();
    }

    @Test
    void anInvitationOfAnotherDeckOrAForeignOwnerIsNotFoundAndAPrivateDeckHasNoInvitations() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        Shared other = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), null, null);
        assertThatThrownBy(() -> invites.revoke(owner, other.id(), created.invite().inviteId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invites.revoke(UUID.randomUUID(), deck.id(), created.invite().inviteId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invites.create(UUID.randomUUID(), deck.id(), null, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> invites.list(UUID.randomUUID(), deck.id(), null, null)).isInstanceOf(ResourceNotFoundException.class);

        Shared lowered = shared(owner, DeckVisibility.INVITE);
        InviteService.Created before = invites.create(owner, lowered.id(), null, null);
        fixtures.level(lowered.deck(), DeckVisibility.PRIVATE);
        assertThatThrownBy(() -> invites.create(owner, lowered.id(), null, null)).isInstanceOf(PublicationRequiredException.class);
        // a private deck's invitation is dead, and so it stays after the deck is shared again: the code was rotated, and an invitation of the old
        // generation opens nothing for anybody (the canon: lowering the level changes the links)
        UUID guest = UUID.randomUUID();
        assertThat(redeem(guest, before.token())).isNull();
        String rotated = fixtures.level(lowered.deck(), DeckVisibility.INVITE);
        assertThat(rotated).isNotEqualTo(lowered.code());
        assertThat(redeem(guest, before.token())).as("the old generation stays dead").isNull();
        assertThat(redeem(owner, before.token())).as("even for the owner").isNull();
        assertThat(invites.list(owner, lowered.id(), null, null).items().getFirst().stale()).isTrue();
        assertThat(invites.status(invites.list(owner, lowered.id(), null, null).items().getFirst())).isEqualTo(Invite.Status.STALE);
        assertThat(repository.activeInvites(lowered.id(), clock.instant())).as("a stale invitation is not active").isZero();
        InviteService.Created fresh = invites.create(owner, lowered.id(), null, null);
        assertThat(fresh.link()).startsWith("/d/" + rotated + "?invite=");
        assertThat(redeem(guest, fresh.token())).isEqualTo(rotated);
        assertThat(readable(guest, rotated)).isTrue();
        assertThat(readable(guest, lowered.code())).as("the code of before the downgrade is gone").isFalse();
        // raising the level keeps the code and the generation; lowering again (LINK to INVITE) rotates and kills it
        fixtures.level(lowered.deck(), DeckVisibility.LINK);
        assertThat(redeem(UUID.randomUUID(), fresh.token())).isEqualTo(rotated);
        fixtures.level(lowered.deck(), DeckVisibility.INVITE);
        assertThat(redeem(UUID.randomUUID(), fresh.token())).isNull();
    }

    @Test
    void onALinkOrPublicDeckAValidInvitationIsANoOpAnswerWithTheCode() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), 1, null);
        fixtures.level(deck.deck(), DeckVisibility.LINK);
        UUID visitor = UUID.randomUUID();
        assertThat(redeem(visitor, created.token())).isEqualTo(deck.code());
        assertThat(grants(deck.id())).as("anybody with the link can read it: nothing to grant").isZero();
        assertThat(uses(created.invite().inviteId())).as("and no use is consumed").isZero();
        fixtures.level(deck.deck(), DeckVisibility.PUBLIC);
        assertThat(redeem(visitor, created.token())).isEqualTo(deck.code());
        invites.revoke(owner, deck.id(), created.invite().inviteId());
        assertThat(redeem(visitor, created.token())).as("a revoked invitation is dead at any level").isNull();
    }

    @Test
    void aRevokedPersonIsNotLetBackInByALiveLinkForThirtyDaysButTheOwnerMayAddThemByLogin() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), null, null);
        UUID person = UUID.randomUUID();
        GrantService grants = new GrantService(repository, library, username -> java.util.Optional.of(person), new ProfileLookupBudget(), metrics, transactions, clock);
        assertThat(redeem(person, created.token())).isEqualTo(deck.code());
        grants.revoke(owner, deck.id(), person);
        assertThat(readable(person, deck.code())).isFalse();
        assertThat(redeem(person, created.token())).as("the link is live, the revocation sticks").isNull();
        assertThat(jdbc.sql("SELECT status FROM app_learning.deck_access_request WHERE deck_id = :d AND requester_id = :p").param("d", deck.id()).param("p", person)
                .query(String.class).single()).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM app_learning.deck_access_request WHERE deck_id = :d AND requester_id = :p").param("d", deck.id()).param("p", person)
                .query(Boolean.class).single()).isTrue();
        clock.advance(Duration.ofDays(29));
        assertThat(redeem(person, created.token())).isNull();
        // the owner's deliberate act wins
        grants.grantByLogin(owner, deck.id(), "someone");
        assertThat(readable(person, deck.code())).isTrue();
        grants.revoke(owner, deck.id(), person);
        assertThat(redeem(person, created.token())).isNull();
        clock.advance(Duration.ofDays(31));
        assertThat(redeem(person, created.token())).as("after 30 days the link works again").isEqualTo(deck.code());
        assertThat(readable(person, deck.code())).isTrue();
        // a plain rejection of a request is not a revocation: a link the owner sends later works at once
        UUID asker = UUID.randomUUID();
        requests.send(asker, deck.code());
        requests.reject(owner, deck.id(), asker);
        assertThat(redeem(asker, created.token())).isEqualTo(deck.code());
        assertThat(readable(asker, deck.code())).isTrue();
        // revoking a person who has no grant records nothing
        UUID nobody = UUID.randomUUID();
        grants.revoke(owner, deck.id(), nobody);
        assertThat(status(deck.id(), nobody)).isNull();
    }

    @Test
    void aDeckHoldsAThousandPeopleAndTheLimitIsEnforcedOnEveryWayIn() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), null, null);
        UUID member = UUID.randomUUID();
        redeem(member, created.token());
        jdbc.sql("""
                INSERT INTO app_learning.deck_access_grant(deck_id, grantee_id, role, granted_by, granted_at)
                SELECT :deck, gen_random_uuid(), 'VIEWER', :by, now() FROM generate_series(1, :count)
                """).param("deck", deck.id()).param("by", owner).param("count", AccessRepository.GRANT_LIMIT - 1).update();
        assertThat(grants(deck.id())).isEqualTo(AccessRepository.GRANT_LIMIT);
        UUID waiting = UUID.randomUUID();
        requests.send(waiting, deck.code());
        UUID late = UUID.randomUUID();
        GrantService byLogin = new GrantService(repository, library, username -> java.util.Optional.of(late), new ProfileLookupBudget(), metrics, transactions, clock);
        assertThatThrownBy(() -> redeem(UUID.randomUUID(), created.token())).isInstanceOfSatisfying(ResourceLimitExceededException.class,
                refused -> assertThat(refused.extension().members()).containsEntry("limit", 1000L));
        assertThatThrownBy(() -> byLogin.grantByLogin(owner, deck.id(), "someone")).isInstanceOf(ResourceLimitExceededException.class);
        assertThatThrownBy(() -> requests.approve(owner, deck.id(), waiting)).isInstanceOf(ResourceLimitExceededException.class);
        assertThat(status(deck.id(), waiting)).as("the request stays open").isEqualTo("PENDING");
        assertThat(uses(created.invite().inviteId())).as("no use was consumed by the refusals").isEqualTo(1);
        assertThat(redeem(member, created.token())).as("somebody already in is unaffected").isEqualTo(deck.code());
        // a slot frees up and the request can be approved
        byLogin.revoke(owner, deck.id(), member);
        assertThat(requests.approve(owner, deck.id(), waiting).status()).isEqualTo("APPROVED");
    }

    @Test
    void aRequesterTakesARequestBackAndFreesASlot() {
        UUID asker = UUID.randomUUID();
        List<Shared> all = new ArrayList<>();
        for (int index = 0; index < 5; index++) all.add(shared(owner, DeckVisibility.INVITE));
        for (Shared deck : all) assertThat(requests.send(asker, deck.code())).isEqualTo("PENDING");
        requests.withdraw(asker, all.getFirst().code());
        assertThat(status(all.getFirst().id(), asker)).isEqualTo("WITHDRAWN");
        assertThat(requests.list(owner, all.getFirst().id(), null, null).items()).as("the author no longer sees it").isEmpty();
        requests.withdraw(asker, all.getFirst().code());
        assertThat(status(all.getFirst().id(), asker)).as("twice is the same").isEqualTo("WITHDRAWN");
        UUID stranger = UUID.randomUUID();
        requests.withdraw(stranger, all.getFirst().code());
        assertThat(status(all.getFirst().id(), stranger)).as("no request, nothing to take back, still a success").isNull();
        assertThatThrownBy(() -> requests.withdraw(asker, "1".repeat(10))).isInstanceOf(ResourceNotFoundException.class);
        Shared hidden = shared(owner, DeckVisibility.INVITE);
        fixtures.level(hidden.deck(), DeckVisibility.PRIVATE);
        assertThatThrownBy(() -> requests.withdraw(asker, hidden.code())).isInstanceOf(ResourceNotFoundException.class);
        // a withdrawn request is asked again at once (the daily limit still counts it)
        clock.advance(Duration.ofDays(2));
        assertThat(requests.send(asker, all.getFirst().code())).isEqualTo("PENDING");
        assertThat(status(all.getFirst().id(), asker)).isEqualTo("PENDING");
    }

    @Test
    void twentyOpenRequestsAreFreedByWithdrawing() {
        UUID asker = UUID.randomUUID();
        List<Shared> all = new ArrayList<>();
        for (int index = 0; index < 21; index++) all.add(shared(owner, DeckVisibility.INVITE));
        int next = 0;
        for (int day = 0; day < 4; day++) {
            for (int index = 0; index < 5; index++) requests.send(asker, all.get(next++).code());
            clock.advance(Duration.ofHours(25));
        }
        assertThatThrownBy(() -> requests.send(asker, all.get(20).code())).isInstanceOf(ResourceLimitExceededException.class);
        requests.withdraw(asker, all.getFirst().code());
        assertThat(requests.send(asker, all.get(20).code())).isEqualTo("PENDING");
        assertThat(status(all.get(20).id(), asker)).isEqualTo("PENDING");
    }

    @Test
    void theTokenNeverPrintsAndTheOutcomesAreCounted() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), 1, null);
        assertThat(created.toString()).doesNotContain(created.token()).doesNotContain("?invite=");
        UUID reader = UUID.randomUUID();
        redeem(reader, created.token());
        redeem(reader, created.token());
        redeem(UUID.randomUUID(), created.token());
        redeem(UUID.randomUUID(), "garbage");
        assertThat(counter("redeem", "ok")).isEqualTo(1);
        assertThat(counter("redeem", "noop")).isEqualTo(1);
        assertThat(counter("redeem", "refused")).isEqualTo(2);
        UUID asker = UUID.randomUUID();
        requests.send(asker, deck.code());
        requests.send(asker, deck.code());
        assertThatThrownBy(() -> requests.send(owner, deck.code())).isInstanceOf(InvalidRequestException.class);
        assertThat(counter("request", "created")).isEqualTo(1);
        assertThat(counter("request", "silent")).isEqualTo(1);
        assertThat(counter("request", "refused")).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getKey()).isIn("op", "outcome")));
    }

    private double counter(String op, String outcome) {
        var counter = meters.find("mnema_deck_access_total").tags("op", op, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theInstanceBudgetOfLoginLookupsStopsTheCallBeforeIdentityIsAsked() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        Shared notInvite = shared(owner, DeckVisibility.LINK);
        int[] asked = {0};
        GrantService grants = new GrantService(repository, library, username -> {
            asked[0]++;
            return java.util.Optional.empty();
        }, new ProfileLookupBudget(3, 3, 2, 3, System::nanoTime), metrics, transactions, clock);
        assertThatThrownBy(() -> grants.grantByLogin(owner, notInvite.id(), "someone")).as("a LINK deck has nobody to invite").isInstanceOf(PublicationRequiredException.class);
        Shared priv = shared(owner, DeckVisibility.INVITE);
        fixtures.level(priv.deck(), DeckVisibility.PRIVATE);
        assertThatThrownBy(() -> grants.grantByLogin(owner, priv.id(), "someone")).isInstanceOf(PublicationRequiredException.class);
        assertThat(asked[0]).as("no lookup for a deck that cannot use a grant").isZero();
        for (int index = 0; index < 2; index++) assertThatThrownBy(() -> grants.grantByLogin(owner, deck.id(), "someone")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> grants.grantByLogin(owner, deck.id(), "someone")).as("the owner's two a window").isInstanceOf(RateLimitedException.class);
        UUID other = UUID.randomUUID();
        Shared othersDeck = shared(other, DeckVisibility.INVITE);
        assertThatThrownBy(() -> grants.grantByLogin(other, othersDeck.id(), "someone")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> grants.grantByLogin(other, othersDeck.id(), "someone")).as("the instance's three a window").isInstanceOf(RateLimitedException.class);
        assertThat(asked[0]).isEqualTo(3);
    }

    @Test
    void approveRevokeRedeemAndLoginGrantOnOnePersonNeverDeadlock() throws Exception {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created link = invites.create(owner, deck.id(), null, null);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            for (int round = 0; round < 25; round++) {
                UUID person = UUID.randomUUID();
                GrantService grants = new GrantService(repository, library, username -> java.util.Optional.of(person), new ProfileLookupBudget(1000, 1000, 1000, 1000, System::nanoTime),
                        metrics, transactions, clock);
                requests.send(person, deck.code());
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> work = new ArrayList<>();
                work.add(pool.submit(() -> { start.await(); try { requests.approve(owner, deck.id(), person); } catch (ResourceNotFoundException expected) { /* decided meanwhile */ } return null; }));
                work.add(pool.submit(() -> { start.await(); grants.revoke(owner, deck.id(), person); return null; }));
                work.add(pool.submit(() -> { start.await(); redeem(person, link.token()); return null; }));
                work.add(pool.submit(() -> { start.await(); grants.grantByLogin(owner, deck.id(), "someone"); return null; }));
                work.add(pool.submit(() -> { start.await(); grants.revoke(owner, deck.id(), person); return null; }));
                work.add(pool.submit(() -> { start.await(); redeem(person, link.token()); return null; }));
                start.countDown();
                // a deadlock would surface as a DeadlockLoserDataAccessException from one of them, or as a timeout
                for (Future<?> each : work) each.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_invite WHERE uses > COALESCE(max_uses, 2147483647)").query(Long.class).single()).isZero();
    }

    @Test
    void ownersRedemptionOfOwnInvitationIsANoOpAndInvalidValuesAreRefused() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), 3, 1);
        assertThat(redeem(owner, created.token())).isEqualTo(deck.code());
        assertThat(uses(created.invite().inviteId())).isZero();
        assertThat(grants(deck.id())).isZero();
        for (Integer bad : new Integer[] {0, -1, 1001}) {
            assertThatThrownBy(() -> invites.create(owner, deck.id(), bad, null)).as("maxUses " + bad).isInstanceOf(InvalidRequestException.class);
        }
        for (Integer bad : new Integer[] {0, -1, 366}) {
            assertThatThrownBy(() -> invites.create(owner, deck.id(), null, bad)).as("expiresInDays " + bad).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(redeem(UUID.randomUUID(), "not-a-token")).isNull();
        assertThat(redeem(UUID.randomUUID(), InviteTokens.generate(new SecureRandom()))).isNull();
    }

    @Test
    void concurrentRedemptionsNeverExceedMaxUses() throws Exception {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), 5, null);
        int accounts = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < accounts; index++) {
                UUID account = UUID.randomUUID();
                results.add(pool.submit(() -> {
                    start.await();
                    return redeem(account, created.token());
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<String> result : results) if (result.get() != null) admitted++;
            assertThat(admitted).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(uses(created.invite().inviteId())).isEqualTo(5);
        assertThat(grants(deck.id())).isEqualTo(5);
    }

    @Test
    void oneAccountRedeemingAOneTimeInvitationConcurrentlyConsumesItOnce() throws ExecutionException, InterruptedException {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        InviteService.Created created = invites.create(owner, deck.id(), 1, null);
        UUID account = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return redeem(account, created.token());
                }));
            }
            start.countDown();
            for (Future<String> result : results) assertThat(result.get()).isEqualTo(deck.code());
        } finally {
            pool.shutdownNow();
        }
        assertThat(uses(created.invite().inviteId())).isEqualTo(1);
        assertThat(grants(deck.id())).isEqualTo(1);
    }

    @Test
    void anOwnerHoldsTwentyActiveInvitationsAndDeadOldOnesArePrunedOnCreation() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        List<InviteService.Created> created = new ArrayList<>();
        for (int index = 0; index < InviteService.ACTIVE_LIMIT; index++) created.add(invites.create(owner, deck.id(), 1, null));
        assertThatThrownBy(() -> invites.create(owner, deck.id(), 1, null)).isInstanceOfSatisfying(ResourceLimitExceededException.class,
                refused -> assertThat(refused.extension().members()).containsEntry("limit", (long) InviteService.ACTIVE_LIMIT));
        // a revoked invitation is not active: a slot is free again
        invites.revoke(owner, deck.id(), created.getFirst().invite().inviteId());
        assertThat(invites.create(owner, deck.id(), 1, null).invite().status(clock.instant())).isEqualTo(Invite.Status.ACTIVE);
        assertThat(invitesOf(deck.id())).isEqualTo(21);
        // a month later two more are revoked; creating one prunes the dead ones that are older than 30 days (two of them) and keeps the active ones
        invites.revoke(owner, deck.id(), created.get(1).invite().inviteId());
        clock.advance(Duration.ofDays(31));
        InviteService.Created next = invites.create(owner, deck.id(), 1, null);
        assertThat(next.invite().createdAt()).isEqualTo(clock.instant());
        assertThat(invitesOf(deck.id())).isEqualTo(20);
        assertThat(invites.list(owner, deck.id(), "100", null).items()).noneMatch(each -> each.revokedAt() != null);
        // the time of DEATH counts, not the time of creation: an old invitation revoked just now is kept for 30 days
        invites.revoke(owner, deck.id(), created.get(3).invite().inviteId());
        InviteService.Created after = invites.create(owner, deck.id(), 1, null);
        assertThat(after).isNotNull();
        assertThat(invites.list(owner, deck.id(), "100", null).items()).anyMatch(each -> each.inviteId().equals(created.get(3).invite().inviteId()) && each.revokedAt() != null);
    }

    private long invitesOf(UUID deck) {
        return jdbc.sql("SELECT count(*) FROM app_learning.deck_invite WHERE deck_id = :deck").param("deck", deck).query(Long.class).single();
    }

    // ---- requests ----

    @Test
    void theAuthorIsToldOncePerDayAndARejectionIsSilentForThirtyDays() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        UUID asker = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThat(requests.send(asker, deck.code())).isEqualTo("PENDING");
        clock.advance(Duration.ofHours(1));
        assertThat(requests.send(second, deck.code())).isEqualTo("PENDING");
        assertThat(notificationParams(owner, "DECK_ACCESS_REQUESTED")).as("one notification for two requests of one day")
                .singleElement().asString().contains(deck.id().toString()).contains("\"pendingCount\": 1");
        clock.advance(Duration.ofHours(1));
        Instant firstCreated = jdbc.sql("SELECT created_at FROM app_learning.deck_access_request WHERE deck_id = :deck AND requester_id = :r")
                .param("deck", deck.id()).param("r", asker).query(java.sql.Timestamp.class).single().toInstant();
        assertThat(requests.send(asker, deck.code())).as("a repeat while open").isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT created_at FROM app_learning.deck_access_request WHERE deck_id = :deck AND requester_id = :r")
                .param("deck", deck.id()).param("r", asker).query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(firstCreated);

        assertThat(requests.reject(owner, deck.id(), asker).status()).isEqualTo("REJECTED");
        assertThat(notificationParams(asker, "DECK_ACCESS_GRANTED")).isEmpty();
        clock.advance(Duration.ofDays(10));
        assertThat(requests.send(asker, deck.code())).as("a repeat 10 days after the rejection looks the same").isEqualTo("PENDING");
        assertThat(status(deck.id(), asker)).isEqualTo("REJECTED");
        assertThat(notificationParams(owner, "DECK_ACCESS_REQUESTED")).hasSize(1);

        clock.advance(Duration.ofDays(21));
        assertThat(requests.send(asker, deck.code())).isEqualTo("PENDING");
        assertThat(status(deck.id(), asker)).as("after 30 days the request is new").isEqualTo("PENDING");
        // a new day, so a new notification; the other request is still open, so two are waiting
        List<String> told = notificationParams(owner, "DECK_ACCESS_REQUESTED");
        assertThat(told).hasSize(2);
        assertThat(told.getLast()).contains("\"pendingCount\": 2");
    }

    @Test
    void theNotificationDayIsTheUtcCalendarDay() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        clock.advance(Duration.ofHours(14).plusMinutes(59).plusSeconds(30));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-11T23:59:30Z"));
        requests.send(UUID.randomUUID(), deck.code());
        requests.send(UUID.randomUUID(), deck.code());
        assertThat(notificationParams(owner, "DECK_ACCESS_REQUESTED")).hasSize(1);
        clock.advance(Duration.ofSeconds(45));
        requests.send(UUID.randomUUID(), deck.code());
        assertThat(notificationParams(owner, "DECK_ACCESS_REQUESTED")).hasSize(2);
        assertThat(jdbc.sql("SELECT dedupe_key FROM app_learning.notification WHERE owner_id = :owner AND kind = 'DECK_ACCESS_REQUESTED' ORDER BY seq")
                .param("owner", owner).query(String.class).list())
                .containsExactly("deck-access:" + deck.id() + ":requested:20261011", "deck-access:" + deck.id() + ":requested:20261012");
    }

    @Test
    void aRequesterHasFiveNewRequestsInADayAndTwentyOpenOnes() {
        UUID asker = UUID.randomUUID();
        List<Shared> decksOfOne = new ArrayList<>();
        for (int index = 0; index < 21; index++) decksOfOne.add(shared(owner, DeckVisibility.INVITE));
        int next = 0;
        for (int day = 0; day < 4; day++) {
            for (int index = 0; index < 5; index++) assertThat(requests.send(asker, decksOfOne.get(next++).code())).isEqualTo("PENDING");
            if (day == 0) {
                clock.advance(Duration.ofHours(1));
                int blocked = next;
                assertThatThrownBy(() -> requests.send(asker, decksOfOne.get(blocked).code())).isInstanceOfSatisfying(RateLimitedException.class,
                        refused -> assertThat(refused.retryAfterSeconds()).as("the oldest request leaves the window in 23 hours").isEqualTo(23 * 3600));
                assertThat(status(decksOfOne.get(blocked).id(), asker)).as("a refused request writes nothing").isNull();
                clock.advance(Duration.ofHours(24));
            } else {
                clock.advance(Duration.ofHours(25));
            }
        }
        assertThat(next).isEqualTo(20);
        Shared twentyFirst = decksOfOne.get(20);
        assertThatThrownBy(() -> requests.send(asker, twentyFirst.code())).isInstanceOfSatisfying(ResourceLimitExceededException.class,
                refused -> assertThat(refused.extension().members()).containsEntry("limit", 20L));
        // the author's decision frees a slot: open means pending
        requests.reject(owner, decksOfOne.getFirst().id(), asker);
        assertThat(requests.send(asker, twentyFirst.code())).isEqualTo("PENDING");
        assertThat(status(twentyFirst.id(), asker)).isEqualTo("PENDING");
    }

    @Test
    void aSilentRepeatIsAnsweredBeforeTheLimits() {
        UUID asker = UUID.randomUUID();
        List<Shared> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) five.add(shared(owner, DeckVisibility.INVITE));
        for (Shared deck : five) requests.send(asker, deck.code());
        // five requests today: a sixth deck is refused, but repeating an open one still gets the plain answer
        assertThat(requests.send(asker, five.getFirst().code())).isEqualTo("PENDING");
        Shared sixth = shared(owner, DeckVisibility.INVITE);
        assertThatThrownBy(() -> requests.send(asker, sixth.code())).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void thereIsNothingToAskForUnlessTheDeckIsInvitationOnlyAndTheCallerCannotReadIt() {
        Shared invitation = shared(owner, DeckVisibility.INVITE);
        Shared link = shared(owner, DeckVisibility.LINK);
        Shared isPublic = shared(owner, DeckVisibility.PUBLIC);
        Shared priv = shared(owner, DeckVisibility.INVITE);
        fixtures.level(priv.deck(), DeckVisibility.PRIVATE);
        UUID stranger = UUID.randomUUID();
        UUID grantee = UUID.randomUUID();
        publications.grant(owner, invitation.id(), grantee, GrantRole.VIEWER);
        assertThatThrownBy(() -> requests.send(owner, invitation.code())).as("owner").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> requests.send(grantee, invitation.code())).as("grantee").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> requests.send(stranger, link.code())).as("LINK").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> requests.send(stranger, isPublic.code())).as("PUBLIC").isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> requests.send(stranger, priv.code())).as("private").isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> requests.send(stranger, "1".repeat(10))).as("unknown").isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> requests.send(stranger, "../etc")).as("malformed").isInstanceOf(ResourceNotFoundException.class);
        // the switch of the author
        jdbc.sql("UPDATE app_learning.deck_publication SET requests_enabled = FALSE, row_version = row_version + 1 WHERE deck_id = :deck").param("deck", invitation.id()).update();
        assertThatThrownBy(() -> requests.send(stranger, invitation.code())).as("requests off").isInstanceOf(DeckInviteOnlyException.class);
        assertThat(status(invitation.id(), stranger)).isNull();
        assertThat(notificationParams(owner, "DECK_ACCESS_REQUESTED")).isEmpty();
        // the kill switch of the public routes
        AccessRequestService off = new AccessRequestService(new PublicRouteSettings(false, 120, 600, 600, 600, 50_000, 3), access, repository, library,
                notifications, metrics, transactions, clock);
        assertThatThrownBy(() -> off.send(stranger, invitation.code())).isInstanceOf(ResourceNotFoundException.class);
    }

    private String status(UUID deck, UUID requester) {
        return jdbc.sql("SELECT status FROM app_learning.deck_access_request WHERE deck_id = :deck AND requester_id = :r").param("deck", deck).param("r", requester)
                .query(String.class).optional().orElse(null);
    }

    @Test
    void approvingGrantsAndNotifiesOnceAndRevokingTheGrantStartsTheThirtyDaysAgain() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        UUID asker = UUID.randomUUID();
        GrantService grants = new GrantService(repository, library, username -> java.util.Optional.empty(), new ProfileLookupBudget(), metrics, transactions, clock);
        requests.send(asker, deck.code());
        AccessRequestService.Decision approved = requests.approve(owner, deck.id(), asker);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(readable(asker, deck.code())).isTrue();
        assertThat(notificationParams(asker, "DECK_ACCESS_GRANTED")).singleElement().asString().contains(deck.code()).doesNotContain(deck.id().toString());
        clock.advance(Duration.ofMinutes(3));
        assertThat(requests.approve(owner, deck.id(), asker)).as("the same decision again is the same answer").isEqualTo(approved);
        assertThat(notificationParams(asker, "DECK_ACCESS_GRANTED")).hasSize(1);
        assertThatThrownBy(() -> requests.reject(owner, deck.id(), asker)).as("the opposite decision").isInstanceOf(ResourceNotFoundException.class);

        grants.revoke(owner, deck.id(), asker);
        assertThat(readable(asker, deck.code())).isFalse();
        assertThat(status(deck.id(), asker)).isEqualTo("REJECTED");
        clock.advance(Duration.ofDays(2));
        assertThat(requests.send(asker, deck.code())).isEqualTo("PENDING");
        assertThat(status(deck.id(), asker)).as("asking again within 30 days of the revocation is silent").isEqualTo("REJECTED");
        assertThatThrownBy(() -> requests.approve(owner, deck.id(), UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> requests.approve(UUID.randomUUID(), deck.id(), asker)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anInvitationOrALoginGrantClosesAWaitingRequestWithoutANotification() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        UUID byLink = UUID.randomUUID();
        UUID byLogin = UUID.randomUUID();
        requests.send(byLink, deck.code());
        requests.send(byLogin, deck.code());
        invites.redeem(byLink, invites.create(owner, deck.id(), null, null).token());
        GrantService grants = new GrantService(repository, library, username -> java.util.Optional.of(byLogin), new ProfileLookupBudget(), metrics, transactions, clock);
        grants.grantByLogin(owner, deck.id(), "some.login");
        assertThat(status(deck.id(), byLink)).isEqualTo("APPROVED");
        assertThat(status(deck.id(), byLogin)).isEqualTo("APPROVED");
        assertThat(notificationParams(byLink, "DECK_ACCESS_GRANTED")).isEmpty();
        assertThat(notificationParams(byLogin, "DECK_ACCESS_GRANTED")).isEmpty();
    }

    @Test
    void theListsPageByKeysetNewestFirst() {
        Shared deck = shared(owner, DeckVisibility.INVITE);
        List<UUID> askers = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            UUID asker = UUID.randomUUID();
            askers.add(asker);
            requests.send(asker, deck.code());
            invites.create(owner, deck.id(), null, null);
            repository.addGrant(deck.id(), asker, owner, clock.instant());
            clock.advance(Duration.ofSeconds(1));
        }
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            AccessRequestService.Page page = requests.list(owner, deck.id(), "2", cursor);
            assertThat(page.items().size()).isLessThanOrEqualTo(2);
            page.items().forEach(item -> seen.add(item.requesterId()));
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        List<UUID> newestFirst = new ArrayList<>(askers);
        java.util.Collections.reverse(newestFirst);
        assertThat(seen).containsExactlyElementsOf(newestFirst);

        List<UUID> inviteIds = new ArrayList<>();
        cursor = null;
        do {
            InviteService.Page page = invites.list(owner, deck.id(), "2", cursor);
            page.items().forEach(item -> inviteIds.add(item.inviteId()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(inviteIds).hasSize(5).doesNotHaveDuplicates();
        GrantService grants = new GrantService(repository, library, username -> java.util.Optional.empty(), new ProfileLookupBudget(), metrics, transactions, clock);
        List<UUID> granted = new ArrayList<>();
        cursor = null;
        do {
            GrantService.Page page = grants.list(owner, deck.id(), "2", cursor);
            page.items().forEach(item -> granted.add(item.granteeId()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(granted).containsExactlyElementsOf(newestFirst);
    }
}
