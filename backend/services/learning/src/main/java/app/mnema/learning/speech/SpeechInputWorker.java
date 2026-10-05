package app.mnema.learning.speech;

import app.mnema.learning.ai.AiFailure;
import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.ai.Transcription;
import app.mnema.learning.ai.UserKeys;
import app.mnema.learning.speech.SpeechInputRepository.Claim;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.UsageContentionException;
import app.mnema.learning.usage.UsageLedger;
import app.mnema.learning.usage.UsageLimitReachedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The worker of speech inputs: claims QUEUED rows ({@code FOR UPDATE SKIP LOCKED}, oldest first), transcribes each on its own virtual thread and settles
 * the row. It exists only for the {@code worker} and {@code all} roles ({@code learning.runtime.roles}); an {@code api} process admits inputs and serves
 * polls but never calls a provider and holds no provider key. Generation steps are session-scoped and long, an input is one short anonymous call, so
 * the queue is the {@code speech_input} table itself rather than the step queue.
 *
 * <p>No transaction is open during the provider call: the claim, the read of the audio, the read of the deck terms and the settlement are each their own
 * short unit. Wake-ups: the {@code afterCommit} of an admission ({@link #wake}), the end of every run and the sweeper
 * ({@code learning.speech.sweep-interval}, 2 s). The table is the source of truth: a lost wake-up costs at most one sweep. Failing inputs past their
 * deadline, deleting audio left behind a terminal input and purging expired rows is {@link SpeechInputSweeper}'s, which runs in every role, so an
 * {@code api} process cleans up too. Per-instance concurrency is {@code learning.ai.permits.stt} (4).
 *
 * <p><b>Consent.</b> The consent is read again when an input is claimed: gone (withdrawn), of an old version or no longer covering the region admission
 * required, the input fails {@code UNAVAILABLE} with its audio deleted and no provider is called; otherwise the consent's region is what the router may
 * use ({@link Transcription.Request#allowedRegion()}), so a clip never leaves Russia on an RU consent, whatever fails before.
 *
 * <p><b>Fair use.</b> The declared duration is the learner's word: a provider measurement above it by more than {@code max(2 s, 20 %)} fails the input
 * {@code TOO_LONG} and delivers nothing, and an input whose seconds do not fit the window when counted is failed {@code UNAVAILABLE} (a retry meets the
 * {@code 409 USAGE_LIMIT_REACHED} of admission) instead of being delivered uncounted.
 *
 * <p>Settlement: the audio is deleted with the transition to DONE or FAILED, in the same transaction; DONE also counts the metered seconds in the STT
 * bucket ({@link UsageLedger#consume}); a failed input is not counted. A late settlement (the learner deleted the input meanwhile, or the sweeper failed
 * it) changes nothing: it is fenced by the claim token.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class SpeechInputWorker implements DisposableBean {
    private static final Logger LOG = LoggerFactory.getLogger(SpeechInputWorker.class);
    /** The longest text a row holds (the column's limit); a transcript of a 60-second clip is far shorter, so a longer one is a provider fault. */
    static final int MAX_TEXT = 20_000;

    private final SpeechInputRepository repository;
    private final SpeechHints hints;
    private final SpeechConsents consents;
    private final Transcription transcription;
    private final UserKeys userKeys;
    private final UsageLedger ledger;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;
    private final Semaphore permits;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    private volatile boolean stopping;

    SpeechInputWorker(SpeechInputRepository repository, SpeechHints hints, SpeechConsents consents, Transcription transcription, UserKeys userKeys, UsageLedger ledger,
                      AiProperties ai, MeterRegistry meters, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.hints = hints;
        this.consents = consents;
        this.transcription = transcription;
        this.userKeys = userKeys;
        this.ledger = ledger;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
        this.permits = new Semaphore(ai.permits().stt());
    }

    /** Asks for a pass over the queue; coalesced, never blocks the caller. */
    void wake() {
        try {
            threads.execute(this::drain);
        } catch (java.util.concurrent.RejectedExecutionException closing) {
            // the context is shutting down; the rows are failed or taken by the next process
        }
    }

    @Scheduled(initialDelayString = "${learning.speech.sweep-interval:PT2S}", fixedDelayString = "${learning.speech.sweep-interval:PT2S}")
    void sweep() {
        drain();
    }

    /** Claims and starts inputs until nothing is queued or every permit is taken. Single-flight: a second caller sets a flag. */
    void drain() {
        if (stopping) return;
        if (!draining.compareAndSet(false, true)) {
            again.set(true);
            return;
        }
        try {
            do {
                again.set(false);
                while (startOne()) {
                    // keep claiming while there is capacity and queued work
                }
            } while (again.get());
        } catch (RuntimeException failure) {
            LOG.warn("speech_input_drain_failed error_type={}", failure.getClass().getSimpleName());
        } finally {
            draining.set(false);
        }
        if (again.get()) wake();
    }

    private boolean startOne() {
        if (!permits.tryAcquire()) return false;
        Optional<Claim> claim;
        try {
            claim = repository.claim(UUID.randomUUID());
        } catch (RuntimeException failure) {
            permits.release();
            throw failure;
        }
        if (claim.isEmpty()) {
            permits.release();
            return false;
        }
        threads.execute(() -> {
            try {
                run(claim.get());
            } finally {
                permits.release();
                wake();
            }
        });
        return true;
    }

    /** One input, start to settlement. Anything unexpected leaves the row TRANSCRIBING, which the sweeper fails after the deadline. */
    void run(Claim claim) {
        try {
            Optional<Transcription.Region> allowed = consents.allowed(claim.ownerId(), claim.consentRegion());
            if (allowed.isEmpty()) {
                // withdrawn, outdated or narrower since the admission: the voice is not processed at all, and the audio goes with the failure
                fail(claim, "UNAVAILABLE");
                return;
            }
            AiResult<Transcription.Transcript> result = transcribe(claim, allowed.get());
            settle(claim, result);
        } catch (RuntimeException failure) {
            LOG.error("speech_input_crashed speech_input_id={} error_type={}", claim.id(), failure.getClass().getSimpleName());
        }
    }

    private AiResult<Transcription.Transcript> transcribe(Claim claim, Transcription.Region allowed) {
        Optional<SpeechInputRepository.Audio> audio = repository.audio(claim.id());
        if (audio.isEmpty()) return AiResult.failed(new AiFailure.Transient("audio_gone"));
        Duration remaining = Duration.between(Instant.now(), claim.deadlineAt());
        if (remaining.isNegative() || remaining.isZero()) return AiResult.failed(new AiFailure.Timeout());
        try {
            OpaqueUserKey key = userKeys.configured() ? userKeys.opaque(claim.ownerId()) : null;
            Transcription.Request request = new Transcription.Request(audio.get().bytes(), claim.mimeType(), claim.langHint(),
                    hints.of(claim.ownerId(), claim.deckId()), key, remaining, claim.declaredMs(), claim.purpose(),
                    transcription.scriptable() ? audio.get().script() : null, allowed);
            return transcription.transcribe(request);
        } catch (RuntimeException failure) {
            LOG.warn("speech_input_transcribe_failed speech_input_id={} error_type={}", claim.id(), failure.getClass().getSimpleName());
            return AiResult.failed(new AiFailure.Transient("transcribe_error"));
        }
    }

    private void settle(Claim claim, AiResult<Transcription.Transcript> result) {
        if (result instanceof AiResult.Ok<Transcription.Transcript> ok) {
            Transcription.Transcript transcript = ok.value();
            if (understated(claim.declaredMs(), transcript.seconds())) {
                LOG.warn("speech_input_duration_understated speech_input_id={} declared_ms={} measured_s={}", claim.id(), claim.declaredMs(), transcript.seconds());
                fail(claim, "TOO_LONG");
            } else if (transcript.text().isBlank()) fail(claim, "NO_SPEECH");
            else if (transcript.text().length() > MAX_TEXT) fail(claim, "UNAVAILABLE");
            else done(claim, transcript);
            return;
        }
        fail(claim, errorCode(((AiResult.Failed<Transcription.Transcript>) result).failure()));
    }

    /** Whether the provider measured more than the recorder declared by more than {@code max(2 s, 20 %)}: the clip was longer than it was paid for. */
    static boolean understated(int declaredMs, int measuredSeconds) {
        long declared = (declaredMs + 999L) / 1000;
        return measuredSeconds > declared + Math.max(2, (declared + 4) / 5);
    }

    /** What the learner is told of a failed transcription; only the learner's own clip is their fault, everything else is {@code UNAVAILABLE}. */
    static String errorCode(AiFailure failure) {
        if (failure instanceof AiFailure.InvalidOutput invalid && "unsupported_audio".equals(invalid.detail())) return "UNSUPPORTED_AUDIO";
        if (failure instanceof AiFailure.Refusal refusal && "too_long".equals(refusal.detail())) return "TOO_LONG";
        return "UNAVAILABLE";
    }

    private void done(Claim claim, Transcription.Transcript transcript) {
        int seconds = Math.min(Transcription.MAX_SECONDS + 1, transcript.seconds());
        boolean written;
        try {
            written = Boolean.TRUE.equals(transaction.execute(status -> {
                if (!repository.done(claim, transcript.text(), seconds, transcript.lang(), transcript.garbled())) return false;
                ledger.consume(claim.ownerId(), Bucket.STT, seconds, "stt:" + claim.ownerId() + ":" + claim.id(), claim.id().toString());
                return true;
            }));
        } catch (UsageLimitReachedException | UsageContentionException overshoot) {
            // the seconds do not fit a window when counted (two admissions raced past it, or the counter was busy): the text is not delivered free; the
            // transaction rolled back, the input fails and a retry meets the 409 of admission
            LOG.warn("speech_input_usage_overshoot speech_input_id={} seconds={} reason={}", claim.id(), seconds, overshoot.getClass().getSimpleName());
            fail(claim, "UNAVAILABLE");
            return;
        }
        if (written) {
            meters.counter("mnema_stt_inputs_total", "outcome", "DONE").increment();
            LOG.info("speech_input_done speech_input_id={} purpose={} outcome=DONE seconds={} garbled={}", claim.id(), claim.purpose(), seconds, transcript.garbled());
        }
    }

    private void fail(Claim claim, String code) {
        boolean written = Boolean.TRUE.equals(transaction.execute(status -> repository.failed(claim, code)));
        if (written) {
            meters.counter("mnema_stt_inputs_total", "outcome", code).increment();
            LOG.info("speech_input_done speech_input_id={} purpose={} outcome={}", claim.id(), claim.purpose(), code);
        }
    }

    /** A clean shutdown: no new claims and a few seconds for the runs to finish; one that does not is failed by the sweeper of any instance after its deadline. */
    @Override
    public void destroy() {
        stopping = true;
        threads.shutdown();
        try {
            if (!threads.awaitTermination(5, TimeUnit.SECONDS)) threads.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            threads.shutdownNow();
        }
    }
}
