package app.mnema.learning.onboarding;

import app.mnema.learning.fork.ForkWorlds;
import app.mnema.learning.fork.ForkWorlds.Source;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inventory of the sharing paths (OB-201): a deck whose purpose is {@code SAMPLE} or {@code SAMPLE_TEMPLATE} is never published, shared or copied by a user. The test lists
 * EVERY mutating route of the {@code library} and {@code fork} packages from the live handler mapping and requires each one to be classified here, as a command that must refuse
 * the sample deck and the template ({@code SAMPLE_DECK_NOT_SHAREABLE}) or as one that does not widen who can read or take a deck. A route added later and not classified fails the test,
 * so a new sharing path cannot silently skip {@code DeckPurposeGuard}. Each refused command is then executed against both kinds of deck.
 */
class SharingInventoryIntegrationTest extends SampleDeckIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired private app.mnema.learning.library.DeckModeration moderation;

    /** The commands that widen who can read or take a deck: they must be refused for a sample deck and for the template. */
    private static final Set<String> WIDENING = Set.of(
            "PUT /decks/{deckId}/publication",
            "POST /decks/{deckId}/invites",
            "POST /decks/{deckId}/grants",
            "POST /decks/{deckId}/access-requests/{requesterId}:approve",
            "POST /public/decks/{code}/access-requests",
            "POST /invites:redeem",
            "POST /decks/forks");

    /** The commands that only take access away, manage the caller's own copy or report: nothing a sample deck must be protected from. */
    private static final Set<String> NOT_WIDENING = Set.of(
            "POST /decks/{deckId}/invites/{inviteId}:revoke",
            "POST /decks/{deckId}/access-requests/{requesterId}:reject",
            "DELETE /decks/{deckId}/grants/{granteeId}",
            "POST /public/decks/{code}/access-requests:withdraw",
            "POST /decks/{deckId}/fork:retry",
            "POST /decks/{deckId}/fork:detach",
            "PUT /decks/{deckId}/fork:auto-update");

    private static final String NO_LIMITS = "{\"maxUses\":null,\"expiresInDays\":null}";
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    // ---- the inventory ----

    @Test
    void everyMutatingRouteOfTheLibraryAndForkPackagesIsClassified() {
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Set<String> routes = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            String type = entry.getValue().getBeanType().getName();
            if (!type.startsWith("app.mnema.learning.library.") && !type.startsWith("app.mnema.learning.fork.")) continue;
            Set<String> patterns = entry.getKey().getPathPatternsCondition().getPatternValues();
            for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
                if (MUTATING.contains(method.name())) patterns.forEach(pattern -> routes.add(method.name() + " " + pattern));
            }
        }
        Set<String> classified = new TreeSet<>(WIDENING);
        classified.addAll(NOT_WIDENING);
        assertThat(routes).as("a mutating route that is not classified in SharingInventoryIntegrationTest: decide whether it widens who can read or take a deck, and if so "
                + "guard it with DeckPurposeGuard and list it in WIDENING").isEqualTo(classified);
    }

    // ---- the widening commands against both kinds of deck ----

    private record Decks(UUID editor, UUID template, String code, UUID learner, UUID sample) { }

    private Decks decks() {
        Source source = template(12, 2);
        UUID learner = UUID.randomUUID();
        UUID sample = sample(learner);
        worlds.awaitReady(learner, sample);
        return new Decks(source.author(), source.deck(), source.code(), learner, sample);
    }

    private HttpResponse<String> publication(UUID owner, UUID deck, String level, boolean publish) {
        HttpResponse<String> state = worlds.get("/decks/" + deck + "/publication", owner);
        assertThat(state.statusCode()).as(state.body()).isEqualTo(200);
        ObjectNode body = ForkWorlds.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", level);
        body.putObject("metadata").put("topicId", "japanese").put("contentLanguage", "ru").put("targetLanguage", "ja").put("level", "A2").putArray("tags").add("jlpt n5");
        body.put("requestsEnabled", true);
        if (publish) {
            body.putObject("publish").put("expectedHeadRevisionId", decksHead(owner, deck)).putNull("releaseNote");
        } else {
            body.putNull("publish");
        }
        return worlds.send("PUT", "/decks/" + deck + "/publication", owner, body.toString(), state.headers().firstValue("etag").orElseThrow());
    }

    private String decksHead(UUID owner, UUID deck) {
        return decks.read(owner, deck).path("revisionId").stringValue(null);
    }

    private static JsonNode body(String json) {
        return ForkWorlds.JSON.readTree(json);
    }

    @Test
    void publicationAndEveryLevelChangeAreRefusedForASampleDeck() {
        Decks world = decks();
        for (String level : new String[] {"PRIVATE", "INVITE", "LINK", "PUBLIC"}) {
            for (boolean publish : new boolean[] {false, true}) {
                if (publish && level.equals("PRIVATE")) continue; // the command itself is invalid: a private deck is not published
                problem(publication(world.learner(), world.sample(), level, publish), 409, "SAMPLE_DECK_NOT_SHAREABLE");
            }
        }
        assertThat(count("SELECT count(*) FROM app_learning.deck_publication WHERE deck_id = :p0", world.sample())).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.deck_publication_event WHERE deck_id = :p0", world.sample())).isZero();
        // the problem is the stable schema of the fixture
        HttpResponse<String> refused = publication(world.learner(), world.sample(), "PUBLIC", true);
        JsonNode problem = json(refused);
        assertThat(problem.path("type").stringValue(null)).isEqualTo("urn:mnema:problem:sample-deck-not-shareable");
        assertThat(problem.path("title").stringValue(null)).isEqualTo("Sample deck not shareable");
        assertThat(problem.path("detail").stringValue(null)).isEqualTo("A sample deck cannot be published, shared or copied.");
        assertThat(problem.path("instance").stringValue(null)).isEqualTo("/api/decks/" + world.sample() + "/publication");
        // a stranger's deck id is still the opaque 404, whatever the deck is for
        ObjectNode stranger = ForkWorlds.JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("visibility", "LINK").put("requestsEnabled", true);
        stranger.putObject("metadata").put("topicId", "japanese").put("contentLanguage", "ru").put("targetLanguage", "ja").put("level", "A2").putArray("tags").add("jlpt n5");
        stranger.putNull("publish");
        assertThat(worlds.send("PUT", "/decks/" + world.template() + "/publication", world.learner(), stranger.toString(), "\"0\"").statusCode()).as("not the owner")
                .isEqualTo(404);
    }

    @Test
    void theTemplateOwnerPublishesNewRevisionsAtLinkAndNothingElse() {
        Decks world = decks();
        long events = count("SELECT count(*) FROM app_learning.deck_publication_event WHERE deck_id = :p0", world.template());
        long jobs = count("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key LIKE :p0", world.template() + ":%");
        // a new revision of the content: allowed (that is how an update reaches the copies)
        worlds.study.addMaterial(world.editor(), world.template(), "ещё", "один");
        HttpResponse<String> publish = publication(world.editor(), world.template(), "LINK", true);
        assertThat(publish.statusCode()).as(publish.body()).isEqualTo(200);
        // the same level without a new revision: allowed too (metadata and note edits of the template)
        assertThat(publication(world.editor(), world.template(), "LINK", false).statusCode()).isEqualTo(200);
        // any other level, the private one included, is not
        for (String level : new String[] {"PRIVATE", "INVITE", "PUBLIC"}) {
            if (!level.equals("PRIVATE")) problem(publication(world.editor(), world.template(), level, true), 409, "SAMPLE_DECK_NOT_SHAREABLE");
            problem(publication(world.editor(), world.template(), level, false), 409, "SAMPLE_DECK_NOT_SHAREABLE");
        }
        assertThat(worlds.get("/decks/" + world.template() + "/publication", world.editor()).statusCode()).isEqualTo(200);
        assertThat(json(worlds.get("/decks/" + world.template() + "/publication", world.editor())).path("visibility").stringValue(null)).isEqualTo("LINK");
        // the catalogue is never told about the template: no outbox row, no projection job, no catalogue row
        assertThat(count("SELECT count(*) FROM app_learning.deck_publication_event WHERE deck_id = :p0", world.template())).isEqualTo(events);
        assertThat(count("SELECT count(*) FROM app_learning.learning_job WHERE queue = 'catalog.project' AND dedupe_key LIKE :p0", world.template() + ":%")).isEqualTo(jobs);
        assertThat(count("SELECT count(*) FROM app_learning.public_deck WHERE deck_id = :p0", world.template())).isZero();
    }

    @Test
    void invitationsGrantsAndAccessRequestsAreRefusedForBothKinds() {
        Decks world = decks();
        UUID requester = UUID.randomUUID();
        for (Map.Entry<UUID, UUID> deck : List.of(Map.entry(world.learner(), world.sample()), Map.entry(world.editor(), world.template()))) {
            UUID owner = deck.getKey();
            String path = "/decks/" + deck.getValue();
            problem(worlds.send("POST", path + "/invites", owner, NO_LIMITS, null), 409, "SAMPLE_DECK_NOT_SHAREABLE");
            problem(worlds.send("POST", path + "/invites", owner, "{\"maxUses\":1,\"expiresInDays\":7}", null), 409, "SAMPLE_DECK_NOT_SHAREABLE");
            problem(worlds.send("POST", path + "/grants", owner, "{\"username\":\"somebody\"}", null), 409, "SAMPLE_DECK_NOT_SHAREABLE");
            problem(worlds.send("POST", path + "/access-requests/" + requester + ":approve", owner, null, null), 409, "SAMPLE_DECK_NOT_SHAREABLE");
            // a stranger does not learn what the deck is for: not the owner, the opaque 404
            UUID stranger = UUID.randomUUID();
            assertThat(worlds.send("POST", path + "/invites", stranger, NO_LIMITS, null).statusCode()).isEqualTo(404);
            assertThat(worlds.send("POST", path + "/grants", stranger, "{\"username\":\"somebody\"}", null).statusCode()).isEqualTo(404);
        }
        assertThat(count("SELECT count(*) FROM app_learning.deck_invite WHERE deck_id IN (:p0, :p1)", world.sample(), world.template())).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id IN (:p0, :p1)", world.sample(), world.template())).isZero();
        // the code of the template is not a door either: asking for access to it is refused for what it is
        problem(worlds.send("POST", "/public/decks/" + world.code() + "/access-requests", requester, null, null), 409, "SAMPLE_DECK_NOT_SHAREABLE");
        assertThat(count("SELECT count(*) FROM app_learning.deck_access_request WHERE deck_id IN (:p0, :p1)", world.sample(), world.template())).isZero();
        // and a code that opens nothing is still the 404 of an unknown deck
        assertThat(worlds.send("POST", "/public/decks/ZZZZZZZZZZ/access-requests", requester, null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void theTemplateCanNotBeCopiedByAnotherAccountAndASampleDeckHasNoCodeToCopyBy() {
        Decks world = decks();
        UUID stranger = UUID.randomUUID();
        HttpResponse<String> copy = worlds.fork(stranger, world.code());
        problem(copy, 409, "SAMPLE_DECK_NOT_SHAREABLE");
        assertThat(count("SELECT count(*) FROM app_learning.deck WHERE owner_id = :p0", stranger)).isZero();
        assertThat(count("SELECT count(*) FROM app_learning.deck_fork WHERE upstream_deck_id = :p0 AND owner_id <> :p1", world.template(), world.learner())).isZero();
        // the template's owner copying his own deck by its code was, and stays, FORK_OWN_DECK
        problem(worlds.fork(world.editor(), world.code()), 409, "FORK_OWN_DECK");
        // a sample copy never has a publication row, so there is no code that opens it
        assertThat(count("SELECT count(*) FROM app_learning.deck_publication WHERE deck_id = :p0", world.sample())).isZero();
        // the copy's owner may still read the template's code from his deck (the update path needs it) but it copies nothing for him either
        problem(worlds.fork(world.learner(), world.code()), 409, "SAMPLE_DECK_NOT_SHAREABLE");
        assertThat(liveSamples(world.learner())).isOne();
    }

    @Test
    void anInvitationOfADeckThatBecameTheTemplateOpensNothing() {
        // an ordinary deck shared by invitation, whose owner then becomes the editorial account: the invitation link it handed out must not open it
        Source invited = worlds.source("LINK", 12, 1);
        ObjectNode created = (ObjectNode) json(worlds.send("POST", "/decks/" + invited.deck() + "/invites", invited.author(), NO_LIMITS, null));
        String token = created.path("token").stringValue(null);
        UUID guest = UUID.randomUUID();
        String redeem = ForkWorlds.JSON.createObjectNode().put("token", token).toString();
        SampleDeckTestConfiguration.template(context, invited.deck());
        assertThat(purpose(invited.deck())).isEqualTo("SAMPLE_TEMPLATE");
        HttpResponse<String> refused = worlds.send("POST", "/invites:redeem", guest, redeem, null);
        assertThat(refused.statusCode()).as("the answer of every token that opens nothing").isEqualTo(404);
        assertThat(count("SELECT count(*) FROM app_learning.deck_access_grant WHERE deck_id = :p0", invited.deck())).isZero();
    }

    @Test
    void aTemplateHiddenByModerationAnswersTheOpaque404AndNotTheSharingRefusal() {
        Decks world = decks();
        UUID requester = UUID.randomUUID();
        String path = "/public/decks/" + world.code() + "/access-requests";
        problem(worlds.send("POST", path, requester, null, null), 409, "SAMPLE_DECK_NOT_SHAREABLE");
        Boolean hidden404 = new org.springframework.transaction.support.TransactionTemplate(transactions)
                .execute(status -> moderation.hide(world.template(), java.time.Instant.now()));
        assertThat(hidden404).isTrue();
        // hidden: everybody but the owner gets the answer of an unknown code, whatever the deck is for
        HttpResponse<String> hidden = worlds.send("POST", path, requester, null, null);
        assertThat(hidden.statusCode()).as(hidden.body()).isEqualTo(404);
        assertThat(json(hidden).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(worlds.send("POST", "/public/decks/ZZZZZZZZZZ/access-requests", requester, null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void theCommandsThatOnlyTakeAccessAwayStayOpen() {
        Decks world = decks();
        UUID somebody = UUID.randomUUID();
        for (Map.Entry<UUID, UUID> deck : List.of(Map.entry(world.learner(), world.sample()), Map.entry(world.editor(), world.template()))) {
            String path = "/decks/" + deck.getValue();
            assertThat(worlds.send("DELETE", path + "/grants/" + somebody, deck.getKey(), null, null).statusCode()).as("revoke a grant").isEqualTo(204);
            assertThat(worlds.send("GET", path + "/grants", deck.getKey(), null, null).statusCode()).isEqualTo(200);
            assertThat(worlds.send("GET", path + "/invites", deck.getKey(), null, null).statusCode()).isEqualTo(200);
            assertThat(worlds.send("GET", path + "/publication", deck.getKey(), null, null).statusCode()).isEqualTo(200);
        }
        // the owner of a sample copy detaches, retries, deletes: it is his deck
        assertThat(worlds.send("POST", "/decks/" + world.sample() + "/fork:detach", world.learner(), null, null).statusCode()).isEqualTo(200);
        assertThat(new ArrayList<>(List.of(world.sample()))).hasSize(1);
        delete(world.learner(), world.sample());
        assertThat(liveSamples(world.learner())).isZero();
    }
}
