package app.mnema.learning.moderation;

import app.mnema.learning.admin.AdminAudit;
import app.mnema.learning.library.DeckModeration;
import app.mnema.learning.library.PublishedUnits;
import app.mnema.learning.moderation.ComplaintRepository.Unit;
import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import app.mnema.learning.platform.api.ComplaintDecidedException;
import app.mnema.learning.platform.api.ComplaintNotHiddenException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The owner console's side of complaints (Share/15, #437; contract {@code contracts/moderation/complaints.json}): the queue, the detail, the unit picker, the
 * decisions and the journal. The caller (the controller) has already proved the console owner and the admin client token; the actor is that verified account.
 *
 * <p><b>A decision</b> resolves the takedown units first (storage and manifest reads, outside any transaction), then is ONE transaction: the complaint row is
 * locked, becomes DECIDED once, its effect is applied ({@link DeckModeration#hide}, the {@code content_takedown} rows), one row is appended to the
 * immutable journal, one to the console audit, and the owner's notification is published as the last write (it takes the recipient's cursor lock). A complaint has
 * exactly one decision: the same decision again is the same answer without a second journal row (a {@code TAKEN_DOWN} may add more units), another is
 * {@link ComplaintDecidedException}. Unhiding is explicit ({@link #unhide}). Nothing here writes to Identity: {@code ACCOUNT_REFERRED} only records that the
 * operator moves to the Identity directory.
 *
 * <p>The lock order is the complaint row, then the publication row ({@link DeckModeration}), then the recipient's notification cursor; no path takes them the
 * other way.
 */
@Service
public class ComplaintAdminService {
    private static final int DETAIL_TAKEDOWNS = 200;
    private static final Set<String> DECISIONS_FOR_PROFILE = Set.of("REJECTED", "ACCOUNT_REFERRED");
    private static final Set<String> DECISIONS_FOR_DECK = Set.of("REJECTED", "HIDDEN", "TAKEN_DOWN");

    private record Applied(UUID complaintId, boolean changed) { }

    private final ComplaintRepository repository;
    private final DeckModeration decks;
    private final PublishedUnits published;
    private final AdminAudit audit;
    private final NotificationPublisher notifications;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    @Autowired
    ComplaintAdminService(ComplaintRepository repository, DeckModeration decks, PublishedUnits published, AdminAudit audit, NotificationPublisher notifications,
                          MeterRegistry meters, PlatformTransactionManager transactions) {
        this(repository, decks, published, audit, notifications, meters, transactions, Clock.systemUTC());
    }

    ComplaintAdminService(ComplaintRepository repository, DeckModeration decks, PublishedUnits published, AdminAudit audit, NotificationPublisher notifications,
                          MeterRegistry meters, PlatformTransactionManager transactions, Clock clock) {
        this.repository = repository;
        this.decks = decks;
        this.published = published;
        this.audit = audit;
        this.notifications = notifications;
        this.meters = meters;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(20);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setTimeout(10);
    }

    // ---- reads ----

    /** @param status {@code OPEN} (default) or {@code DECIDED} */
    public ObjectNode queue(String status, String limit, String cursor) {
        boolean open;
        if (status == null || "OPEN".equals(status)) open = true;
        else if ("DECIDED".equals(status)) open = false;
        else throw new InvalidRequestException();
        int size = ComplaintCursor.pageSize(limit);
        ComplaintCursor after = ComplaintCursor.decode(cursor);
        return readOnly.execute(transactionStatus -> {
            List<Complaint> rows = repository.page(open, after, size);
            boolean more = rows.size() > size;
            List<Complaint> items = more ? rows.subList(0, size) : rows;
            Map<UUID, String> titles = repository.deckTitles(items.stream().map(Complaint::deckId).filter(java.util.Objects::nonNull).distinct().toList());
            Instant now = clock.instant();
            ObjectNode page = JsonNodeFactory.instance.objectNode();
            ArrayNode array = page.putArray("items");
            items.forEach(item -> array.add(listed(item, titles.get(item.deckId()), now)));
            if (more) {
                Complaint last = items.getLast();
                Instant at = open ? last.dueAt() : last.decidedAt();
                page.put("nextCursor", new ComplaintCursor(at, last.id()).encode());
            } else {
                page.putNull("nextCursor");
            }
            return page;
        });
    }

    public ObjectNode detail(UUID id) {
        UuidPolicy.requireEntityId(id, "complaintId");
        return readOnly.execute(status -> detail(repository.find(id).orElseThrow(ResourceNotFoundException::new), null));
    }

    /** The picker: the units of the PUBLISHED revision of the complaint's deck. */
    public ObjectNode units(UUID id, String kind, String limit, String cursor) {
        UuidPolicy.requireEntityId(id, "complaintId");
        Complaint complaint = repository.find(id).orElseThrow(ResourceNotFoundException::new);
        if (!complaint.isDeck() || kind == null) throw new InvalidRequestException();
        PublishedUnits.Page page = published.page(complaint.deckId(), kind, limit, cursor);
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("kind", page.kind()).put("total", page.total());
        ArrayNode units = result.putArray("units");
        for (PublishedUnits.Unit unit : page.units()) {
            ObjectNode node = units.addObject().put("kind", unit.kind()).put("key", unit.key().toString()).put("revisionId", unit.revisionId().toString())
                    .put("ordinal", unit.ordinal());
            if (PublishedUnits.ITEM.equals(unit.kind())) node.put("title", unit.label());
            else node.put("type", unit.type()).put("prompt", unit.label());
            node.put("takenDown", unit.takenDown());
        }
        result.put("nextCursor", page.nextCursor());
        return result;
    }

    /** Newest first by journal id (uuidv7). */
    public ObjectNode journal(String limit, String cursor) {
        int size = ComplaintCursor.pageSize(limit);
        UUID before = AdminAudit.cursor(cursor);
        return readOnly.execute(status -> {
            List<ComplaintRepository.Journal> rows = repository.journal(before, size);
            boolean more = rows.size() > size;
            List<ComplaintRepository.Journal> items = more ? rows.subList(0, size) : rows;
            ObjectNode page = JsonNodeFactory.instance.objectNode();
            ArrayNode array = page.putArray("items");
            for (ComplaintRepository.Journal row : items) {
                array.addObject().put("journalId", row.journalId().toString()).put("complaintId", row.complaintId().toString()).put("action", row.action())
                        .put("actorId", row.actorId().toString()).put("occurredAt", row.occurredAt().toString()).put("targetKind", row.targetKind())
                        .put("deckId", row.deckId() == null ? null : row.deckId().toString()).put("publicCode", row.publicCode())
                        .put("profileAccountId", row.profileAccountId() == null ? null : row.profileAccountId().toString()).put("unitCount", row.unitCount());
            }
            page.put("nextCursor", more ? items.getLast().journalId().toString() : null);
            return page;
        });
    }

    // ---- decisions ----

    /**
     * @throws ResourceNotFoundException no such complaint
     * @throws InvalidRequestException the decision does not fit the target, or a unit is not in the deck's lineage
     * @throws ComplaintDecidedException the complaint already has another decision
     */
    public ObjectNode decide(UUID actor, UUID id, ComplaintCommands.Decision decision) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(id, "complaintId");
        try {
            Complaint seen = repository.find(id).orElseThrow(ResourceNotFoundException::new);
            if (!(seen.isDeck() ? DECISIONS_FOR_DECK : DECISIONS_FOR_PROFILE).contains(decision.decision())) throw new InvalidRequestException();
            Applied applied = transaction.execute(status -> apply(actor, id, decision));
            meters.counter("mnema_complaints_total", "op", "decision", "outcome", applied.changed() ? decision.decision().toLowerCase(java.util.Locale.ROOT) : "replay")
                    .increment();
            return readOnly.execute(status -> detail(repository.find(id).orElseThrow(), applied.changed()));
        } catch (ResourceNotFoundException | InvalidRequestException | ComplaintDecidedException refused) {
            meters.counter("mnema_complaints_total", "op", "decision", "outcome", "refused").increment();
            throw refused;
        }
    }

    /**
     * Clears the HIDDEN flag of the complaint's deck. Only a complaint decided HIDDEN can do it, and only while the deck is hidden by moderation:
     * anything else is {@link ComplaintNotHiddenException} (409) and writes nothing.
     */
    public ObjectNode unhide(UUID actor, UUID id) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(id, "complaintId");
        try {
            transaction.executeWithoutResult(status -> {
                Complaint complaint = repository.lockComplaint(id).orElseThrow(ResourceNotFoundException::new);
                if (!complaint.isDeck()) throw new InvalidRequestException();
                if (!"HIDDEN".equals(complaint.decision())) throw new ComplaintNotHiddenException();
                decks.lockPublication(complaint.deckId());
                if (!decks.unhide(complaint.deckId())) throw new ComplaintNotHiddenException();
                Instant now = clock.instant();
                repository.appendJournal(complaint, "UNHIDDEN", actor, now, 0, ComplaintService.retainUntil(now));
                audit.append(actor, "COMPLAINT_UNHIDE", id, null);
            });
            meters.counter("mnema_complaints_total", "op", "decision", "outcome", "unhidden").increment();
            return readOnly.execute(status -> detail(repository.find(id).orElseThrow(), true));
        } catch (ResourceNotFoundException | InvalidRequestException | ComplaintNotHiddenException refused) {
            meters.counter("mnema_complaints_total", "op", "decision", "outcome", "refused").increment();
            throw refused;
        }
    }

    /**
     * Names the revision of every unit and checks each is a revision of the deck's lineage. Runs in the decision's transaction AFTER the complaint and the
     * publication row are locked (the published revision cannot move meanwhile), and resolves every unit that names no revision in ONE pass per manifest.
     */
    private List<Unit> resolve(Complaint complaint, List<ComplaintCommands.UnitSpec> specs) {
        DeckModeration.Info deck = decks.deck(complaint.deckId()).orElseThrow(ResourceNotFoundException::new);
        Map<PublishedUnits.Key, UUID> published = this.published.publishedRevisions(complaint.deckId(),
                specs.stream().filter(spec -> spec.revisionId() == null).map(spec -> new PublishedUnits.Key(spec.kind(), spec.key())).toList());
        List<Unit> units = new ArrayList<>();
        for (ComplaintCommands.UnitSpec spec : specs) {
            UUID revision = spec.revisionId() != null ? spec.revisionId() : published.get(new PublishedUnits.Key(spec.kind(), spec.key()));
            if (revision == null) throw new InvalidRequestException();
            units.add(new Unit(spec.kind(), spec.key(), revision));
        }
        Set<UUID> items = repository.existingItemRevisions(deck.scopeId(), units.stream().filter(unit -> "ITEM".equals(unit.kind())).toList());
        Set<UUID> exercises = repository.existingExerciseRevisions(deck.scopeId(), units.stream().filter(unit -> "EXERCISE".equals(unit.kind())).toList());
        for (Unit unit : units) {
            if (!("ITEM".equals(unit.kind()) ? items : exercises).contains(unit.revisionId())) throw new InvalidRequestException();
        }
        return List.copyOf(units);
    }

    private Applied apply(UUID actor, UUID id, ComplaintCommands.Decision decision) {
        Complaint complaint = repository.lockComplaint(id).orElseThrow(ResourceNotFoundException::new);
        boolean first = complaint.open();
        if (!first && !decision.decision().equals(complaint.decision())) throw new ComplaintDecidedException();
        boolean takeDown = "TAKEN_DOWN".equals(decision.decision());
        boolean hideDecision = "HIDDEN".equals(decision.decision());
        // The decision of a DECIDED complaint can be repeated: a TAKEN_DOWN may add units, a HIDDEN hides again after an unhide; the others are a replay.
        if (!first && !takeDown && !hideDecision) return new Applied(id, false);
        Instant now = clock.instant();
        // lock order: the complaint row, then the publication row; the units are resolved under both
        if (complaint.isDeck() && (takeDown || hideDecision)) decks.lockPublication(complaint.deckId());
        List<Unit> units = takeDown ? resolve(complaint, decision.units()) : List.of();
        if (first) {
            repository.decide(id, decision.decision(), actor, now);
            complaint = repository.find(id).orElseThrow();
        }
        Instant retain = ComplaintService.retainUntil(now);
        DeckModeration.Info deck = complaint.isDeck() ? decks.deck(complaint.deckId()).orElse(null) : null;
        boolean hidden = false;
        int materials = 0;
        int exercises = 0;
        if (hideDecision) {
            hidden = decks.hide(complaint.deckId(), now);
        } else if (takeDown) {
            if (deck == null) throw new ResourceNotFoundException();
            int[] fresh = repository.insertTakedowns(deck.scopeId(), id, units, now);
            materials = fresh[0];
            exercises = fresh[1];
            // the assets of the named revisions are blocked for the whole lineage (a trivial edit that keeps the disputed image does not serve it again)
            repository.recordAssets(id);
            if (decision.hide()) hidden = decks.hide(complaint.deckId(), now);
        }
        int newUnits = materials + exercises;
        boolean changed = first || hidden || newUnits > 0;
        if (!changed) return new Applied(id, false);
        UUID decisionRow = null;
        if (first || newUnits > 0 || (hidden && hideDecision)) decisionRow = repository.appendJournal(complaint, decision.decision(), actor, now, newUnits, retain);
        if (hidden && !hideDecision) repository.appendJournal(complaint, "HIDDEN", actor, now, 0, retain);
        audit.append(actor, "COMPLAINT_DECIDE", id, null);
        // last writes of the transaction: a notification takes the recipient's cursor lock
        if (deck != null && deck.ownerId() != null) {
            String stamp = Long.toString(now.toEpochMilli());
            if (hidden) {
                notifications.publish(deck.ownerId(), NotificationKind.DECK_HIDDEN, "deck-moderation:" + deck.deckId() + ":hidden:" + stamp,
                        Map.of("deckId", deck.deckId()), NotificationRoute.DECK);
            }
            if (newUnits > 0) {
                notifications.publish(deck.ownerId(), NotificationKind.DECK_CONTENT_TAKEN_DOWN, "deck-moderation:" + deck.deckId() + ":takedown:" + decisionRow,
                        Map.of("deckId", deck.deckId(), "materialCount", materials, "exerciseCount", exercises), NotificationRoute.DECK);
            }
        }
        return new Applied(id, true);
    }

    // ---- shapes ----

    private ObjectNode listed(Complaint complaint, String title, Instant now) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("complaintId", complaint.id().toString()).put("targetKind", complaint.targetKind()).put("reason", complaint.reason())
                .put("status", complaint.status()).put("decision", complaint.decision()).put("createdAt", complaint.createdAt().toString())
                .put("dueAt", complaint.dueAt().toString()).put("overdue", complaint.open() && now.isAfter(complaint.dueAt()))
                .put("decidedAt", complaint.decidedAt() == null ? null : complaint.decidedAt().toString())
                .put("deckId", complaint.deckId() == null ? null : complaint.deckId().toString()).put("publicCode", complaint.publicCode())
                .put("deckTitle", title).put("profileAccountId", complaint.profileAccountId() == null ? null : complaint.profileAccountId().toString())
                .put("profileUsername", complaint.profileUsername());
        return node;
    }

    private ObjectNode detail(Complaint complaint, Boolean changed) {
        Instant now = clock.instant();
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("complaintId", complaint.id().toString()).put("targetKind", complaint.targetKind()).put("reason", complaint.reason())
                .put("text", complaint.text()).put("status", complaint.status()).put("decision", complaint.decision())
                .put("reporterId", complaint.reporterId().toString()).put("createdAt", complaint.createdAt().toString())
                .put("dueAt", complaint.dueAt().toString()).put("overdue", complaint.open() && now.isAfter(complaint.dueAt()))
                .put("decidedAt", complaint.decidedAt() == null ? null : complaint.decidedAt().toString())
                .put("decidedBy", complaint.decidedBy() == null ? null : complaint.decidedBy().toString())
                .put("retainUntil", complaint.retainUntil().toString());
        if (complaint.isDeck()) {
            node.set("deck", deck(complaint));
            node.putNull("profile");
        } else {
            node.putNull("deck");
            node.putObject("profile").put("accountId", complaint.profileAccountId().toString()).put("username", complaint.profileUsername());
        }
        ObjectNode takedowns = node.putObject("takedowns");
        List<ComplaintRepository.Takedown> rows = repository.takedowns(complaint.id(), DETAIL_TAKEDOWNS + 1);
        int count = rows.size() > DETAIL_TAKEDOWNS ? repository.takedownCount(complaint.id()) : rows.size();
        takedowns.put("count", count).put("truncated", rows.size() > DETAIL_TAKEDOWNS);
        ArrayNode items = takedowns.putArray("items");
        rows.stream().limit(DETAIL_TAKEDOWNS).forEach(row -> items.addObject().put("takedownId", row.takedownId().toString()).put("unitKind", row.unitKind())
                .put("unitKey", row.unitKey().toString()).put("revisionId", row.revisionId().toString()).put("createdAt", row.createdAt().toString()));
        if (changed != null) node.put("changed", changed);
        return node;
    }

    private ObjectNode deck(Complaint complaint) {
        Optional<DeckModeration.Info> found = decks.deck(complaint.deckId());
        ObjectNode node = JsonNodeFactory.instance.objectNode().put("deckId", complaint.deckId().toString());
        int open = repository.openAbout(complaint.deckId());
        if (found.isEmpty()) {
            node.put("live", false).putNull("ownerId");
            node.put("publicCode", complaint.publicCode()).putNull("title").put("visibility", "PRIVATE").put("published", false).put("memberCount", 0)
                    .put("exerciseCount", 0).put("hiddenByModeration", false).putNull("hiddenAt").put("openComplaints", open);
            return node;
        }
        DeckModeration.Info info = found.orElseThrow();
        node.put("live", info.live()).put("ownerId", info.ownerId().toString()).put("publicCode", info.publicCode() == null ? complaint.publicCode() : info.publicCode())
                .put("title", info.title()).put("visibility", info.visibility().name()).put("published", info.published()).put("memberCount", info.memberCount())
                .put("exerciseCount", info.exerciseCount()).put("hiddenByModeration", info.hidden())
                .put("hiddenAt", info.hiddenAt() == null ? null : info.hiddenAt().toString()).put("openComplaints", open);
        return node;
    }
}
