package app.mnema.learning.onboarding;

import app.mnema.learning.fork.ForkWorlds;
import app.mnema.learning.fork.ForkWorlds.Source;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.MediaFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static app.mnema.learning.onboarding.OnboardingFixture.CONTRACT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /api/onboarding/sample-deck} and the {@code sampleDeck} member of {@code GET /api/onboarding} through the real application: the copy of the published template
 * (201), the live copy (200), idempotency by command id, one live deck under parallel requests, a new copy after a deletion, a template that is not ready, the marking of the template
 * at startup, and the update of the template reaching the copy as the ordinary «Есть обновление». The answers are compared with {@code contracts/onboarding/journey.json}.
 */
class SampleDeckHttpIntegrationTest extends SampleDeckIntegrationTest {
    private static TreeSet<String> names(JsonNode node) {
        TreeSet<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    // ---- the copy ----

    @Test
    void theFirstRequestCopiesThePublishedRevisionAs201AndARepeatAnswersTheLiveCopyAs200() {
        Source template = template(25, 3);
        UUID account = UUID.randomUUID();
        assertThat(names(sampleDeck(account))).isEqualTo(names(CONTRACT.path("views").path("sampleReady").path("sampleDeck")));
        JsonNode before = sampleDeck(account);
        assertThat(before.path("available").booleanValue(false)).isTrue();
        assertThat(before.path("deckId").isNull()).isTrue();
        assertThat(before.path("freeChecksLeft").intValue()).isEqualTo(3);
        String tagBefore = onboarding(account, NONE).headers().firstValue("etag").orElseThrow();

        HttpResponse<String> created = post(account);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("cache-control")).contains("private, no-store");
        assertThat(created.headers().firstValue("idempotency-replayed")).isEmpty();
        JsonNode view = json(created);
        assertThat(names(view)).isEqualTo(names(CONTRACT.path("views").path("sampleReady")));
        assertThat(view.path("enabled").booleanValue(false)).isTrue();
        // no journey row is needed or created: the account was never offered the tour
        assertThat(view.path("journey").path("state").stringValue(null)).isEqualTo("NOT_OFFERED");
        assertThat(count("SELECT count(*) FROM app_learning.onboarding_journey WHERE account_id = :p0", account)).isZero();
        UUID deck = UUID.fromString(view.path("sampleDeck").path("deckId").stringValue(null));
        assertThat(view.path("sampleDeck").path("available").booleanValue(false)).isTrue();
        assertThat(view.path("sampleDeck").path("updateAvailable").booleanValue(true)).isFalse();
        assertThat(view.path("sampleDeck").path("freeChecksLeft").intValue()).isEqualTo(3);

        // the copy: a deck of the account marked SAMPLE, a copy of exactly the published revision of the template, materialized by the ordinary job
        assertThat(purpose(deck)).isEqualTo("SAMPLE");
        assertThat(purpose(template.deck())).isEqualTo("SAMPLE_TEMPLATE");
        assertThat(jdbc.sql("SELECT upstream_deck_id = :t AND base_revision_id = (SELECT published_revision_id FROM app_learning.deck_publication WHERE deck_id = :t) AND released_at IS NULL "
                + "FROM app_learning.deck_fork WHERE deck_id = :d").param("t", template.deck()).param("d", deck).query(Boolean.class).single()).isTrue();
        JsonNode ready = worlds.awaitReady(account, deck);
        assertThat(ready.path("fork").path("sourceAvailable").booleanValue(false)).isTrue();
        assertThat(ready.path("memberCount").intValue()).isEqualTo(25);
        assertThat(ready.path("exerciseCount").intValue()).isEqualTo(3);
        worlds.assertHeadsEqualPublishedManifest(template.deck(), deck);

        // the view changed, so did its ETag
        HttpResponse<String> read = onboarding(account, NONE);
        String tagAfter = read.headers().firstValue("etag").orElseThrow();
        assertThat(tagAfter).isNotEqualTo(tagBefore);
        assertThat(json(read).path("sampleDeck").path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(onboarding(account, Map.of("If-None-Match", tagAfter)).statusCode()).isEqualTo(304);
        assertThat(onboarding(account, Map.of("If-None-Match", tagBefore)).statusCode()).isEqualTo(200);

        // another request (another command id), a second tab: 200 with the same copy; nothing new is made
        HttpResponse<String> again = post(account);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(json(again).path("sampleDeck").path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(again.headers().firstValue("idempotency-replayed")).isEmpty();
        assertThat(liveSamples(account)).isOne();
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :p0", account)).isOne();
    }

    @Test
    void aRepeatedCommandIdReplaysTheCreationAndAnotherAccountCannotUseIt() {
        template(12, 2);
        UUID account = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        HttpResponse<String> first = post(account, command);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        UUID deck = UUID.fromString(json(first).path("sampleDeck").path("deckId").stringValue(null));

        HttpResponse<String> replay = post(account, command);
        assertThat(replay.statusCode()).as("the replay answers what the command answered").isEqualTo(201);
        assertThat(replay.headers().firstValue("idempotency-replayed")).contains("true");
        assertThat(json(replay).path("sampleDeck").path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(liveSamples(account)).isOne();

        // the replay of a command that found the live copy is a 200 again
        UUID second = UUID.randomUUID();
        assertThat(post(account, second).statusCode()).isEqualTo(200);
        HttpResponse<String> replayed200 = post(account, second);
        assertThat(replayed200.statusCode()).isEqualTo(200);
        assertThat(replayed200.headers().firstValue("idempotency-replayed")).contains("true");

        // the same command id from another account is a conflict, not a copy for them
        UUID stranger = UUID.randomUUID();
        problem(post(stranger, command), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(liveSamples(stranger)).isZero();
    }

    @Test
    void parallelRequestsOfOneAccountEndWithOneLiveDeckAndTheLosersAnswerTheWinners() throws Exception {
        template(12, 2);
        UUID account = UUID.randomUUID();
        int requests = 8;
        CountDownLatch go = new CountDownLatch(1);
        UUID shared = UUID.randomUUID();
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int index = 0; index < requests; index++) {
            // two of the requests share a command id: they are one command
            UUID command = index < 2 ? shared : UUID.randomUUID();
            calls.add(() -> {
                go.await();
                return post(account, command);
            });
        }
        List<HttpResponse<String>> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (Callable<HttpResponse<String>> call : calls) futures.add(pool.submit(call));
            go.countDown();
            for (Future<HttpResponse<String>> future : futures) answers.add(future.get());
        }
        assertThat(answers).allSatisfy(answer -> assertThat(answer.statusCode()).as(answer.body()).isIn(200, 201));
        assertThat(answers.stream().filter(answer -> answer.statusCode() == 201).count()).as("one request made the copy").isGreaterThanOrEqualTo(1);
        // the winner is 201; the second copy of its command id replays it (201 too), every other request found the live deck
        assertThat(answers.stream().map(answer -> json(answer).path("sampleDeck").path("deckId").stringValue(null)).distinct()).hasSize(1);
        assertThat(liveSamples(account)).isOne();
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :p0 AND released_at IS NULL", account)).isOne();
        assertThat(count("SELECT count(*) FROM app_learning.deck WHERE owner_id = :p0", account)).isOne();
    }

    @Test
    void aDeletedSampleDeckIsReplacedByANewCopyOnTheNextRequest() {
        Source template = template(12, 2);
        UUID account = UUID.randomUUID();
        UUID first = sample(account);
        worlds.awaitReady(account, first);
        delete(account, first);
        assertThat(liveSamples(account)).isZero();
        assertThat(sampleDeck(account).path("deckId").isNull()).isTrue();

        HttpResponse<String> again = post(account);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        UUID second = UUID.fromString(json(again).path("sampleDeck").path("deckId").stringValue(null));
        assertThat(second).isNotEqualTo(first);
        assertThat(liveSamples(account)).isOne();
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE owner_id = :p0 AND upstream_deck_id = :p1", account, template.deck())).isEqualTo(2);
        worlds.awaitReady(account, second);
    }

    @Test
    void theEditorialAccountThatOwnsTheTemplateGetsASampleCopyToo() {
        Source template = template(12, 2);
        HttpResponse<String> response = post(template.author());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        UUID deck = UUID.fromString(json(response).path("sampleDeck").path("deckId").stringValue(null));
        assertThat(purpose(deck)).isEqualTo("SAMPLE");
        worlds.awaitReady(template.author(), deck);
        assertThat(purpose(template.deck())).isEqualTo("SAMPLE_TEMPLATE");
    }

    @Test
    void theRequestIsStrictAndNeedsABearer() {
        template(12, 2);
        UUID account = UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        for (String body : new String[] {"{}", "{\"commandId\":\"" + id + "\",\"rowVersion\":\"0\"}", "{\"commandId\":1}", "{\"commandId\":\"nope\"}", "[]", "not json", ""}) {
            HttpResponse<String> response = HttpIdentityFixture.send(port, "POST", "/onboarding/sample-deck", HttpIdentityFixture.reader(account), body, NONE);
            assertThat(response.statusCode()).as(body).isEqualTo(400);
            assertThat(json(response).path("code").stringValue(null)).as(body).isEqualTo("INVALID_REQUEST");
        }
        assertThat(HttpIdentityFixture.send(port, "POST", "/onboarding/sample-deck", null, "{\"commandId\":\"" + id + "\"}", NONE).statusCode()).isEqualTo(401);
        assertThat(HttpIdentityFixture.send(port, "POST", "/onboarding/sample-deck", HttpIdentityFixture.token(account, "learning.read"), "{\"commandId\":\"" + id + "\"}", NONE)
                .statusCode()).as("writing needs learning.write").isEqualTo(403);
        assertThat(liveSamples(account)).isZero();
    }

    // ---- availability ----

    @Test
    void withoutAReadyTemplateTheAnswerIs409AndTheViewSaysNotAvailable() {
        UUID account = UUID.randomUUID();
        // none configured
        problem(post(account), 409, "SAMPLE_DECK_UNAVAILABLE");
        assertThat(json(post(account)).path("instance").stringValue(null)).isEqualTo("/api/onboarding/sample-deck");
        assertThat(sampleDeck(account).path("available").booleanValue(true)).isFalse();

        // configured but never published
        UUID author = UUID.randomUUID();
        UUID unpublished = worlds.shared.deck(author, "Не опубликована");
        SampleDeckTestConfiguration.template(context, unpublished);
        assertThat(purpose(unpublished)).isEqualTo("SAMPLE_TEMPLATE");
        problem(post(account), 409, "SAMPLE_DECK_UNAVAILABLE");
        assertThat(sampleDeck(account).path("available").booleanValue(true)).isFalse();
        assertThat(count("SELECT count(*) FROM app_learning.command_receipt WHERE actor_id = :p0", account)).as("a refused command stores no receipt").isZero();
        assertThat(liveSamples(account)).isZero();

        // published: the editor is allowed to publish the template at LINK even after it was marked, and the same account is served at once
        worlds.study.addMaterial(author, unpublished, "слово", "перевод");
        assertThat(worlds.publishRaw(author, unpublished, "LINK").statusCode()).isEqualTo(200);
        assertThat(sampleDeck(account).path("available").booleanValue(false)).isTrue();
        assertThat(post(account).statusCode()).isEqualTo(201);
    }

    @Test
    void aTemplateThatIsDeletedNotMarkedOrNotAtLinkIsNotAvailable() {
        UUID account = UUID.randomUUID();
        // deleted
        Source deleted = worlds.source("LINK", 12, 1);
        SampleDeckTestConfiguration.template(context, deleted.deck());
        assertThat(sampleDeck(account).path("available").booleanValue(false)).isTrue();
        delete(deleted.author(), deleted.deck());
        problem(post(account), 409, "SAMPLE_DECK_UNAVAILABLE");
        assertThat(sampleDeck(account).path("available").booleanValue(true)).isFalse();

        // configured but the marker has not seen it yet (a deck created after startup): an ordinary deck is not copied as a sample
        Source late = worlds.source("LINK", 12, 1);
        SampleDeckTestConfiguration.templateWithoutMarking(late.deck());
        assertThat(purpose(late.deck())).isEqualTo("USER");
        problem(post(account), 409, "SAMPLE_DECK_UNAVAILABLE");
        assertThat(sampleDeck(account).path("available").booleanValue(true)).isFalse();

        // marked, but published at a level a copy cannot read updates through: the invitation level needs a grant, the catalogue level lists the deck
        for (String level : new String[] {"INVITE", "PUBLIC"}) {
            Source other = worlds.source(level, 12, 1);
            SampleDeckTestConfiguration.template(context, other.deck());
            assertThat(purpose(other.deck())).isEqualTo("SAMPLE_TEMPLATE");
            problem(post(account), 409, "SAMPLE_DECK_UNAVAILABLE");
            assertThat(sampleDeck(account).path("available").booleanValue(true)).as(level).isFalse();
        }
        assertThat(liveSamples(account)).isZero();
    }

    @Test
    void anAccountThatAlreadyHasACopyKeepsSeeingItWhenTheTemplateGoesAway() {
        Source template = template(12, 2);
        UUID account = UUID.randomUUID();
        UUID deck = sample(account);
        worlds.awaitReady(account, deck);
        delete(template.author(), template.deck());
        JsonNode view = sampleDeck(account);
        assertThat(view.path("available").booleanValue(true)).isFalse();
        assertThat(view.path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(view.path("updateAvailable").booleanValue(true)).isFalse();
    }

    // ---- the template ----

    @Test
    void theTemplateIsMarkedOnceAndNothingElseIsTouched() {
        Source template = worlds.source("LINK", 12, 2);
        Source other = worlds.source("LINK", 12, 2);
        UUID gone = other.deck();
        delete(other.author(), gone);
        long version = jdbc.sql("SELECT row_version FROM app_learning.deck WHERE deck_id = :d").param("d", template.deck()).query(Long.class).single();

        SampleDeckTestConfiguration.template(context, template.deck());
        assertThat(purpose(template.deck())).isEqualTo("SAMPLE_TEMPLATE");
        assertThat(jdbc.sql("SELECT row_version FROM app_learning.deck WHERE deck_id = :d").param("d", template.deck()).query(Long.class).single()).as("the editor's row version is untouched").isEqualTo(version);
        assertThat(count("SELECT count(*) FROM app_learning.deck WHERE purpose <> 'USER' AND deck_id = :p0", other.deck())).as("the other deck is not marked").isZero();
        // idempotent
        SampleDeckTestConfiguration.template(context, template.deck());
        assertThat(purpose(template.deck())).isEqualTo("SAMPLE_TEMPLATE");
        // a deleted deck and a deck that does not exist are not marked and do not stop anything
        SampleDeckTestConfiguration.template(context, gone);
        assertThat(purpose(gone)).isEqualTo("USER");
        SampleDeckTestConfiguration.template(context, UUID.randomUUID());
        // a sample copy is never re-marked as the template
        SampleDeckTestConfiguration.template(context, template.deck());
        UUID copy = sample(UUID.randomUUID());
        SampleDeckTestConfiguration.template(context, copy);
        assertThat(purpose(copy)).isEqualTo("SAMPLE");

        // the deck guard still lets the editor work: a new material and a new revision of the template, and the owner reads it
        SampleDeckTestConfiguration.template(context, template.deck());
        worlds.study.addMaterial(template.author(), template.deck(), "ещё", "одно");
        assertThat(decks.read(template.author(), template.deck()).path("memberCount").intValue()).isEqualTo(13);
    }

    @Test
    void thePurposeOfADeckCanNotBeChangedAnyOtherWay() {
        Source template = template(12, 2);
        UUID copy = sample(UUID.randomUUID());
        for (String statement : new String[] {
                "UPDATE app_learning.deck SET purpose = 'SAMPLE' WHERE deck_id = :d", "UPDATE app_learning.deck SET purpose = 'USER' WHERE deck_id = :d",
                "UPDATE app_learning.deck SET purpose = 'SAMPLE_TEMPLATE' WHERE deck_id = :s", "UPDATE app_learning.deck SET purpose = 'USER' WHERE deck_id = :s"}) {
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> jdbc.sql(statement).param("d", template.deck()).param("s", copy).update(), statement);
        }
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.sql("UPDATE app_learning.deck SET purpose = 'OTHER' WHERE deck_id = :d").param("d", copy).update());
        // a second live sample deck of one account is refused by the index even if a path forgot the lock
        UUID account = jdbc.sql("SELECT owner_id FROM app_learning.deck WHERE deck_id = :d").param("d", copy).query(UUID.class).single();
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> jdbc.sql("""
                INSERT INTO app_learning.deck(deck_id, owner_id, reuse_scope_id, head_revision_id, row_version, created_at, purpose)
                SELECT gen_random_uuid(), owner_id, reuse_scope_id, head_revision_id, 0, created_at, 'SAMPLE' FROM app_learning.deck WHERE deck_id = :d
                """).param("d", copy).update());
        assertThat(liveSamples(account)).isOne();
    }

    @Test
    void theTemplateOwnersNewRevisionReachesTheCopyAsTheOrdinaryUpdateBadge() {
        Source template = template(12, 2);
        UUID account = UUID.randomUUID();
        UUID deck = sample(account);
        worlds.awaitReady(account, deck);
        assertThat(sampleDeck(account).path("updateAvailable").booleanValue(true)).isFalse();
        String before = onboarding(account, NONE).headers().firstValue("etag").orElseThrow();

        // the editorial account publishes a new revision through the ordinary command: allowed, the level stays LINK
        UUID revision = worlds.republish(template, "LINK");
        assertThat(worlds.publishedRevision(template.deck())).isEqualTo(revision);

        assertThat(sampleDeck(account).path("updateAvailable").booleanValue(false)).isTrue();
        assertThat(worlds.forkOf(account, deck).path("updateAvailable").booleanValue(false)).as("the same flag as the deck's own read").isTrue();
        assertThat(onboarding(account, NONE).headers().firstValue("etag").orElseThrow()).isNotEqualTo(before);
    }

    @Test
    void theTemplatesMediaIsReadableByTheCopyAndByNobodyElse() {
        UUID editor = UUID.randomUUID();
        HttpIdentityFixture.publicProfile(editor, true);
        UUID image = worlds.study.readyAsset(editor, "image/png");
        MediaFixtures.variants(jdbc, image, "image/webp");
        UUID deck = worlds.shared.deck(editor, "Шаблон с картинкой");
        worlds.shared.create(editor, deck, MediaFixtures.imageDocument(image));
        worlds.publish(editor, deck, "LINK");
        SampleDeckTestConfiguration.template(context, deck);

        UUID learner = UUID.randomUUID();
        UUID copy = sample(learner);
        worlds.awaitReady(learner, copy);
        // CD-6: the owner of the copy reads the derived variants of the assets its heads reference, never the original
        var playback = media.playback(learner, image);
        assertThat(playback.playable()).isNotNull();
        assertThat(playback.original()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> media.playback(UUID.randomUUID(), image)).isInstanceOf(app.mnema.learning.platform.api.ResourceNotFoundException.class);
    }

    @Test
    void theMarkerRunsWhenTheApplicationIsReadyAndASampleDeckAloneKeepsAnAccountEligible() {
        Source template = worlds.source("LINK", 12, 2);
        SampleDeckTestConfiguration.templateWithoutMarking(template.deck());
        assertThat(purpose(template.deck())).isEqualTo("USER");
        context.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(new org.springframework.boot.SpringApplication(), new String[0],
                (org.springframework.context.ConfigurableApplicationContext) context, java.time.Duration.ZERO));
        assertThat(purpose(template.deck())).as("the listener of the ready event marked it").isEqualTo("SAMPLE_TEMPLATE");

        // the tour's own sample deck is not a deck the account owned before: a new account that asked for it first is still offered the tour
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, false, true);
        assertThat(post(account).statusCode()).isEqualTo(201);
        JsonNode view = json(onboarding(account, NONE));
        assertThat(view.path("journey").path("state").stringValue(null)).isEqualTo("OFFERED");
        assertThat(view.path("journey").path("eligible").booleanValue(false)).isTrue();
        // an ordinary deck of its own does make it ineligible, as before
        UUID other = UUID.randomUUID();
        HttpIdentityFixture.signals(other, false, true);
        worlds.shared.deck(other, "Своя");
        assertThat(json(onboarding(other, NONE)).path("journey").path("state").stringValue(null)).isEqualTo("NOT_OFFERED");
    }

    // ---- views of the command responses ----

    @Test
    void theTourCommandsReportTheSampleDeckToo() {
        template(12, 2);
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, false, true);
        JsonNode view = json(onboarding(account, NONE));
        assertThat(view.path("journey").path("state").stringValue(null)).isEqualTo("OFFERED");
        UUID deck = sample(account);
        ObjectNode start = ForkWorlds.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("rowVersion", "0").put("action", "START").put("stepKey", "welcome");
        HttpResponse<String> journey = HttpIdentityFixture.send(port, "PUT", "/onboarding/journey", HttpIdentityFixture.reader(account), start.toString(), NONE);
        assertThat(journey.statusCode()).as(journey.body()).isEqualTo(200);
        assertThat(json(journey).path("sampleDeck").path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(json(journey).path("sampleDeck").path("freeChecksLeft").intValue()).isEqualTo(3);
    }
}
