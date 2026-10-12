package app.mnema.learning.update.auto;

import app.mnema.learning.platform.jobs.LearningJobs;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * The hook of the publication command (Updates/6 part B, #445; community decks architecture §7): when the PUBLISHED revision of a source moves, one job
 * {@value #FANOUT_QUEUE} per (source deck, published revision) is enqueued in the publication's own transaction (dedupe key {@code deckId:revisionId}, a system job), so the job exists
 * exactly when the publication committed. With the kill switch off ({@code learning.updates.auto.enabled}) nothing is enqueued. The job is the only thing this class does; it reads
 * no copy.
 */
@Service
public class AutoUpdateFanout {
    public static final String FANOUT_QUEUE = "deck.update.fanout";
    public static final String AUTO_QUEUE = "deck.update.auto";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LearningJobs jobs;
    private final AutoUpdateSettings settings;

    AutoUpdateFanout(LearningJobs jobs, AutoUpdateSettings settings) {
        this.jobs = jobs;
        this.settings = settings;
    }

    /** The author's published revision of {@code sourceDeck} is now {@code revision}. Joins the caller's transaction. */
    public void published(UUID sourceDeck, UUID revision) {
        if (!settings.enabled()) return;
        jobs.enqueue(FANOUT_QUEUE, sourceDeck + ":" + revision, null, payload(sourceDeck, revision));
    }

    static ObjectNode payload(UUID deck, UUID revision) {
        return JSON.createObjectNode().put("deckId", deck.toString()).put("revisionId", revision.toString());
    }
}
