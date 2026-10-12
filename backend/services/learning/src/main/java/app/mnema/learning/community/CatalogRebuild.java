package app.mnema.learning.community;

import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobHandler;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.Slice;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/**
 * The rebuild of the catalogue (queue {@value CatalogOutbox#REBUILD_QUEUE}, a system job): the backfill that migration {@code V67} enqueues for the decks that were public before the
 * projector existed, and the documented repair tool for any divergence of {@code public_deck} from the publications ("Расхождение проекции — перестраиваемая задачей"): enqueue a job with a new
 * dedupe key (an operator inserts it or calls {@link LearningJobs#enqueue}) and the whole catalogue is looked at again.
 *
 * <p>It does not project anything itself. It walks {@code deck_publication} in {@code deck_id} order, {@code learning.community.catalog.rebuild-size} (200) decks per slice, and hands each deck that
 * is PUBLIC or still has a catalogue row to the projector as {@code catalog.project} with the key {@code deckId:rebuild:jobId}. The projector then does what it always does: reads the current state,
 * and scans a manifest only for a deck whose row is not yet derived from its published revision, so a rebuild of a healthy catalogue is O(public decks) small statements and no manifest read.
 * The cursor is {@code {after}}, the last deck id handed over; a killed worker resumes there, and a repeated hand-over is absorbed by the projector's job keys.
 */
@Component
public class CatalogRebuild implements JobHandler {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProjectionRepository repository;
    private final LearningJobs jobs;
    private final CatalogSettings settings;

    CatalogRebuild(ProjectionRepository repository, LearningJobs jobs, CatalogSettings settings) {
        this.repository = repository;
        this.jobs = jobs;
        this.settings = settings;
    }

    @Override
    public String queue() {
        return CatalogOutbox.REBUILD_QUEUE;
    }

    @Override
    public Slice run(Job job) {
        UUID after = after(job.progress());
        List<UUID> decks = repository.publicDecksAfter(after, settings.rebuildSize);
        for (UUID deck : decks) jobs.enqueue(CatalogOutbox.PROJECT_QUEUE, deck + ":rebuild:" + job.id(), null, CatalogOutbox.payload(deck));
        long handed = job.progressCount() + decks.size();
        if (decks.size() < settings.rebuildSize) return Slice.done(handed);
        return Slice.proceed(JSON.createObjectNode().put("after", decks.getLast().toString()), handed);
    }

    private static UUID after(ObjectNode progress) {
        if (progress == null) return null;
        try {
            return UUID.fromString(progress.path("after").stringValue(""));
        } catch (IllegalArgumentException invalid) {
            throw new PermanentJobException("cursor_invalid");
        }
    }
}
