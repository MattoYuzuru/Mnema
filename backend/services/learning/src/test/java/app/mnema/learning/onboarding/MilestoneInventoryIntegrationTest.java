package app.mnema.learning.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static app.mnema.learning.onboarding.MilestoneKey.AI_EXPLANATION;
import static app.mnema.learning.onboarding.MilestoneKey.CAPTURE_NOTE;
import static app.mnema.learning.onboarding.MilestoneKey.COMMUNITY_COPY;
import static app.mnema.learning.onboarding.MilestoneKey.FIRST_DECK;
import static app.mnema.learning.onboarding.MilestoneKey.FIRST_SESSION;
import static app.mnema.learning.onboarding.MilestoneKey.GOAL_TOLD;
import static app.mnema.learning.onboarding.MilestoneKey.MEDIA_IN_MATERIAL;
import static app.mnema.learning.onboarding.MilestoneKey.OWN_EXERCISES;
import static app.mnema.learning.onboarding.MilestoneKey.OWN_MATERIAL;
import static app.mnema.learning.onboarding.MilestoneKey.RHYTHM_WEEK;
import static app.mnema.learning.onboarding.MilestoneKey.TWO_DAYS;
import static app.mnema.learning.onboarding.MilestoneKey.VOICE_ANSWER;
import static app.mnema.learning.onboarding.MilestoneKey.WORKSHOP_APPROVED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The net under a new writer of the path (OB-301). Every mutating route of the live handler mapping must be classified here by the milestones it can write, directly or in the
 * background work it queues (an empty set is an explicit decision: the route is not a fact of the path). A route added later and not classified fails this test, and the person who
 * adds it decides, in this file, whether it is a milestone; a milestone then needs a row in {@code contracts/onboarding/milestones.json}, a key in {@link MilestoneKey} and a call
 * of {@link MilestoneRecorder} in its writer, which {@link MilestoneWriterScanTest} checks against the fixture.
 */
class MilestoneInventoryIntegrationTest extends SampleDeckIntegrationTest {
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private static final Set<MilestoneKey> NONE = EnumSet.noneOf(MilestoneKey.class);

    private static Set<MilestoneKey> keys(MilestoneKey... keys) {
        return keys.length == 0 ? NONE : EnumSet.copyOf(Arrays.asList(keys));
    }

    /** A new material, a revision with a picture (every publication of an item). */
    private static final Set<MilestoneKey> ITEM = keys(OWN_MATERIAL, MEDIA_IN_MATERIAL);
    /** The completion of a study session by the last answer, or by the issuer that finds nothing left: the path's two session milestones and the week of the rhythm (OB-401). */
    private static final Set<MilestoneKey> SESSION = keys(FIRST_SESSION, TWO_DAYS, RHYTHM_WEEK);

    /** route to the milestones it can write; the controller of the route is named for the reader. */
    private static final Map<String, Set<MilestoneKey>> ROUTES = routes();

    private static Map<String, Set<MilestoneKey>> routes() {
        Map<String, Set<MilestoneKey>> routes = new LinkedHashMap<>();
        // ---- the path itself ----
        routes.put("PUT /learning-profile", keys(GOAL_TOLD));
        routes.put("POST /decks", keys(FIRST_DECK));
        routes.put("POST /decks/forks", keys(FIRST_DECK, COMMUNITY_COPY));
        routes.put("POST /onboarding/sample-deck", keys(FIRST_DECK));
        routes.put("PUT /onboarding/journey", NONE);
        // a weekly target lowered below the days the current week already has meets the week (WeeklyRhythm.targetChanged)
        routes.put("PUT /onboarding/preferences", keys(RHYTHM_WEEK));
        // ---- authoring ----
        routes.put("POST /decks/{deckId}/items", ITEM);
        routes.put("POST /decks/{deckId}/items/publications", ITEM);
        routes.put("PUT /decks/{deckId}/items/{memberKey}", keys(MEDIA_IN_MATERIAL));
        routes.put("POST /decks/{deckId}/items/deletions", NONE);
        routes.put("POST /decks/{deckId}/items/deletions/preview", NONE);
        routes.put("POST /decks/{deckId}/items/{memberKey}/exemplar", NONE);
        routes.put("POST /decks/{deckId}/exercises", keys(OWN_EXERCISES));
        routes.put("PUT /decks/{deckId}/exercises/{exerciseId}", NONE);
        routes.put("DELETE /decks/{deckId}/exercises/{exerciseId}", NONE);
        routes.put("DELETE /decks/{deckId}/exercises/{exerciseId}/new-mark", NONE);
        routes.put("POST /capture-notes", keys(CAPTURE_NOTE));
        routes.put("POST /capture-notes/{noteId}/archive", keys(CAPTURE_NOTE));
        routes.put("POST /capture-notes/{noteId}/conversions", keys(CAPTURE_NOTE, OWN_MATERIAL, MEDIA_IN_MATERIAL));
        routes.put("PUT /capture-notes/{noteId}", NONE);
        routes.put("DELETE /capture-notes/{noteId}", NONE);
        routes.put("POST /editing-drafts", NONE);
        routes.put("PUT /editing-drafts/{draftId}", NONE);
        routes.put("DELETE /editing-drafts/{draftId}", NONE);
        routes.put("PATCH /decks/{deckId}", NONE);
        routes.put("DELETE /decks/{deckId}", NONE);
        // ---- study: the completion of a session; the model's grade arrives after the answer was accepted ----
        routes.put("POST /decks/{deckId}/study-sessions", NONE);
        routes.put("POST /decks/{deckId}/study-sessions/{sessionId}/presentations", SESSION);
        routes.put("POST /decks/{deckId}/study-sessions/{sessionId}/attempts", keys(FIRST_SESSION, TWO_DAYS, RHYTHM_WEEK, AI_EXPLANATION));
        routes.put("POST /decks/{deckId}/study-sessions/{sessionId}/attempts/{attemptId}/self-rating", SESSION);
        routes.put("POST /decks/{deckId}/study-sessions/{sessionId}/attempts/{attemptId}/self-check", NONE);
        routes.put("POST /decks/{deckId}/study-sessions/{sessionId}/attempts/{attemptId}/dispute", NONE);
        routes.put("POST /decks/{deckId}/study-sessions/{sessionId}/pair-checks", NONE);
        routes.put("POST /decks/{deckId}/study-sessions/{sessionId}/presentations/{presentationId}/hints", NONE);
        routes.put("POST /decks/{deckId}/study-sessions/{sessionId}/presentations/{presentationId}/transcript", NONE);
        routes.put("POST /decks/{deckId}/study-restarts", NONE);
        routes.put("POST /exercise-previews", NONE);
        // ---- speech: the transcript is written by the worker ----
        routes.put("POST /speech-inputs", keys(VOICE_ANSWER));
        routes.put("DELETE /speech-inputs/{id}", NONE);
        routes.put("PUT /speech-consent", NONE);
        routes.put("DELETE /speech-consent", NONE);
        // ---- the Workshop ----
        String generation = "/decks/{deckId}/generation-sessions";
        routes.put("POST " + generation + "/{sessionId}/artifacts/{artifactId}/approval", keys(WORKSHOP_APPROVED, OWN_MATERIAL, MEDIA_IN_MATERIAL, OWN_EXERCISES));
        routes.put("POST " + generation + "/{sessionId}/approvals", keys(WORKSHOP_APPROVED, OWN_MATERIAL, MEDIA_IN_MATERIAL, OWN_EXERCISES));
        routes.put("POST " + generation + "/{sessionId}/note-archival", keys(CAPTURE_NOTE));
        routes.put("POST /decks/{deckId}/generation-estimates", NONE);
        routes.put("POST /decks/{deckId}/generation-intents", NONE);
        routes.put("POST " + generation, NONE);
        routes.put("DELETE " + generation + "/{sessionId}", NONE);
        routes.put("POST " + generation + "/{sessionId}/cancellation", NONE);
        routes.put("POST " + generation + "/{sessionId}/plan-approval", NONE);
        routes.put("POST " + generation + "/{sessionId}/artifacts/{artifactId}/edits", NONE);
        routes.put("POST " + generation + "/{sessionId}/artifacts/{artifactId}/handoff", NONE);
        routes.put("POST " + generation + "/{sessionId}/artifacts/{artifactId}/media-slots/{slotKey}/selection", NONE);
        routes.put("POST " + generation + "/{sessionId}/artifacts/{artifactId}/rejection", NONE);
        routes.put("DELETE " + generation + "/{sessionId}/artifacts/{artifactId}/rejection", NONE);
        routes.put("POST " + generation + "/{sessionId}/artifacts/{artifactId}/retry", NONE);
        routes.put("POST " + generation + "/{sessionId}/artifacts/{artifactId}/revert", NONE);
        // ---- sharing, copies, updates: nobody's authoring ----
        for (String route : new String[] {
                "DELETE /decks/{deckId}/grants/{granteeId}", "POST /decks/{deckId}/grants", "POST /decks/{deckId}/invites", "POST /decks/{deckId}/invites/{inviteId}:revoke",
                "POST /decks/{deckId}/access-requests/{requesterId}:approve", "POST /decks/{deckId}/access-requests/{requesterId}:reject", "PUT /decks/{deckId}/publication",
                "POST /decks/{deckId}/fork:detach", "POST /decks/{deckId}/fork:retry", "PUT /decks/{deckId}/fork:auto-update", "POST /invites:redeem",
                "POST /public/decks/{code}/access-requests", "POST /public/decks/{code}/access-requests:withdraw", "POST /public/decks/{code}/complaints",
                "POST /public/profiles/{username}/complaints", "POST /decks/{deckId}/update-plan:apply", "POST /decks/{deckId}/update-history/{deckRevisionId}:seen",
                "POST /decks/{deckId}/update-history/{deckRevisionId}/units/{kind}/{key}:revert"}) {
            routes.put(route, NONE);
        }
        // ---- the framework's error page answers every method ----
        routes.put("ANY /error", NONE);
        // ---- the rest of the platform ----
        for (String route : new String[] {
                "DELETE /admin/events/{eventId}", "POST /admin/events", "PUT /admin/events/{eventId}", "PATCH /admin/promo-codes/{codeId}", "POST /admin/promo-codes",
                "PATCH /admin/promo-campaigns/{campaignId}", "POST /admin/promo-campaigns",
                "POST /admin/community/anomalies/{code}:dismiss", "POST /admin/complaints/{complaintId}:decide", "POST /admin/complaints/{complaintId}:unhide",
                "POST /admin/support/tickets/{ticketId}/commands", "PUT /admin/community/editorial", "POST /billing/checkout", "POST /billing/tbank/notifications",
                "POST /community/events", "POST /community/exposures", "POST /community/interests:reset", "PUT /community/interests", "POST /experiment-events",
                "DELETE /media-assets/{assetId}/upload", "POST /media-assets/upload-intents", "POST /media-assets/{assetId}/processing/retry",
                "POST /media-assets/{assetId}/upload/finalize", "POST /media-assets/{assetId}/upload/part-urls", "POST /media-assets/{assetId}/upload/retry",
                "POST /media-assets/{assetId}/upload/url", "DELETE /notifications/{notificationId}", "PUT /notifications/read-cursor", "POST /promo-codes/redemptions",
                "POST /promo-popup/events"}) {
            routes.put(route, NONE);
        }
        return routes;
    }

    @Test
    void everyMutatingRouteIsClassifiedByTheMilestonesItCanWrite() {
        RequestMappingHandlerMapping mapping = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Set<String> live = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
            Set<String> patterns = entry.getKey().getPathPatternsCondition().getPatternValues();
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            // a mapping without a method condition answers every method, the mutating ones included
            if (methods.isEmpty()) patterns.forEach(pattern -> live.add("ANY " + pattern));
            for (RequestMethod method : methods) {
                if (MUTATING.contains(method.name())) patterns.forEach(pattern -> live.add(method.name() + " " + pattern));
            }
        }
        assertThat(live).as("a mutating route that is not classified in MilestoneInventoryIntegrationTest (decide which milestones it can write, possibly none), or a classified "
                + "route that no longer exists").isEqualTo(new TreeSet<>(ROUTES.keySet()));
    }

    @Test
    void everyMilestoneWithAWriterIsWrittenByAClassifiedRoute() {
        Set<MilestoneKey> reachable = EnumSet.noneOf(MilestoneKey.class);
        ROUTES.values().forEach(reachable::addAll);
        Set<MilestoneKey> withWriter = EnumSet.noneOf(MilestoneKey.class);
        for (JsonNode milestone : MilestoneFixture.REGISTRY.path("milestones")) {
            if (!milestone.path("writer").isNull()) withWriter.add(MilestoneKey.valueOf(milestone.path("key").stringValue()));
        }
        assertThat(reachable).isEqualTo(withWriter);
    }

    @Test
    void everyRouteAWriterOfTheFixtureNamesIsClassifiedWithItsMilestone() {
        for (JsonNode milestone : MilestoneFixture.REGISTRY.path("milestones")) {
            MilestoneKey key = MilestoneKey.valueOf(milestone.path("key").stringValue());
            for (String route : MilestoneFixture.routes(milestone)) {
                assertThat(ROUTES).as("%s names the route %s", key, route).containsKey(route);
                assertThat(ROUTES.get(route)).as("%s names the route %s", key, route).contains(key);
            }
        }
        // the rhythm is written by the two completing routes' writers and by the preferences route, which the fixture names
        assertThat(MilestoneFixture.routes(MilestoneFixture.REGISTRY.path("milestones").get(MilestoneKey.RHYTHM_WEEK.ordinal()))).containsExactly("PUT /onboarding/preferences");
    }
}
