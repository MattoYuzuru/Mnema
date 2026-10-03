package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.generation.Rows.Slot;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wire shapes of {@code contracts/generation/http.json}: session summaries and details, artifact summaries and
 * details. Versions are decimal strings, instants RFC 3339, and the model route, prompt version and provenance of a
 * revision are never written ("Model route, prompt version and provenance are never returned").
 */
@Component
class SessionViews {
    private final GenerationRepository repository;
    private final SessionReservations reservations;
    private final NoteArchival notes;
    private final PinnedMaterials materials;

    SessionViews(GenerationRepository repository, SessionReservations reservations, NoteArchival notes, PinnedMaterials materials) {
        this.repository = repository;
        this.reservations = reservations;
        this.notes = notes;
        this.materials = materials;
    }

    ObjectNode summary(Session session) {
        return summaries(List.of(session)).getFirst();
    }

    /** Summaries of several sessions, with the artifact counts read in two statements for the whole page. */
    List<ObjectNode> summaries(List<Session> sessions) {
        List<UUID> ids = sessions.stream().map(Session::sessionId).toList();
        Map<UUID, Map<String, Integer>> counts = repository.artifactCounts(ids);
        Map<UUID, Integer> approvable = repository.approvableCounts(ids);
        Map<UUID, SessionReservations.Totals> usage = reservations.totals(sessions);
        return sessions.stream().map(session -> summary(session, counts.get(session.sessionId()),
                approvable.get(session.sessionId()), usage.get(session.sessionId()))).toList();
    }

    private ObjectNode summary(Session session, Map<String, Integer> counts, int approvable, SessionReservations.Totals usage) {
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
        node.putObject("usage").put("reservedCredits", usage.reserved()).put("spentCredits", usage.spent());
        return node;
    }

    static ObjectNode counts(Map<String, Integer> counts) {
        ObjectNode node = Json.object();
        for (String state : GenerationRepository.ARTIFACT_STATES) node.put(state, counts.getOrDefault(state, 0));
        return node;
    }

    ObjectNode detail(Session session) {
        ObjectNode node = summary(session);
        node.set("spec", session.spec().deepCopy());
        NoteArchival.Counts used = notes.counts(session);
        node.putObject("notes").put("used", used.used()).put("archivable", used.archivable());
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
        node.set("sourceRefs", withNoteStatus(session, artifact.sourceRefs()));
        if (revision == null) {
            node.putNull("revision");
        } else {
            ObjectNode rev = node.putObject("revision");
            rev.put("revisionId", revision.revisionId().toString());
            rev.put("cause", revision.cause());
            rev.put("createdAt", Json.time(revision.createdAt()));
            rev.set("validation", revision.validation().deepCopy());
            rev.set("payload", revision.payload().deepCopy());
            if (artifact.targetKind().equals("EXERCISE")) node.set("display", display(session, revision));
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

    /**
     * What a client needs to show an exercise proposal without further requests (read-only, never stored): its mechanic, the title of
     * the objective it evidences (the offered one or the new one) and the plain text of every material node it quotes, by node id,
     * as that node reads in the pinned revision. A node whose revision cannot be read is simply absent.
     */
    private ObjectNode display(Session session, Revision revision) {
        JsonNode command = revision.payload().path("command");
        JsonNode exercise = command.path("exercise");
        ObjectNode display = Json.object().put("mechanic", exercise.path("type").stringValue(""));
        display.put("objectiveTitle", objectiveTitle(session, command.path("objective")));
        ObjectNode quotes = display.putObject("quotes");
        Map<String, PinnedMaterials.Pinned> pinned = new java.util.HashMap<>();
        quoted(exercise.path("content"), quote -> {
            String key = quote.path("memberKey").stringValue("") + ":" + quote.path("itemRevisionId").stringValue("");
            PinnedMaterials.Pinned material = pinned.computeIfAbsent(key, ignored -> materials.read(session.ownerId(), session.deckId(),
                    UUID.fromString(quote.path("memberKey").stringValue("")), UUID.fromString(quote.path("itemRevisionId").stringValue(""))).orElse(null));
            if (material == null) return;
            material.text(UUID.fromString(quote.path("nodeId").stringValue(""))).ifPresent(text -> quotes.put(quote.path("nodeId").stringValue(""), text));
        });
        return display;
    }

    private String objectiveTitle(Session session, JsonNode objective) {
        if (objective.path("operation").stringValue("").equals("create")) return objective.path("title").stringValue("");
        try {
            return repository.objectiveTitle(session.ownerId(), session.deckId(), UUID.fromString(objective.path("objectiveId").stringValue("")),
                    UUID.fromString(objective.path("objectiveRevisionId").stringValue(""))).orElse("");
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    /** Every {@code MATERIAL} block in a content tree. */
    static void quoted(JsonNode node, java.util.function.Consumer<JsonNode> visit) {
        if (node.isObject() && node.path("kind").stringValue("").equals("MATERIAL")) visit.accept(node);
        node.forEach(child -> quoted(child, visit));
    }

    /**
     * The pins with a read-time {@code status} on NOTE entries (#290): {@code CURRENT} while the note is at the pinned
     * {@code row_version}; {@code DELETED} when it is gone; otherwise {@code ARCHIVED} when it is archived and its text still
     * is the pinned snapshot (archiving is the only change), else {@code CHANGED}. Informational: it never changes the artifact.
     */
    private ArrayNode withNoteStatus(Session session, JsonNode refs) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode ref : refs) if (ref.path("type").stringValue("").equals("NOTE")) ids.add(UUID.fromString(ref.path("noteId").stringValue("")));
        Map<UUID, GenerationRepository.NoteLook> looks = repository.noteLooks(session.ownerId(), session.deckId(), ids);
        ArrayNode result = Json.array();
        for (JsonNode ref : refs) {
            ObjectNode copy = (ObjectNode) ref.deepCopy();
            if (copy.path("type").stringValue("").equals("NOTE")) {
                UUID note = UUID.fromString(copy.path("noteId").stringValue(""));
                long pin = Long.parseLong(copy.path("noteRowVersion").stringValue("0"));
                GenerationRepository.NoteLook look = looks.get(note);
                String status;
                if (look == null) status = "DELETED";
                else if (look.rowVersion() == pin) status = "CURRENT";
                else if (look.archived() && repository.pinnedNoteText(session.sessionId(), note, pin).filter(look.text()::equals).isPresent()) status = "ARCHIVED";
                else status = "CHANGED";
                copy.put("status", status);
            }
            result.add(copy);
        }
        return result;
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
