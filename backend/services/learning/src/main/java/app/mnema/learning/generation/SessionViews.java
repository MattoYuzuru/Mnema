package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.UsageLedger;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The wire shapes of {@code contracts/generation/http.json}: session summaries and details, artifact summaries and
 * details. Versions are decimal strings, instants RFC 3339, and the model route, prompt version and provenance of a
 * revision are never written ("Model route, prompt version and provenance are never returned").
 */
@Component
class SessionViews {
    private final GenerationRepository repository;
    private final UsageLedger ledger;

    SessionViews(GenerationRepository repository, UsageLedger ledger) {
        this.repository = repository;
        this.ledger = ledger;
    }

    ObjectNode summary(Session session) {
        return summaries(List.of(session)).getFirst();
    }

    /** Summaries of several sessions, with the artifact counts read in two statements for the whole page. */
    List<ObjectNode> summaries(List<Session> sessions) {
        List<UUID> ids = sessions.stream().map(Session::sessionId).toList();
        Map<UUID, Map<String, Integer>> counts = repository.artifactCounts(ids);
        Map<UUID, Integer> approvable = repository.approvableCounts(ids);
        return sessions.stream().map(session -> summary(session, counts.get(session.sessionId()),
                approvable.get(session.sessionId()))).toList();
    }

    private ObjectNode summary(Session session, Map<String, Integer> counts, int approvable) {
        ObjectNode node = Json.object();
        node.put("sessionId", session.sessionId().toString());
        node.put("deckId", session.deckId().toString());
        node.put("kind", session.kind());
        node.put("state", session.state());
        node.put("rowVersion", Long.toString(session.rowVersion()));
        node.put("endReason", session.endReason());
        node.put("createdAt", Json.time(session.createdAt()));
        node.put("lastActivityAt", Json.time(session.lastActivityAt()));
        node.put("expiresAt", Json.time(session.expiresAt()));
        node.set("artifactCounts", counts(counts));
        node.put("approvableCount", approvable);
        node.set("usage", usage(session));
        return node;
    }

    static ObjectNode counts(Map<String, Integer> counts) {
        ObjectNode node = Json.object();
        for (String state : GenerationRepository.ARTIFACT_STATES) node.put(state, counts.getOrDefault(state, 0));
        return node;
    }

    private ObjectNode usage(Session session) {
        int reserved = 0;
        int spent = 0;
        if (session.reservationId() != null) {
            Optional<Reservation> reservation = ledger.reservation(session.ownerId(), session.reservationId());
            if (reservation.isPresent()) {
                reserved = reservation.get().heldRemaining();
                spent = reservation.get().debitedCredits();
            }
        }
        return Json.object().put("reservedCredits", reserved).put("spentCredits", spent);
    }

    ObjectNode detail(Session session) {
        ObjectNode node = summary(session);
        node.set("spec", session.spec().deepCopy());
        List<Artifact> artifacts = repository.artifacts(session.sessionId());
        Map<UUID, int[]> slots = repository.slotCounts(artifacts.stream().map(Artifact::artifactId).toList());
        ArrayNode list = node.putArray("artifacts");
        for (Artifact artifact : artifacts) list.add(artifactSummary(artifact, slots.get(artifact.artifactId())));
        return node;
    }

    static ObjectNode artifactSummary(Artifact artifact, int[] slots) {
        int[] counts = slots == null ? new int[3] : slots;
        ObjectNode node = Json.object();
        node.put("artifactId", artifact.artifactId().toString());
        node.put("ordinal", artifact.ordinal());
        node.put("targetKind", artifact.targetKind());
        node.put("state", artifact.state());
        node.put("rowVersion", Long.toString(artifact.rowVersion()));
        node.put("title", artifact.title());
        node.put("currentRevisionId", artifact.currentRevisionId() == null ? null : artifact.currentRevisionId().toString());
        node.putObject("mediaSlotCounts").put("total", counts[0]).put("ready", counts[1]).put("failed", counts[2]);
        node.put("errorCode", artifact.errorCode());
        node.put("repinStatus", artifact.repinStatus());
        node.set("publishedRef", artifact.publishedRef() == null ? Json.NODES.nullNode() : artifact.publishedRef().deepCopy());
        return node;
    }

    /**
     * An artifact with the exact revision asked for (the current one by default; {@code revisionId} reads another without
     * moving the pointer), its media slots, the revision list and the turns.
     */
    ObjectNode artifactDetail(Session session, Artifact artifact, Revision revision) {
        ObjectNode node = artifactSummary(artifact, repository.slotCounts(List.of(artifact.artifactId())).get(artifact.artifactId()));
        node.put("sessionId", session.sessionId().toString());
        node.put("deckId", session.deckId().toString());
        node.set("sourceRefs", artifact.sourceRefs().deepCopy());
        if (revision == null) {
            node.putNull("revision");
        } else {
            ObjectNode rev = node.putObject("revision");
            rev.put("revisionId", revision.revisionId().toString());
            rev.put("cause", revision.cause());
            rev.put("createdAt", Json.time(revision.createdAt()));
            rev.set("validation", revision.validation().deepCopy());
            rev.set("payload", revision.payload().deepCopy());
        }
        ArrayNode slots = node.putArray("mediaSlots");
        if (revision != null) {
            for (Slot slot : repository.slots(artifact.artifactId(), revision.revisionId())) {
                slots.addObject().put("slotKey", slot.slotKey()).put("kind", slot.kind()).put("nodeId", slot.nodeId().toString())
                        .put("assetId", slot.assetId().toString()).put("state", slot.state()).put("errorCode", slot.errorCode());
            }
        }
        ArrayNode revisions = node.putArray("revisions");
        for (Revision listed : repository.revisionList(artifact.artifactId())) {
            revisions.addObject().put("revisionId", listed.revisionId().toString()).put("cause", listed.cause())
                    .put("createdAt", Json.time(listed.createdAt()));
        }
        node.putArray("turns");
        return node;
    }

    /** {@code {events, cursor, session, activeSteps}} of {@code events.json}. */
    ObjectNode eventEnvelope(Session session, long after, List<Rows.Event> events, List<Rows.Step> active) {
        ObjectNode node = Json.object();
        ArrayNode list = node.putArray("events");
        long cursor = after;
        for (Rows.Event event : events) {
            ObjectNode item = list.addObject();
            item.put("seq", Long.toString(event.seq()));
            item.put("type", event.type());
            item.put("sessionId", session.sessionId().toString());
            item.put("artifactId", event.artifactId() == null ? null : event.artifactId().toString());
            item.put("occurredAt", Json.time(event.occurredAt()));
            item.set("payload", event.payload().deepCopy());
            cursor = event.seq();
        }
        node.put("cursor", Long.toString(cursor));
        node.putObject("session").put("state", session.state()).put("rowVersion", Long.toString(session.rowVersion()));
        ArrayNode steps = node.putArray("activeSteps");
        for (Rows.Step step : active) {
            steps.addObject().put("stepId", step.stepId().toString())
                    .put("artifactId", step.artifactId() == null ? null : step.artifactId().toString())
                    .put("kind", step.kind()).put("state", step.state())
                    .put("startedAt", step.startedAt() == null ? null : Json.time(step.startedAt()));
        }
        return node;
    }
}
