package app.mnema.learning.library;

import app.mnema.learning.library.PublicationRepository.Blocked;
import app.mnema.learning.library.PublicationRepository.Head;
import app.mnema.learning.library.PublicationRepository.Revision;
import app.mnema.learning.library.PublicationRepository.Row;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The owner's explicit publication (Share/8, #430; contract {@code contracts/decks/publication.json}). Publishing sets the revision non-owners read to the
 * deck's head and nothing else: no deck revision is created and {@code deck.row_version} never moves, so an open editor gets no 412. Every command is
 * a compare-and-set of the one {@code deck_publication} row at its wire version (stored version plus one, 0 for a deck without a row) and an idempotent
 * command receipt.
 *
 * <p>The state after a command is exactly the body: level, metadata, request switch and, when the body publishes, the published revision and its note. A command
 * whose result equals the stored state writes nothing. A resulting level {@link DeckVisibility#PUBLIC} must pass the checklist; the catalog threshold is shown
 * but never blocks. Lowering the level rotates the public code ({@link DeckVisibility#lowersTo}); every effective change of level or revision appends one
 * outbox row for the catalog.
 *
 * <p>Load: the publication is one row; a read adds the head (two keys), the range of the journal after the published revision (two index range scans), at most 20 cached
 * titles, the non-commercial media probe (an empty partial-index probe for almost every owner) and the in-memory directory. Nothing is proportional to the number of copies.
 */
@Service
public class PublicationService {
    /** The catalog threshold of the checklist: informational, never blocking (the catalog epic owns the ranking rule). */
    public static final int NEED_MATERIALS = 10;
    public static final int NEED_EXERCISES = 1;
    static final int TITLE_SAMPLE = 20;
    private static final int CODE_ATTEMPTS = 5;
    private static final String SCOPE = "deck.publication";

    private final PublicationRepository repository;
    private final TopicDirectory topics;
    private final CommandReceiptService receipts;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;
    private final SecureRandom random;

    @Autowired
    PublicationService(PublicationRepository repository, TopicDirectory topics, CommandReceiptService receipts, PlatformTransactionManager transactions) {
        this(repository, topics, receipts, transactions, new SecureRandom());
    }

    PublicationService(PublicationRepository repository, TopicDirectory topics, CommandReceiptService receipts, PlatformTransactionManager transactions,
                       SecureRandom random) {
        this.repository = repository;
        this.topics = topics;
        this.receipts = receipts;
        this.random = random;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setTimeout(10);
    }

    /** The result of a save: the acknowledgement and whether it is a replay of an earlier identical command. */
    public record WriteResult(JsonNode acknowledgement, boolean replayed) {
        public WriteResult { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
    }

    /** The publication state of the owner's deck. {@code profileReady} is the Identity claim of the current request. */
    public ObjectNode read(UUID owner, UUID deck, boolean profileReady) {
        requireIds(owner, deck);
        return readOnly.execute(status -> {
            Head head = repository.head(owner, deck, false).orElseThrow(ResourceNotFoundException::new);
            return state(owner, head, repository.row(deck, false), profileReady);
        });
    }

    /**
     * Applies the command at the wire version the caller read.
     *
     * @throws ResourceNotFoundException the deck is not the owner's or is deleted
     * @throws VersionConflictException the row, or the head named by {@code publish}, is not the one the caller saw
     * @throws PublicationRequiredException the first publication did not publish
     * @throws PublicationRequirementsException the «Публичная» checklist is not met
     */
    public WriteResult save(UUID owner, UUID deck, long wireVersion, PublicationCommand command, boolean profileReady) {
        requireIds(owner, deck);
        if (wireVersion < 0) throw new InvalidRequestException();
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> saveOnce(owner, deck, wireVersion, command, profileReady));
            } catch (DuplicateKeyException collision) {
                // The row of this deck cannot collide (the insert skips an existing row), so this is the public code: draw another in a fresh transaction.
                if (attempt == CODE_ATTEMPTS) throw collision;
            }
        }
    }

    private WriteResult saveOnce(UUID owner, UUID deck, long wireVersion, PublicationCommand command, boolean profileReady) {
        // Receipt callbacks are not run on replay: the current ACL is checked outside them.
        repository.head(owner, deck, false).orElseThrow(ResourceNotFoundException::new);
        CommandIdentity identity = new CommandIdentity(command.commandId(), owner, SCOPE, "publication.save");
        ObjectNode envelope = command.envelope(deck, wireVersion);
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new WriteResult(replay.orElseThrow(), true);
        boolean[] applied = {false};
        JsonNode acknowledgement = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            return apply(owner, deck, wireVersion, command, profileReady);
        });
        return new WriteResult(acknowledgement, !applied[0]);
    }

    private ObjectNode apply(UUID owner, UUID deck, long wireVersion, PublicationCommand command, boolean profileReady) {
        PublicationMetadata metadata = command.metadata();
        if (metadata.topicId() != null && !topics.selectable(metadata.topicId())) throw new InvalidRequestException();
        boolean publishing = command.publish() != null;
        // The head cannot move under a command that publishes it or judges it (a share lock on the deck row; an editor's save waits for this short transaction).
        Head head = repository.head(owner, deck, publishing || command.visibility() == DeckVisibility.PUBLIC).orElseThrow(ResourceNotFoundException::new);
        Optional<Row> current = repository.row(deck, true);
        if (current.map(Row::wireVersion).orElse(0L) != wireVersion) throw new VersionConflictException();

        UUID pointer = current.map(Row::publishedRevisionId).orElse(null);
        if (command.visibility() != DeckVisibility.PRIVATE && pointer == null && !publishing) throw new PublicationRequiredException();
        boolean moves = false;
        if (publishing) {
            if (!command.publish().expectedHeadRevisionId().equals(head.revisionId())) throw new VersionConflictException();
            moves = !head.revisionId().equals(pointer);
            pointer = head.revisionId();
        }
        String note = publishing ? command.publish().releaseNote() : current.map(Row::releaseNote).orElse(null);
        DeckVisibility before = current.map(Row::visibility).orElse(DeckVisibility.PRIVATE);
        UUID target = pointer;

        boolean changed = current.map(row -> row.visibility() != command.visibility() || !row.metadata().equals(metadata)
                        || row.requestsEnabled() != command.requestsEnabled() || !Objects.equals(row.releaseNote(), note)
                        || !Objects.equals(row.publishedRevisionId(), target))
                .orElse(command.visibility() != DeckVisibility.PRIVATE || !metadata.equals(PublicationMetadata.EMPTY) || !command.requestsEnabled());
        Optional<Row> result = current;
        if (changed) {
            if (command.visibility() == DeckVisibility.PUBLIC) {
                // Exposure: the deck becomes public now, or a new revision goes out while it is. A benign edit of a public deck (tags, request switch) exposes nothing new.
                boolean exposure = before != DeckVisibility.PUBLIC || publishing;
                // The NC check reads the head; readers get the published pointer. Going public with a pointer that is not the head would judge one revision and serve another.
                if (exposure && !publishing && !head.revisionId().equals(pointer)) throw new PublicationRequiredException();
                requirePublic(owner, deck, head, pointer, publishing, exposure, metadata, profileReady);
            }
            String rotated = current.isPresent() && before.lowersTo(command.visibility()) ? PublicCodes.next(random) : null;
            result = current.isEmpty()
                    ? repository.insert(deck, command.visibility(), PublicCodes.next(random), pointer, metadata, note, command.requestsEnabled())
                    : repository.update(deck, current.orElseThrow().rowVersion(), command.visibility(), rotated, pointer, moves, metadata, note,
                            command.requestsEnabled());
            if (result.isEmpty()) throw new VersionConflictException();
            // One outbox row per effective write while the deck is public, so the catalog projector never misses a metadata edit; otherwise a level or revision change.
            boolean publicWrite = command.visibility() == DeckVisibility.PUBLIC;
            if ((publicWrite || before != command.visibility() || moves) && pointer != null) repository.appendEvent(deck, pointer, command.visibility());
        }
        ObjectNode acknowledgement = JsonNodeFactory.instance.objectNode();
        acknowledgement.put("commandId", command.commandId().toString()).put("changed", changed);
        acknowledgement.set("publication", state(owner, head, result, profileReady));
        return acknowledgement;
    }

    /**
     * The checklist of a resulting «Публичная» level; the description is that of the revision the readers will get. The description, topic and language hold for every
     * effective write of a public deck. The public profile and the media check gate the exposure only (the transition into PUBLIC and a publish while PUBLIC): a withdrawn
     * consent already hides the author in Identity at once, whether the deck itself is demoted is an open owner decision, and until then it does not freeze benign edits.
     */
    private void requirePublic(UUID owner, UUID deck, Head head, UUID pointer, boolean publishing, boolean exposure, PublicationMetadata metadata, boolean profileReady) {
        List<String> failed = new ArrayList<>();
        String served = publishing ? head.description()
                : repository.revision(deck, pointer).map(Revision::description).orElseThrow(() -> new IllegalStateException("The published revision of a deck is missing"));
        if (served.isBlank()) failed.add("description");
        if (metadata.topicId() == null) failed.add("topic");
        if (metadata.contentLanguage() == null) failed.add("language");
        if (exposure && !profileReady) failed.add("publicProfile");
        if (exposure && !repository.blockedMedia(owner, deck, 1).isEmpty()) failed.add("blockedMedia");
        if (!failed.isEmpty()) throw new PublicationRequirementsException(failed);
    }

    private ObjectNode state(UUID owner, Head head, Optional<Row> found, boolean profileReady) {
        UUID deck = head.deckId();
        DeckVisibility visibility = found.map(Row::visibility).orElse(DeckVisibility.PRIVATE);
        UUID published = found.map(Row::publishedRevisionId).orElse(null);
        Revision publishedRevision = published == null ? null
                : published.equals(head.revisionId()) ? new Revision(head.revisionId(), head.sequence(), head.title(), head.description())
                : repository.revision(deck, published).orElseThrow(() -> new IllegalStateException("The published revision of a deck is missing"));
        PublicationMetadata metadata = found.map(Row::metadata).orElse(PublicationMetadata.EMPTY);
        String code = visibility == DeckVisibility.PRIVATE ? null : found.map(Row::publicCode).orElse(null);
        List<String> titles = repository.headTitles(deck, TITLE_SAMPLE);
        StringBuilder text = new StringBuilder(head.title()).append('\n').append(head.description());
        titles.forEach(title -> text.append('\n').append(title));

        ObjectNode state = JsonNodeFactory.instance.objectNode();
        state.put("deckId", deck.toString()).put("visibility", visibility.name()).put("publicCode", code);
        state.put("link", code == null ? null : visibility == DeckVisibility.PUBLIC
                ? "/d/" + code + "/" + DeckSlug.of(publishedRevision == null ? head.title() : publishedRevision.title()) : "/d/" + code);
        state.put("publishedRevisionId", published == null ? null : published.toString());
        state.put("publishedAt", found.map(Row::publishedAt).map(Object::toString).orElse(null));
        state.put("headRevisionId", head.revisionId().toString());
        if (publishedRevision == null) state.putNull("unpublishedChanges");
        else state.put("unpublishedChanges", publishedRevision.revisionId().equals(head.revisionId()) ? 0 : repository.changesAfter(deck, publishedRevision.sequence()));
        state.set("metadata", metadata.toJson());
        ObjectNode suggested = state.putObject("suggested");
        suggested.put("contentLanguage", LanguageDetector.detect(text.toString()).orElse(null));
        var suggestedTopics = suggested.putArray("topicIds");
        topics.suggest(head.title(), head.description()).forEach(suggestedTopics::add);
        state.put("releaseNote", found.map(Row::releaseNote).orElse(null));
        state.put("requestsEnabled", found.map(Row::requestsEnabled).orElse(true));

        ObjectNode checklist = state.putObject("checklist");
        checklist.put("description", !head.description().isBlank());
        checklist.put("topic", metadata.topicId() != null);
        checklist.put("language", metadata.contentLanguage() != null);
        checklist.put("publicProfile", profileReady);
        checklist.putObject("catalogThreshold").put("met", head.memberCount() >= NEED_MATERIALS && head.exerciseCount() >= NEED_EXERCISES)
                .put("materials", head.memberCount()).put("exercises", head.exerciseCount()).put("needMaterials", NEED_MATERIALS)
                .put("needExercises", NEED_EXERCISES);
        var blocked = checklist.putArray("blockedMedia");
        for (Blocked media : repository.blockedMedia(owner, deck, PublicationRepository.MAX_BLOCKED)) {
            ObjectNode entry = blocked.addObject();
            entry.put("item".equals(media.kind()) ? "memberKey" : "exerciseId", media.subjectId().toString());
            entry.put("reason", "NC_LICENSE");
        }
        state.put("rowVersion", Long.toString(found.map(Row::wireVersion).orElse(0L)));
        return state;
    }

    private static void requireIds(UUID owner, UUID deck) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deck, "deckId");
    }
}
