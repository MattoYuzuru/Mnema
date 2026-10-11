package app.mnema.learning.library;

import app.mnema.learning.catalog.content.ItemPreviews;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.storage.NativeSnapshotDecoder;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The read-only path of someone else's deck (CD-3): the published revision of a deck behind its public code, for guests and any account. Each call
 * checks the kill switch (off: 404 as if the route did not exist), takes a place in the abuse limit (before any lookup, so a flood of unknown codes is
 * limited too), resolves {@link DeckAccess} and only then reads, always from the immutable roots of the published revision: never the deck's head, its
 * drafts, its journal or any other deck of the lineage. The answers carry no editing metadata and no answer keys; media is not served here (Share/9).
 * Two semaphores are tried BEFORE the transaction opens ({@code max-concurrent} places in all, of which guests may hold at most one less), so excess reads fail fast with 503 and never queue for a connection and a signed-in viewer always has a place. Resolution and every SQL lookup are read-only transactions with a ten-second bound; storage reads run in their own short transactions (they take row locks); the titles of a page come from the cache in one statement, and the titles that had to be derived are cached as the last step inside the permit, in a short transaction of their own.
 *
 * <p>Outcomes are counted as {@code mnema_public_deck_requests_total{route,outcome}} (ok, not_found, invite_only, rate_limited, busy, disabled), the 404/403/429
 * figures of architecture section 14.
 */
@Service
public class PublicDeckService {
    private static final Logger LOG = LoggerFactory.getLogger(PublicDeckService.class);

    /** What a granted read works with: the level, the deck and the revision non-owners read. */
    private record Read(AccessLevel level, DeckRef deck, DeckRef.Published revision) { }

    private final PublicRouteSettings settings;
    private final PublicReadLimiter limiter;
    private final DeckAccess access;
    private final ManifestPages manifests;
    private final PublishedContentRepository content;
    private final ItemPreviews previews;
    private final NativeStorageBatches nativeBatches;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;
    private final TransactionTemplate cacheWrite;
    private final Semaphore bulkhead;
    private final Semaphore guestBulkhead;

    PublicDeckService(PublicRouteSettings settings, PublicReadLimiter limiter, DeckAccess access, ManifestPages manifests,
                      PublishedContentRepository content, ItemPreviews previews, ImmutableStorage storage, MeterRegistry meters,
                      PlatformTransactionManager transactions) {
        this.settings = settings;
        this.limiter = limiter;
        this.access = access;
        this.manifests = manifests;
        this.content = content;
        this.previews = previews;
        this.nativeBatches = new NativeStorageBatches(storage);
        this.meters = meters;
        // The read is read-only: nothing it does can write. The title cache is filled afterwards in a short transaction of its own.
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
        this.transaction.setReadOnly(true);
        this.cacheWrite = new TransactionTemplate(transactions);
        this.cacheWrite.setTimeout(5);
        this.bulkhead = new Semaphore(settings.maxConcurrent);
        // guests may hold all but one place: the last one is always free for a signed-in viewer
        this.guestBulkhead = new Semaphore(settings.maxConcurrent - 1);
    }

    public ObjectNode summary(Viewer viewer, String code) {
        return serve("summary", viewer, code, read -> {
            DeckRef.Published published = read.revision();
            ObjectNode result = JsonNodeFactory.instance.objectNode().put("code", code)
                    .put("visibility", read.deck().visibility().name()).put("access", read.level().name())
                    .put("title", published.title()).put("description", published.description())
                    .put("memberCount", published.memberCount()).put("exerciseCount", published.exerciseCount())
                    .put("publishedAt", published.publishedAt().toString()).put("ownerId", read.deck().ownerId().toString());
            if (read.deck().visibility() == DeckVisibility.PUBLIC) result.put("slug", DeckSlug.of(published.title()));
            return result;
        });
    }

    public ObjectNode items(Viewer viewer, String code, String limit, String cursor) {
        return serve("items", viewer, code, read -> {
            List<PublishedContentRepository.NewTitle> derived = new ArrayList<>();
            DeckRef.Published published = read.revision();
            int size = PublicCursor.pageSize(limit);
            int start = start(PublicCursor.decode(cursor, 'i'), published);
            UUID scope = read.deck().scopeId();
            List<Entry> entries = manifests.members(scope, published.membersRootId(), published.memberCount(), start, size);
            Map<String, PublishedContentRepository.ItemRevision> revisions = new HashMap<>();
            sql(() -> content.itemRevisions(scope, entries.stream().map(Entry::key).toList(),
                            entries.stream().map(entry -> entry.target().objectId()).toList()))
                    .forEach(revision -> revisions.put(pair(revision.memberKey(), revision.descriptorRootId()), revision));
            // one statement for the cached titles of the whole page; only a miss reads (and later caches) the document
            Map<String, String> cached = sql(() -> content.cachedTitles(scope, entries.stream().map(Entry::key).toList(),
                    revisions.values().stream().map(PublishedContentRepository.ItemRevision::revisionId).toList()));
            ObjectNode page = page(code, published.memberCount(), "items");
            ArrayNode items = (ArrayNode) page.get("items");
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                PublishedContentRepository.ItemRevision revision = revisions.get(pair(entry.key(), entry.target().objectId()));
                if (revision == null) throw new IllegalStateException("Published member projection is inconsistent");
                String title = cached.get(pair(entry.key(), revision.revisionId()));
                if (title == null) {
                    title = previews.derive(scope, revision.contentRootId());
                    derived.add(new PublishedContentRepository.NewTitle(entry.key(), revision.revisionId(), title));
                }
                items.addObject().put("memberKey", entry.key().toString()).put("itemRevisionId", revision.revisionId().toString())
                        .put("ordinal", start + index).put("title", title);
            }
            // Last step inside the permit: the cache write holds a connection too, so it is bounded by the bulkhead like every other step.
            if (!derived.isEmpty()) {
                try {
                    cacheWrite.executeWithoutResult(status -> content.storeTitles(scope, derived));
                } catch (RuntimeException failure) {
                    // a cache that could not be filled is filled by the next reader; the page was already read
                    LOG.warn("public_title_cache_failed error_type={}", failure.getClass().getSimpleName());
                }
            }
            return next(page, 'i', published, start + entries.size());
        });
    }

    public ObjectNode item(Viewer viewer, String code, String member) {
        return serve("item", viewer, code, read -> {
            UUID memberKey = memberKey(member);
            DeckRef.Published published = read.revision();
            UUID scope = read.deck().scopeId();
            PublishedContentRepository.Located located = published.memberCount() == 0 ? null
                    : sql(() -> content.locate(scope, published.membersRootId(), published.memberCount(), memberKey)).orElse(null);
            if (located == null) throw new ResourceNotFoundException();
            PublishedContentRepository.ItemRevision revision = sql(() -> content.itemRevisions(scope, List.of(memberKey),
                    List.of(located.descriptorRootId()))).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Published member projection is inconsistent"));
            NativeSnapshotDecoder decoder = new NativeSnapshotDecoder(new ObjectRef(scope, revision.contentRootId()));
            while (!decoder.isComplete()) nativeBatches.readNext(decoder);
            ObjectNode result = JsonNodeFactory.instance.objectNode().put("code", code).put("memberKey", memberKey.toString())
                    .put("itemRevisionId", revision.revisionId().toString()).put("ordinal", located.ordinal()).put("formatVersion", 1);
            result.set("document", decoder.snapshot().document().toJson());
            return result;
        });
    }

    public ObjectNode exercises(Viewer viewer, String code, String limit, String cursor) {
        return serve("exercises", viewer, code, read -> {
            DeckRef.Published published = read.revision();
            int size = PublicCursor.pageSize(limit);
            int start = start(PublicCursor.decode(cursor, 'e'), published.revisionId(), published.exerciseCount());
            List<Entry> entries = manifests.exercises(read.deck().scopeId(), published.exercisesRootId(), published.exerciseCount(), start, size);
            Map<String, PublishedContentRepository.ExerciseRow> rows = new HashMap<>();
            sql(() -> content.exercises(read.deck().scopeId(), entries.stream().map(Entry::key).toList(),
                            entries.stream().map(entry -> entry.target().objectId()).toList()))
                    .forEach(row -> rows.put(pair(row.exerciseId(), row.descriptorRootId()), row));
            ObjectNode result = page(code, published.exerciseCount(), "exercises");
            ArrayNode exercises = (ArrayNode) result.get("exercises");
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                PublishedContentRepository.ExerciseRow row = rows.get(pair(entry.key(), entry.target().objectId()));
                if (row == null) throw new IllegalStateException("Published exercise projection is inconsistent");
                exercises.addObject().put("exerciseId", row.exerciseId().toString())
                        .put("exerciseRevisionId", row.revisionId().toString()).put("ordinal", start + index)
                        .put("type", row.type()).put("enabled", row.enabled())
                        .put("prompt", ExercisePrompts.summary(row.type(), row.question()));
            }
            return next(result, 'e', published, start + entries.size());
        });
    }

    private <T> T serve(String route, Viewer viewer, String code, Function<Read, T> body) {
        if (!settings.enabled) {
            count(route, "disabled");
            throw new ResourceNotFoundException();
        }
        // A signed-in viewer was admitted by the filter of the public chain before Identity was asked (one token cannot drive unlimited
        // Identity round trips); only guests are counted here.
        if (viewer.isGuest()) {
            try {
                limiter.admit(viewer);
            } catch (RateLimitedException failure) {
                count(route, "rate_limited");
                throw failure;
            }
        }
        // Before a connection or a transaction is asked for: a flood of public reads is refused here, it never queues for the pool. A guest also
        // needs a place of the guest share, so guests alone can never take the last place.
        boolean guest = viewer.isGuest();
        if (guest && !guestBulkhead.tryAcquire()) {
            count(route, "busy");
            throw new PublicReadBusyException();
        }
        if (!bulkhead.tryAcquire()) {
            if (guest) guestBulkhead.release();
            count(route, "busy");
            throw new PublicReadBusyException();
        }
        try {
            // Resolution is one short read-only transaction. The rest reads immutable data addressed by ids the resolution produced, so it needs no
            // snapshot: storage reads (which take FOR KEY SHARE locks and so cannot run in a read-only transaction) run in their own short
            // transactions, and each SQL lookup is a read-only transaction of its own ({@link #sql}), never one connection held across the whole read.
            Read read = transaction.execute(status -> {
                Resolution resolution = access.resolve(viewer, code);
                if (resolution.denial() == Denial.INVITE_ONLY) throw new DeckInviteOnlyException();
                if (!resolution.granted() || resolution.deck().published() == null) throw new ResourceNotFoundException();
                return new Read(resolution.level(), resolution.deck(), resolution.deck().published());
            });
            T result = body.apply(read);
            count(route, "ok");
            return result;
        } catch (DeckInviteOnlyException failure) {
            count(route, "invite_only");
            throw failure;
        } catch (ResourceNotFoundException failure) {
            count(route, "not_found");
            throw failure;
        } finally {
            bulkhead.release();
            if (guest) guestBulkhead.release();
        }
    }

    /** One read-only lookup with the ten-second bound. */
    private <R> R sql(Supplier<R> lookup) {
        return transaction.execute(status -> lookup.get());
    }

    private void count(String route, String outcome) {
        meters.counter("mnema_public_deck_requests_total", "route", route, "outcome", outcome).increment();
    }

    private static int start(PublicCursor cursor, DeckRef.Published published) {
        return start(cursor, published.revisionId(), published.memberCount());
    }

    private static int start(PublicCursor cursor, UUID revision, int total) {
        if (cursor == null) return 0;
        // A page list of another publication is stale, not wrong: the client restarts from the first page.
        if (!cursor.revisionId().equals(revision)) throw new VersionConflictException();
        if (cursor.next() > total) throw new InvalidRequestException();
        return cursor.next();
    }

    private static ObjectNode page(String code, int total, String name) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("code", code).put("total", total);
        result.putArray(name);
        return result;
    }

    private static ObjectNode next(ObjectNode result, char kind, DeckRef.Published published, int next) {
        int total = result.get("total").intValue();
        if (next < total) result.put("nextCursor", new PublicCursor(kind, published.revisionId(), next).encode());
        else result.putNull("nextCursor");
        return result;
    }

    private static UUID memberKey(String value) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(value), "memberKey");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static String pair(UUID key, UUID descriptor) { return key + "/" + descriptor; }
}
