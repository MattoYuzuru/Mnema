package app.mnema.learning.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code learning.generation.*}: session retention, the step queue, the checkpoint cadence and the context budgets of the
 * text step. The session limits ({@code max-sources}, {@code max-artifacts-per-session}, ...) are owned by the usage
 * module's {@code GenerationLimits} and are not repeated here. Durations are ISO-8601 or Spring duration strings.
 *
 * @param sessionRetention last activity plus this is {@code expires_at} (architecture section 3)
 * @param maxActiveSessions the admission limit of active sessions per account (contract decision 3)
 * @param usdRubRate roubles per US dollar: the ledger keeps cost in millionths of a rouble, the provider layer in
 *                   micro-dollars (an approximation, like the price table)
 * @param similarTitle similarity (0..1) above which a new title is reported as similar to an existing one
 * @param retention the retention worker: how often it runs, how long an expired session stays readable, when the owner is
 *                  warned and how long the events of an ended session are kept
 */
@ConfigurationProperties("learning.generation")
record GenerationSettings(
        @DefaultValue("P30D") Duration sessionRetention,
        @DefaultValue("3") int maxActiveSessions,
        @DefaultValue("85") BigDecimal usdRubRate,
        @DefaultValue("0.55") double similarTitle,
        @DefaultValue Worker worker,
        @DefaultValue Step step,
        @DefaultValue Stream stream,
        @DefaultValue Context context,
        @DefaultValue Retention retention) {

    GenerationSettings {
        if (sessionRetention.isNegative() || sessionRetention.isZero() || maxActiveSessions < 1 || maxActiveSessions > 100
                || usdRubRate.signum() <= 0 || similarTitle <= 0 || similarTitle > 1) {
            throw new IllegalArgumentException("Invalid generation settings");
        }
    }

    /**
     * The dispatcher of one process ({@code learning.runtime.roles} api never starts it).
     *
     * @param lease how long a claim lives without a heartbeat
     * @param heartbeat how often a running step renews its lease and looks for a cancellation
     * @param sweepInterval how often the sweeper recovers expired leases and claims due steps
     * @param accountCap soft limit of concurrently running steps per account
     * @param renewInterval how often the reservations of running sessions are renewed
     */
    record Worker(@DefaultValue("PT30S") Duration lease, @DefaultValue("PT3S") Duration heartbeat,
                  @DefaultValue("PT2S") Duration sweepInterval, @DefaultValue("4") int accountCap,
                  @DefaultValue("PT10M") Duration renewInterval) {
        Worker {
            if (lease.isNegative() || lease.isZero() || heartbeat.isNegative() || heartbeat.isZero()
                    || heartbeat.compareTo(lease) >= 0 || sweepInterval.isNegative() || sweepInterval.isZero()
                    || accountCap < 1 || renewInterval.isNegative() || renewInterval.isZero()) {
                throw new IllegalArgumentException("Invalid generation worker settings");
            }
        }
    }

    /**
     * Step-level retry policy, on top of the provider layer's own retries and fallback.
     *
     * @param maxAttempts claims of one step before it fails (a crashed or throttled step is claimed again)
     * @param textDraftDeadline the budget of one run of a TEXT_DRAFT step, provider calls and repairs included
     * @param maxLifetime the budget of a whole step from its first claim, however often it is retried; every requeue delay
     *                    is also capped at {@code backoffCap}, so a step never sits READY for long while the UI says "writing"
     */
    record Step(@DefaultValue("3") int maxAttempts, @DefaultValue("PT5S") Duration backoffBase,
                @DefaultValue("PT2M") Duration backoffCap, @DefaultValue("PT6M") Duration textDraftDeadline,
                @DefaultValue("PT1H") Duration maxLifetime) {
        Step {
            if (maxAttempts < 1 || maxAttempts > 20 || backoffBase.isNegative() || backoffCap.compareTo(backoffBase) < 0
                    || textDraftDeadline.isNegative() || textDraftDeadline.isZero()
                    || textDraftDeadline.compareTo(Duration.ofHours(1)) > 0 || maxLifetime.compareTo(textDraftDeadline) < 0
                    || maxLifetime.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("Invalid generation step settings");
            }
        }
    }

    /**
     * Draft checkpoints ({@code BLOCKS_APPENDED}).
     *
     * @param checkpointInterval at most one checkpoint per interval (750 ms by default); zero checkpoints at every block
     * @param maxEventBytes size bound of the blocks of one event; the log row is bounded at 32 KiB of {@code jsonb} text, which is
     *                      longer than the raw JSON, so the default leaves a quarter of headroom
     */
    record Stream(@DefaultValue("PT0.75S") Duration checkpointInterval, @DefaultValue("24576") int maxEventBytes) {
        Stream {
            if (checkpointInterval.isNegative() || maxEventBytes < 1_024 || maxEventBytes > 30_000) {
                throw new IllegalArgumentException("Invalid generation stream settings");
            }
        }
    }

    /**
     * Context budgets of the text step (context-and-quality section 2.3), in estimated tokens unless stated.
     *
     * @param outlineLines the outline of a deck of at most this many materials is shown whole
     * @param latestMaterials larger decks show every exemplar, this many latest materials and the top-K by title similarity
     * @param exemplarTokens one exemplar in full up to this size, longer ones as a skeleton
     * @param exemplarsTotalTokens all exemplars together
     * @param notesTokens all sources together
     * @param outlineTokens the outline
     */
    record Context(@DefaultValue("200") int outlineLines, @DefaultValue("40") int latestMaterials,
                   @DefaultValue("40") int topK, @DefaultValue("2500") int exemplarTokens,
                   @DefaultValue("6000") int exemplarsTotalTokens, @DefaultValue("12000") int notesTokens,
                   @DefaultValue("5000") int outlineTokens) {
        Context {
            if (outlineLines < 1 || latestMaterials < 0 || topK < 0 || exemplarTokens < 100
                    || exemplarsTotalTokens < exemplarTokens || notesTokens < 100 || outlineTokens < 100) {
                throw new IllegalArgumentException("Invalid generation context settings");
            }
        }
    }

    /**
     * Retention of sessions ({@code learning.generation.retention.*}; the span itself is {@code session-retention}).
     *
     * @param interval how often the worker runs
     * @param expiredReadable how long an EXPIRED session stays readable before the purge deletes it
     * @param warnBefore how long before {@code expires_at} the owner is told what would be deleted
     * @param eventsAfterEnd how long the events of a CLOSED, CANCELLED or EXPIRED session are kept
     * @param batch sessions handled per step of one run (a run repeats a step until it finds less than a batch)
     */
    record Retention(@DefaultValue("PT10M") Duration interval, @DefaultValue("P1D") Duration expiredReadable,
                     @DefaultValue("P3D") Duration warnBefore, @DefaultValue("P1D") Duration eventsAfterEnd,
                     @DefaultValue("50") int batch) {
        Retention {
            if (interval.isNegative() || interval.isZero() || expiredReadable.isNegative() || warnBefore.isNegative()
                    || warnBefore.isZero() || eventsAfterEnd.isNegative() || batch < 1 || batch > 1_000) {
                throw new IllegalArgumentException("Invalid generation retention settings");
            }
        }
    }
}
