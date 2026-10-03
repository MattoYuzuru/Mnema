package app.mnema.learning.generation;

import app.mnema.learning.usage.EstimateController;
import app.mnema.learning.usage.ReservationScope;
import app.mnema.learning.usage.UsageLedger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The HTTP contract of {@code contracts/generation/http.json} for the operations of this task: status codes, headers,
 * problem codes and members, the evaluation order and the opaque 404.
 */
class GenerationHttpContractTest extends GenerationIntegrationTest {
    @Autowired private UsageLedger ledger;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private EstimateController estimates;

    private static void problem(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode body = json(response);
        assertThat(body.path("code").stringValue(null)).isEqualTo(code);
        assertThat(body.path("type").stringValue(null)).isEqualTo("urn:mnema:problem:" + code.toLowerCase().replace('_', '-'));
        assertThat(body.path("status").intValue()).isEqualTo(status);
    }

    private static JsonNode problemBody(MockHttpServletResponse response) throws Exception {
        return json(response);
    }

    // ----------------------------------------------------------------- opaque 404

    @Test
    void aForeignAndAnAbsentDeckSessionAndArtifactShareOneOpaqueNotFoundAndOwnershipComesBeforeValidation() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        awaitState(session, "REVIEW");
        UUID artifact = UUID.fromString(json(getSession(owner, deck, session)).path("artifacts").get(0).path("artifactId").stringValue(null));
        UUID absent = UUID.randomUUID();
        String base = "/decks/" + deck + "/generation-sessions/";

        List<MockHttpServletResponse> foreign = new ArrayList<>();
        foreign.add(getSession(stranger, deck, session));
        foreign.add(getSession(stranger, absent, session));
        foreign.add(getSession(owner, deck, absent));
        foreign.add(events(stranger, deck, session, ""));
        foreign.add(send(stranger, get(base + session + "/artifacts/" + artifact)));
        foreign.add(send(owner, get(base + session + "/artifacts/" + absent)));
        foreign.add(cancel(stranger, deck, session, UUID.randomUUID()));
        foreign.add(send(stranger, get("/decks/" + deck + "/generation-sessions")));
        // an invalid body on a foreign deck is a 404, not a 400: ownership is decided first
        foreign.add(send(stranger, post("/decks/" + deck + "/generation-sessions").contentType("application/json").content("{nope")));
        foreign.add(create(stranger, deck, spec("20 глаголов движения"), UUID.randomUUID()));
        for (MockHttpServletResponse response : foreign) problem(response, 404, "RESOURCE_NOT_FOUND");
        // the answer says nothing about which of the ids exists
        assertThat(problemBody(foreign.get(0)).propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code");
        assertThat(problemBody(foreign.get(0)).path("detail")).isEqualTo(problemBody(foreign.get(2)).path("detail"));
    }

    // ------------------------------------------------------------ create: errors

    @Test
    void aMalformedOrUnsupportedSpecIsRefusedWithTheCodeOfTheContract() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        String path = "/decks/" + deck + "/generation-sessions";
        UUID command = UUID.randomUUID();

        problem(send(owner, post(path).contentType("application/json").content("{nope")), 400, "INVALID_REQUEST");
        problem(send(owner, post(path).contentType("application/json").content("{\"commandId\":\"" + command
                + "\",\"commandId\":\"" + command + "\",\"spec\":{}}")), 400, "INVALID_REQUEST");
        problem(send(owner, post(path).contentType("application/json").content("{\"commandId\":\"" + command + "\"}")), 400, "INVALID_REQUEST");
        problem(send(owner, post(path).contentType("application/json").content("{\"commandId\":\"not-a-uuid\",\"spec\":{}}")), 400, "INVALID_REQUEST");
        problem(create(owner, deck, body(command, spec("p")).put("extra", true)), 400, "INVALID_REQUEST");
        // a version-1 UUID is not a command id
        problem(create(owner, deck, body(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), spec("p"))), 400, "INVALID_REQUEST");
        problem(create(owner, deck, spec("x".repeat(2_001)), command), 400, "INVALID_REQUEST");
        problem(create(owner, deck, spec(null), command), 400, "INVALID_REQUEST");
        ObjectNode unknownField = spec("p");
        unknownField.putObject("settings").put("effort", "MEDIUM").put("surprise", 1);
        problem(create(owner, deck, unknownField, command), 400, "INVALID_REQUEST");
        ObjectNode unknownKind = spec("p");
        unknownKind.put("kind", "SOMETHING");
        problem(create(owner, deck, unknownKind, command), 400, "INVALID_REQUEST");
        byte[] huge = ("{\"commandId\":\"" + command + "\",\"spec\":{\"kind\":\"MATERIALS\",\"prompt\":\"" + "y".repeat(70_000) + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        problem(send(owner, post(path).contentType("application/json").content(huge)), 400, "INVALID_REQUEST");

        // the revise specs are supported (AI-16): they need their exact shape, an owned target and an instruction or a media action
        for (String kind : List.of("REVISE_ITEM", "REVISE_EXERCISE")) {
            problem(create(owner, deck, JSON.createObjectNode().put("kind", kind), UUID.randomUUID()), 400, "INVALID_REQUEST");
        }
        ObjectNode noInstruction = JSON.createObjectNode().put("kind", "REVISE_EXERCISE");
        noInstruction.putObject("target").put("exerciseId", UUID.randomUUID().toString()).put("exerciseRevisionId", UUID.randomUUID().toString());
        problem(create(owner, deck, noInstruction, UUID.randomUUID()), 400, "INVALID_REQUEST");
        ObjectNode unknownMember = JSON.createObjectNode().put("kind", "REVISE_ITEM").put("instruction", "проще");
        unknownMember.putObject("target").put("memberKey", UUID.randomUUID().toString()).put("itemRevisionId", UUID.randomUUID().toString());
        problem(create(owner, deck, unknownMember, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");

        ObjectNode exercises = JSON.createObjectNode().put("kind", "EXERCISES");
        exercises.putArray("targets").addObject().put("memberKey", UUID.randomUUID().toString()).put("itemRevisionId", UUID.randomUUID().toString());
        // ownership of the target is checked first: a random material is the opaque 404
        problem(create(owner, deck, exercises, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        var material = fixtures.addMaterial(owner, deck, "память", "забывание");
        ObjectNode ownExercises = JSON.createObjectNode().put("kind", "EXERCISES");
        ownExercises.putArray("targets").addObject().put("memberKey", material.member().toString())
                .put("itemRevisionId", material.itemRevision().toString());
        // an exercises spec is supported (AI-13); only a planned one waits for the planner
        ObjectNode plannedExercises = ownExercises.deepCopy();
        plannedExercises.putObject("settings").put("planFirst", true);
        MockHttpServletResponse plannedExercisesResponse = create(owner, deck, plannedExercises, UUID.randomUUID());
        problem(plannedExercisesResponse, 422, "SPEC_NOT_SUPPORTED");
        assertThat(problemBody(plannedExercisesResponse).path("kind").stringValue(null)).isEqualTo("EXERCISES");

        ObjectNode planFirst = spec("p");
        ((ObjectNode) planFirst.path("settings")).put("planFirst", true);
        MockHttpServletResponse planned = create(owner, deck, planFirst, UUID.randomUUID());
        problem(planned, 422, "SPEC_NOT_SUPPORTED");
        assertThat(problemBody(planned).path("kind").stringValue(null)).isEqualTo("MATERIALS");

        ObjectNode tooMany = spec("p");
        var sources = tooMany.putArray("sources");
        for (int i = 0; i < 21; i++) sources.add(noteSource(UUID.randomUUID(), 0));
        MockHttpServletResponse limit = create(owner, deck, tooMany, UUID.randomUUID());
        problem(limit, 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(problemBody(limit).path("limit").stringValue(null)).isEqualTo("SOURCES");
        assertThat(problemBody(limit).path("limits").path("maxSources").intValue()).isEqualTo(20);

        // nothing was created by any refusal
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_session WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void sourcesAreCheckedForOwnershipAndFreshnessAndCapabilitiesForAvailability() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID otherDeck = deck(owner);
        UUID mine = note(owner, deck, "моя заметка");
        UUID foreign = note(stranger, deck(stranger), "чужая заметка");
        UUID elsewhere = note(owner, otherDeck, "заметка другой колоды");

        problem(create(owner, deck, spec(null, noteSource(UUID.randomUUID(), 0)), UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(create(owner, deck, spec(null, noteSource(foreign, 0)), UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(create(owner, deck, spec(null, noteSource(elsewhere, 0)), UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");

        // an owned note whose version moved is SOURCE_UNAVAILABLE, with the client's own ids and nothing else
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,updated_at=updated_at WHERE note_id=:id").param("id", mine).update();
        MockHttpServletResponse stale = create(owner, deck, spec(null, noteSource(mine, 0)), UUID.randomUUID());
        problem(stale, 409, "SOURCE_UNAVAILABLE");
        assertThat(problemBody(stale).path("sources")).hasSize(1);
        assertThat(problemBody(stale).path("sources").get(0).path("type").stringValue(null)).isEqualTo("NOTE");
        assertThat(problemBody(stale).path("sources").get(0).path("id").stringValue(null)).isEqualTo(mine.toString());
        assertThat(stale.getContentAsString()).doesNotContain("моя заметка");
        MockHttpServletResponse current = create(owner, deck, spec(null, noteSource(mine, 1)), UUID.randomUUID());
        assertThat(current.getStatus()).isEqualTo(201);

        // a capability the spec needs and that is off: image search has no adapter and its flag is off
        ObjectNode images = spec("с картинками");
        ((ObjectNode) images.path("settings")).putObject("media").put("imageSearch", true);
        MockHttpServletResponse unavailable = create(owner, deck, images, UUID.randomUUID());
        problem(unavailable, 409, "CAPABILITY_UNAVAILABLE");
        assertThat(problemBody(unavailable).path("capability").stringValue(null)).isEqualTo("imageSearch");
        assertThat(problemBody(unavailable).path("reason").stringValue(null)).isEqualTo("DISABLED");
        ObjectNode research = spec("с проверкой");
        ((ObjectNode) research.path("settings")).put("factCheck", true);
        MockHttpServletResponse noSearch = create(owner, deck, research, UUID.randomUUID());
        problem(noSearch, 409, "CAPABILITY_UNAVAILABLE");
        assertThat(problemBody(noSearch).path("capability").stringValue(null)).isEqualTo("webSearch");
    }

    // -------------------------------------------------------- replay and admission

    @Test
    void anExactRetryReplaysTheStoredAnswerBeforeAnythingElseIsEvaluatedAndAChangedReuseConflicts() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID command = UUID.randomUUID();
        ObjectNode spec = spec("20 глаголов движения");

        MockHttpServletResponse first = create(owner, deck, spec, command);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getHeader("Idempotency-Replayed")).isNull();
        MockHttpServletResponse replay = create(owner, deck, spec, command);
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getHeader("ETag")).isNull();
        assertThat(replay.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        assertThat(json(replay)).isEqualTo(json(first));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_session WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isEqualTo(1);

        ObjectNode changed = spec("совсем другой запрос");
        problem(create(owner, deck, changed, command), 409, "IDEMPOTENCY_CONFLICT");

        // two more sessions fill the limit of three active ones; the replay of the first is still its stored 201 ...
        start(owner, deck, spec("второй"));
        start(owner, deck, spec("третий"));
        MockHttpServletResponse stillReplay = create(owner, deck, spec, command);
        assertThat(stillReplay.getStatus()).isEqualTo(201);
        assertThat(stillReplay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        // ... while a new command is refused with the ids of the sessions in the way
        MockHttpServletResponse fourth = create(owner, deck, spec("четвёртый"), UUID.randomUUID());
        problem(fourth, 422, "RESOURCE_LIMIT_EXCEEDED");
        JsonNode problem = problemBody(fourth);
        assertThat(problem.path("limit").stringValue(null)).isEqualTo("ACTIVE_SESSIONS");
        assertThat(problem.path("limits").path("maxActiveSessions").intValue()).isEqualTo(3);
        assertThat(problem.path("activeSessionIds")).hasSize(3);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_session WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void parallelCreatesCannotExceedTheActiveSessionLimit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        int attempts = 6;
        var results = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var gate = new java.util.concurrent.CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            ObjectNode request = body(UUID.randomUUID(), spec("запрос " + i));
            Thread thread = Thread.ofVirtual().unstarted(() -> {
                try {
                    gate.await();
                    results.add(sendIsolated(owner, deck, request));
                } catch (Exception failure) {
                    results.add(-1);
                }
            });
            threads.add(thread);
            thread.start();
        }
        gate.countDown();
        for (Thread thread : threads) thread.join();

        assertThat(results.stream().filter(status -> status == 201).count()).isEqualTo(3);
        assertThat(results.stream().filter(status -> status == 422).count()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_session WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isEqualTo(3);
    }

    private int sendIsolated(UUID owner, UUID deck, ObjectNode request) throws Exception {
        // MockMvc reads the security context of the calling thread, so each thread builds its own
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                .header("alg", "RS256").subject(owner.toString()).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
        try {
            return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new app.mnema.learning.platform.api.ApiExceptionHandler())
                    .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                    .build().perform(post("/decks/" + deck + "/generation-sessions").contentType("application/json")
                            .content(request.toString())).andReturn().getResponse().getStatus();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void aUsageLimitIsRefusedLastAndLeavesNothingBehind() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // PLUS has 360 credits: take all but five, so the ten of a medium material do not fit
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, 355));
        MockHttpServletResponse refused = create(owner, deck, spec("20 глаголов движения"), UUID.randomUUID());

        problem(refused, 409, "USAGE_LIMIT_REACHED");
        JsonNode problem = problemBody(refused);
        assertThat(problem.path("bucket").stringValue(null)).isEqualTo("CREDITS");
        assertThat(problem.path("required").intValue()).isEqualTo(10);
        assertThat(problem.path("plan").stringValue(null)).isEqualTo("PLUS");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_session WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_step WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT reserved FROM app_learning.usage_balance WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isEqualTo(355);
    }

    @Test
    void theEvaluationOrderPutsSourcesBeforeCapabilitiesAndTheActiveSessionLimitBeforeUsage() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // a stale pin and a capability that is off: the pin is reported first
        UUID note = note(owner, deck, "заметка");
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,updated_at=updated_at WHERE note_id=:id").param("id", note).update();
        ObjectNode both = spec(null, noteSource(note, 0));
        ((ObjectNode) both.path("settings")).putObject("media").put("imageSearch", true);
        problem(create(owner, deck, both, UUID.randomUUID()), 409, "SOURCE_UNAVAILABLE");
        // ... while the estimate does not know about stale pins
        assertThat(estimate(owner, "/decks/" + deck + "/generation-estimates", "{\"spec\":" + both + "}").getStatus()).isEqualTo(409);

        // three active sessions and an account that cannot pay: 422 (limit) before 409 (usage)
        for (int i = 0; i < 3; i++) start(owner, deck, spec("сессия " + i));
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, 325));
        problem(create(owner, deck, spec("четвёртая"), UUID.randomUUID()), 422, "RESOURCE_LIMIT_EXCEEDED");
    }

    @Test
    void aBudgetShareThatCannotPayForOneMaterialIsRefusedAtAdmission() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        ObjectNode small = spec("20 глаголов движения");
        ((ObjectNode) small.path("settings")).put("budgetPercent", 1);
        MockHttpServletResponse refused = create(owner, deck, small, UUID.randomUUID());
        problem(refused, 409, "USAGE_LIMIT_REACHED");
        assertThat(problemBody(refused).path("bucket").stringValue(null)).isEqualTo("CREDITS");
        assertThat(problemBody(refused).path("required").intValue()).isEqualTo(10);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_session WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isZero();
        // a share that buys at least one material is admitted
        ObjectNode enough = spec("20 глаголов движения");
        ((ObjectNode) enough.path("settings")).put("budgetPercent", 10);
        assertThat(create(owner, deck, enough, UUID.randomUUID()).getStatus()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ listings

    @Test
    void sessionsAreListedNewestFirstPerDeckAndAcrossDecksWithOpaqueCursorsAndStrictQueries() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deckA = deck(owner);
        UUID deckB = deck(owner);
        UUID first = start(owner, deckA, spec("первый"));
        UUID second = start(owner, deckA, spec("второй"));
        UUID other = start(owner, deckB, spec("третий"));
        awaitState(first, "REVIEW");
        awaitState(second, "REVIEW");
        awaitState(other, "REVIEW");

        MockHttpServletResponse byDeck = send(owner, get("/decks/" + deckA + "/generation-sessions"));
        assertThat(byDeck.getStatus()).isEqualTo(200);
        assertThat(byDeck.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode items = json(byDeck).path("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("sessionId").stringValue(null)).isIn(first.toString(), second.toString());
        assertThat(json(byDeck).path("nextCursor").isNull()).isTrue();
        JsonNode summary = items.get(0);
        assertThat(summary.propertyNames()).containsExactlyInAnyOrder("sessionId", "deckId", "kind", "state", "rowVersion", "endReason",
                "createdAt", "lastActivityAt", "expiresAt", "artifactCounts", "approvableCount", "usage");
        assertThat(summary.path("artifactCounts").path("PROPOSED").intValue()).isEqualTo(1);

        // newest first by activity, paged with an opaque cursor
        JsonNode pageOne = json(send(owner, get("/decks/" + deckA + "/generation-sessions?limit=1")));
        assertThat(pageOne.path("items")).hasSize(1);
        String cursor = pageOne.path("nextCursor").stringValue(null);
        assertThat(cursor).isNotBlank();
        JsonNode pageTwo = json(send(owner, get("/decks/" + deckA + "/generation-sessions?limit=1&cursor=" + cursor)));
        assertThat(pageTwo.path("items")).hasSize(1);
        assertThat(pageTwo.path("nextCursor").isNull()).isTrue();
        assertThat(pageTwo.path("items").get(0).path("sessionId")).isNotEqualTo(pageOne.path("items").get(0).path("sessionId"));
        assertThat(json(send(owner, get("/decks/" + deckA + "/generation-sessions?active=true"))).path("items")).hasSize(2);

        // the account-wide list carries the deck and covers every active session of the owner and nobody else's
        JsonNode account = json(send(owner, get("/generation-sessions?state=active")));
        assertThat(account.path("items")).hasSize(3);
        List<String> decksOfItems = new ArrayList<>();
        account.path("items").forEach(item -> decksOfItems.add(item.path("deckId").stringValue(null)));
        assertThat(decksOfItems).contains(deckA.toString(), deckB.toString());
        assertThat(json(send(UUID.randomUUID(), get("/generation-sessions?state=active"))).path("items")).isEmpty();

        problem(send(owner, get("/generation-sessions")), 400, "INVALID_REQUEST");
        problem(send(owner, get("/generation-sessions?state=closed")), 400, "INVALID_REQUEST");
        problem(send(owner, get("/generation-sessions?state=active&limit=0")), 400, "INVALID_REQUEST");
        problem(send(owner, get("/generation-sessions?state=active&limit=101")), 400, "INVALID_REQUEST");
        problem(send(owner, get("/generation-sessions?state=active&cursor=not-a-cursor")), 400, "INVALID_REQUEST");
        problem(send(owner, get("/generation-sessions?state=active&surprise=1")), 400, "INVALID_REQUEST");
        problem(send(owner, get("/decks/" + deckA + "/generation-sessions?active=maybe")), 400, "INVALID_REQUEST");
        problem(send(owner, get("/decks/nope/generation-sessions")), 400, "INVALID_REQUEST");

        // a cancelled session leaves the active list
        assertThat(cancel(owner, deckA, first, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(json(send(owner, get("/generation-sessions?state=active"))).path("items")).hasSize(2);
        assertThat(json(send(owner, get("/decks/" + deckA + "/generation-sessions"))).path("items")).hasSize(2);
    }

    @Test
    void readsValidateTheirQueriesAndAnArtifactOfAnotherSessionIsNotFound() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("первый"));
        UUID other = start(owner, deck, spec("второй"));
        awaitState(session, "REVIEW");
        awaitState(other, "REVIEW");
        JsonNode detail = json(getSession(owner, deck, session));
        UUID artifact = UUID.fromString(detail.path("artifacts").get(0).path("artifactId").stringValue(null));
        String base = "/decks/" + deck + "/generation-sessions/";

        problem(events(owner, deck, session, "?after=abc"), 400, "INVALID_REQUEST");
        problem(events(owner, deck, session, "?after=-1"), 400, "INVALID_REQUEST");
        problem(events(owner, deck, session, "?limit=0"), 400, "INVALID_REQUEST");
        problem(events(owner, deck, session, "?limit=101"), 400, "INVALID_REQUEST");
        problem(events(owner, deck, session, "?surprise=1"), 400, "INVALID_REQUEST");
        problem(send(owner, get(base + session + "?x=1")), 400, "INVALID_REQUEST");
        problem(send(owner, get(base + "not-a-uuid")), 400, "INVALID_REQUEST");
        problem(send(owner, get(base + session + "/artifacts/" + artifact + "?revisionId=nope")), 400, "INVALID_REQUEST");
        problem(send(owner, get(base + session + "/artifacts/" + artifact + "?revisionId=" + UUID.randomUUID())), 404, "RESOURCE_NOT_FOUND");
        // the artifact belongs to its own session only
        problem(send(owner, get(base + other + "/artifacts/" + artifact)), 404, "RESOURCE_NOT_FOUND");
        // a cursor beyond the log is an empty page that keeps the cursor
        JsonNode beyond = json(events(owner, deck, session, "?after=99999"));
        assertThat(beyond.path("events")).isEmpty();
        assertThat(beyond.path("cursor").stringValue(null)).isEqualTo("99999");
    }

    @Test
    void cancellingAClosedSessionIsAStateConflictAndABadBodyIsRefusedBeforeAnyChange() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[stub:refusal]] запрещённое"));
        awaitState(session, "CLOSED");
        String path = "/decks/" + deck + "/generation-sessions/" + session + "/cancellation";

        MockHttpServletResponse conflict = cancel(owner, deck, session, UUID.randomUUID());
        problem(conflict, 409, "GENERATION_STATE_CONFLICT");
        assertThat(problemBody(conflict).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
        problem(send(owner, post(path).contentType("application/json").content("{}")), 400, "INVALID_REQUEST");
        problem(send(owner, post(path).contentType("application/json").content("{\"commandId\":\"x\"}")), 400, "INVALID_REQUEST");
        problem(send(owner, post(path).contentType("application/json").content("{\"commandId\":\"" + UUID.randomUUID() + "\",\"x\":1}")),
                400, "INVALID_REQUEST");
        assertThat(sessionState(session)).isEqualTo("CLOSED");
        // a session never answers to the same commandId used for another session
        UUID command = UUID.randomUUID();
        UUID live = start(owner, deck, spec("[[fake:block]] долго"));
        assertThat(cancel(owner, deck, live, command).getStatus()).isEqualTo(200);
        problem(cancel(owner, deck, session, command), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void theSessionsOfADeletedDeckAreAsAbsentAsTheDeckAndDoNotCountAgainstTheLimit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        start(owner, deck, spec("второй"));
        start(owner, deck, spec("третий"));
        awaitState(session, "REVIEW");
        UUID artifact = UUID.fromString(json(getSession(owner, deck, session)).path("artifacts").get(0).path("artifactId").stringValue(null));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_session WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).single()).isEqualTo(3);

        decks.delete(owner, deck, Long.parseLong(decks.read(owner, deck).path("rowVersion").stringValue(null)));

        problem(getSession(owner, deck, session), 404, "RESOURCE_NOT_FOUND");
        problem(events(owner, deck, session, ""), 404, "RESOURCE_NOT_FOUND");
        problem(cancel(owner, deck, session, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(send(owner, get("/decks/" + deck + "/generation-sessions")), 404, "RESOURCE_NOT_FOUND");
        problem(send(owner, get("/decks/" + deck + "/generation-sessions/" + session + "/artifacts/" + artifact)), 404, "RESOURCE_NOT_FOUND");
        assertThat(json(send(owner, get("/generation-sessions?state=active"))).path("items")).isEmpty();
        // the sessions of a deleted deck no longer hold the owner's three places
        UUID fresh = deck(owner);
        assertThat(create(owner, fresh, spec("новая колода"), UUID.randomUUID()).getStatus()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ estimate

    @Test
    void theEstimateUsesTheSameValidationAndNeverRevealsAForeignSourceOrArtifact() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID mine = note(owner, deck, "моя заметка");
        UUID foreign = note(stranger, deck(stranger), "чужая");
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        awaitState(session, "REVIEW");
        UUID artifact = UUID.fromString(json(getSession(owner, deck, session)).path("artifacts").get(0).path("artifactId").stringValue(null));
        String path = "/decks/" + deck + "/generation-estimates";

        MockHttpServletResponse ok = estimate(owner, path, "{\"spec\":" + spec(null, noteSource(mine, 0)) + "}");
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(json(ok).path("credits").path("p95").intValue()).isEqualTo(10);
        problem(estimate(owner, path, "{\"spec\":" + spec(null, noteSource(foreign, 0)) + "}"), 404, "RESOURCE_NOT_FOUND");
        problem(estimate(owner, path, "{\"spec\":" + spec(null, noteSource(UUID.randomUUID(), 0)) + "}"), 404, "RESOURCE_NOT_FOUND");
        ObjectNode planFirst = spec("p");
        ((ObjectNode) planFirst.path("settings")).put("planFirst", true);
        problem(estimate(owner, path, "{\"spec\":" + planFirst + "}"), 422, "SPEC_NOT_SUPPORTED");
        ObjectNode images = spec("p");
        ((ObjectNode) images.path("settings")).putObject("media").put("imageSearch", true);
        problem(estimate(owner, path, "{\"spec\":" + images + "}"), 409, "CAPABILITY_UNAVAILABLE");

        String edit = "{\"edit\":{\"sessionId\":\"" + session + "\",\"artifactId\":\"" + artifact + "\",\"action\":\"REWRITE\"}}";
        assertThat(estimate(owner, path, edit).getStatus()).isEqualTo(200);
        // another owner, another deck, an unknown artifact: one opaque 404
        problem(estimate(stranger, path, edit), 404, "RESOURCE_NOT_FOUND");
        problem(estimate(owner, "/decks/" + deck(owner) + "/generation-estimates", edit), 404, "RESOURCE_NOT_FOUND");
        problem(estimate(owner, path, "{\"edit\":{\"sessionId\":\"" + session + "\",\"artifactId\":\"" + UUID.randomUUID()
                + "\",\"action\":\"REWRITE\"}}"), 404, "RESOURCE_NOT_FOUND");
    }

    private MockHttpServletResponse estimate(UUID owner, String path, String body) throws Exception {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("test")
                .header("alg", "RS256").subject(owner.toString()).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(estimates)
                .setControllerAdvice(new app.mnema.learning.platform.api.ApiExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
                .build().perform(post(path).contentType("application/json").content(body)).andReturn().getResponse();
    }
}
