package app.mnema.learning.generation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The retention of sessions: expiry, the readable window, the purge, the warning and the events of an ended session. */
class GenerationRetentionIntegrationTest extends GenerationReviewSupport {
    @Autowired private SessionRetention retention;
    @Autowired private GenerationRepository repository;

    private void expireAt(UUID session, String interval) {
        jdbc.sql("UPDATE app_learning.generation_session SET expires_at=CURRENT_TIMESTAMP + CAST(:interval AS interval) WHERE session_id=:id")
                .param("interval", interval).param("id", session).update();
    }

    private int rows(String table, UUID session) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning." + table + " WHERE session_id=:id").param("id", session)
                .query(Integer.class).single();
    }

    @Test
    void anExpiredSessionStaysReadableThenIsPurgedWithItsHoldsWhilePublishedMaterialsAndProvenanceStay() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "опубликуем");
        UUID second = note(owner, deck, "[[fake:audio-hold]] останется предложением");
        UUID session = start(owner, deck, audioSpec(null, noteSource(first, 0), noteSource(second, 0)));
        awaitState(session, "REVIEW");
        List<Proposal> all = proposals(owner, deck, session);
        UUID asset = readyAsset(owner, all.get(1).artifact());
        assertThat(approve(owner, deck, all.get(0), UUID.randomUUID()).getStatus()).isEqualTo(200);
        // an asset nobody else holds, past its own hold: the session's hold keeps it out of the media GC
        jdbc.sql("UPDATE app_learning.media_asset SET owner_hold_until=CURRENT_TIMESTAMP - interval '1 hour' WHERE asset_id=:id").param("id", asset).update();
        assertThat(media.expireUnattached(1_000)).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:id").param("id", asset).query(String.class).single())
                .isEqualTo("READY");

        // at expires_at the session ends EXPIRED and is readable; nothing else can be done with it
        expireAt(session, "-1 hour");
        SessionRetention.Pass pass = retention.run();
        assertThat(pass.expired()).isGreaterThanOrEqualTo(1);
        JsonNode detail = json(getSession(owner, deck, session));
        assertThat(detail.path("state").stringValue(null)).isEqualTo("EXPIRED");
        assertThat(detail.path("endReason").stringValue(null)).isEqualTo("EXPIRED");
        assertThat(detail.path("artifacts")).hasSize(2);
        List<String> last = new ArrayList<>();
        json(events(owner, deck, session, "")).path("events").forEach(event -> last.add(event.path("type").stringValue(null)
                + ":" + event.path("payload").path("state").stringValue("-")));
        assertThat(last.getLast()).isEqualTo("SESSION_STATE:EXPIRED");
        Proposal pending = fresh(owner, deck, all.get(1));
        problem(approve(owner, deck, pending, approvalBody(UUID.randomUUID(), pending, deckRevision(deck)), "\"" + deckVersion(deck) + "\""), 409, "GENERATION_STATE_CONFLICT");
        problem(cancel(owner, deck, session, UUID.randomUUID()), 409, "GENERATION_STATE_CONFLICT");
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        // the expiry no longer protects the asset, but the purge has not run yet and a second pass does nothing to a readable session
        assertThat(retention.run().purged()).isZero();
        assertThat(getSession(owner, deck, session).getStatus()).isEqualTo(200);

        // after the readable window the purge deletes the unpublished rows and the holds
        expireAt(session, "-3 days");
        SessionRetention.Pass purge = retention.run();
        assertThat(purge.purged()).isGreaterThanOrEqualTo(1);
        problem(getSession(owner, deck, session), 404, "RESOURCE_NOT_FOUND");
        problem(events(owner, deck, session, ""), 404, "RESOURCE_NOT_FOUND");
        problem(send(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/decks/" + deck + "/generation-sessions/" + session
                + "/artifacts/" + all.get(0).artifact())), 404, "RESOURCE_NOT_FOUND");
        problem(deleteSession(owner, deck, session), 404, "RESOURCE_NOT_FOUND");
        for (String table : List.of("generation_session", "generation_artifact", "generation_artifact_revision", "generation_media_slot",
                "generation_media_ref", "generation_step", "generation_event")) {
            assertThat(rows(table, session)).as(table).isZero();
        }
        // published content and its provenance stay
        assertThat(materials(owner, deck)).isEqualTo(1);
        assertThat(rows("generation_provenance", session)).isEqualTo(1);
        // the hold is gone: the asset is now collected by the media GC
        media.expireUnattached(1_000);
        assertThat(jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:id").param("id", asset).query(String.class).single())
                .isEqualTo("DELETED");
    }

    @Test
    void closedAndCancelledSessionsArePurgedAtTheirExpiryAndLiveOnesOfOtherOwnersAreNotTouched() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal done = proposal(owner, deck, spec("20 глаголов движения"));
        assertThat(approve(owner, deck, done, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(sessionState(done.session())).isEqualTo("CLOSED");
        UUID cancelled = start(owner, deck, spec("[[fake:block]] долго"));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(cancel(owner, deck, cancelled, UUID.randomUUID()).getStatus()).isEqualTo(200);
        provider.release.countDown();
        UUID bystander = UUID.randomUUID();
        UUID bystanderDeck = deck(bystander);
        Proposal alive = proposal(bystander, bystanderDeck, spec("20 глаголов движения"));

        expireAt(done.session(), "-1 minute");
        expireAt(cancelled, "-1 minute");
        retention.run();
        assertThat(rows("generation_session", done.session())).isZero();
        assertThat(rows("generation_session", cancelled)).isZero();
        assertThat(materials(owner, deck)).isEqualTo(1);
        assertThat(sessionState(alive.session())).isEqualTo("REVIEW");
        assertThat(rows("generation_artifact", alive.session())).isEqualTo(1);
    }

    @Test
    void theOwnerIsWarnedThreeDaysBeforeExpiryOncePerExpiryDateAndOnlyWhileSomethingWouldBeDeleted() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal pending = proposal(owner, deck, spec("20 глаголов движения"));
        Proposal far = proposal(owner, deck, spec("20 глаголов движения"));
        Proposal published = proposal(owner, deck, spec("20 глаголов движения"));
        assertThat(approve(owner, deck, published, UUID.randomUUID()).getStatus()).isEqualTo(200);
        expireAt(pending.session(), "2 days");
        expireAt(far.session(), "10 days");
        expireAt(published.session(), "2 days");

        assertThat(retention.run().warned()).isEqualTo(1);
        assertThat(notificationKinds(owner)).filteredOn("GENERATION_SESSION_EXPIRING"::equals).hasSize(1);
        JsonNode params = JSON.readTree(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:owner "
                + "AND kind='GENERATION_SESSION_EXPIRING'").param("owner", owner).query(String.class).single());
        assertThat(params.path("sessionId").stringValue(null)).isEqualTo(pending.session().toString());
        assertThat(params.path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(params.path("pendingCount").intValue()).isEqualTo(1);
        assertThat(Instant.parse(params.path("expiresAt").stringValue(null))).isAfter(Instant.now());
        assertThat(params.propertyNames()).containsExactlyInAnyOrder("deckId", "sessionId", "expiresAt", "pendingCount");
        // the same expiry date is not announced twice; activity that moves the expiry is a new date and a new notice
        retention.run();
        assertThat(notificationKinds(owner)).filteredOn("GENERATION_SESSION_EXPIRING"::equals).hasSize(1);
        // the sweep no longer even selects a session that was warned for this expiry date
        assertThat(repository.expiring(java.time.Duration.ofDays(3), new UUID(0, 0), 100)).isEmpty();
        expireAt(pending.session(), "1 day");
        retention.run();
        assertThat(notificationKinds(owner)).filteredOn("GENERATION_SESSION_EXPIRING"::equals).hasSize(2);
    }

    @Test
    void theEventsOfAnEndedSessionAreDeletedADayAfterItEndedAndTheSessionStaysReadable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal done = proposal(owner, deck, spec("20 глаголов движения"));
        assertThat(approve(owner, deck, done, UUID.randomUUID()).getStatus()).isEqualTo(200);
        Proposal live = proposal(owner, deck, spec("20 глаголов движения"));
        assertThat(rows("generation_event", done.session())).isPositive();

        retention.run();
        assertThat(rows("generation_event", done.session())).isPositive();
        jdbc.sql("UPDATE app_learning.generation_session SET last_activity_at=CURRENT_TIMESTAMP - interval '2 days' WHERE session_id=:id")
                .param("id", done.session()).update();
        assertThat(retention.run().eventsDeleted()).isPositive();
        assertThat(rows("generation_event", done.session())).isZero();
        assertThat(getSession(owner, deck, done.session()).getStatus()).isEqualTo(200);
        assertThat(json(events(owner, deck, done.session(), "")).path("events")).isEmpty();
        // the log of a live session is never touched
        jdbc.sql("UPDATE app_learning.generation_session SET last_activity_at=CURRENT_TIMESTAMP - interval '2 days' WHERE session_id=:id")
                .param("id", live.session()).update();
        retention.run();
        assertThat(rows("generation_event", live.session())).isPositive();
    }
}
