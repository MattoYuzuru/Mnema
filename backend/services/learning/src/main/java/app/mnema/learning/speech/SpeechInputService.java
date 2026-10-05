package app.mnema.learning.speech;

import app.mnema.learning.ai.Transcription;
import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Speech inputs (#298, {@code contracts/speech}): admission, polling and deletion. Admission is one short transaction under an advisory lock of the account:
 * the replay of an idempotency key, the rate limit (20 inputs in 10 minutes, kept in the database because every instance is stateless), the fair-use check
 * of the STT bucket and the insert of the row and its audio; the provider is called later by {@link SpeechInputWorker}, never in a request.
 *
 * <p><b>Fair use.</b> The ledger counts, it does not hold: {@code UsageLedger.consume} adds the metered seconds once an input is transcribed. Admission
 * therefore refuses ({@code 409 USAGE_LIMIT_REACHED}) when the declared seconds plus the seconds the account has admitted and not counted yet (its queued and
 * running inputs) would not fit a window now, which is the reservation the contract describes without a second ledger; a failed input is simply never
 * counted. The declared seconds are the learner's own word: the worker compares them with the provider's measurement and fails an input that was
 * understated, and an input whose seconds do not fit the window when counted is failed rather than delivered free. Two inputs admitted in the same instant by two instances can overshoot a window by a clip, never by more than the rate limit allows.
 *
 * <p><b>Study.</b> A spoken Study answer is the edited transcript sent with {@code answerSource: SPEECH}: nothing links a speech input to an attempt, so the
 * grader's {@code ASR_GARBLED} flag is the grader's own judgment of the text; {@code garbled} here is the provider's confidence, shown to the learner only.
 */
@Service
class SpeechInputService {
    private static final Logger LOG = LoggerFactory.getLogger(SpeechInputService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An admitted recording. {@code script} is the Stub's scripted answer (null unless the Stub is active and a harness sent one). */
    record Submission(UUID owner, UUID key, Transcription.Purpose purpose, String lang, UUID deck, int declaredMs, String mimeType, byte[] audio, String script) { }

    /** The answer of an admission: the input, whether the key had been used before, and the expiry. */
    record Accepted(UUID id, boolean replayed, Instant expiresAt) { }

    private final SpeechInputRepository repository;
    private final SpeechConsents consents;
    private final SpeechInputSettings settings;
    private final LearningCapabilities capabilities;
    private final Transcription transcription;
    private final UsageLedger ledger;
    private final ObjectProvider<SpeechInputWorker> worker;
    private final TransactionTemplate transaction;

    SpeechInputService(SpeechInputRepository repository, SpeechConsents consents, SpeechInputSettings settings, LearningCapabilities capabilities,
                       Transcription transcription, UsageLedger ledger, ObjectProvider<SpeechInputWorker> worker, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.consents = consents;
        this.settings = settings;
        this.capabilities = capabilities;
        this.transcription = transcription;
        this.ledger = ledger;
        this.worker = worker;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
    }

    /**
     * The most bytes a second of a clip may take, per container: far above what a recorder produces (Opus is about 4 KB/s, AAC and MP3 up to 40 KB/s at
     * their highest rates) and far below what a clip longer than it was declared to be would take. It is the admission-time guard against under-declaring
     * {@code X-Audio-Duration-Ms} (the declared seconds are what fair use reserves); the provider's own measurement settles the rest in the worker.
     */
    private static final java.util.Map<String, Integer> MAX_BYTES_PER_SECOND = java.util.Map.of("audio/ogg", 16_384, "audio/webm", 16_384,
            "audio/mp4", 32_768, "audio/mpeg", 40_960);
    /** Container headers and a first packet: allowed on top of the per-second ceiling, so that a very short clip is not refused. */
    private static final int BYTES_SLACK = 32_768;

    /** Whether {@code bytes} is a believable size for {@code declaredMs} of {@code mime}; false means the duration was understated. */
    static boolean plausibleSize(String mime, int declaredMs, int bytes) {
        long seconds = (declaredMs + 999L) / 1000;
        return bytes <= BYTES_SLACK + seconds * MAX_BYTES_PER_SECOND.getOrDefault(mime, 16_384);
    }

    /**
     * A cheap look at the rate limit before the body is read, so a flood of oversized requests does not cost a 2 MiB read each: no lock, no write, and a
     * replay of a known key passes (it is answered from the stored input). The authoritative check stays in {@link #submit}.
     *
     * @throws RateLimitedException the account is over the limit
     */
    void precheck(UUID owner, UUID key) {
        if (repository.byKey(owner, key).isPresent()) return;
        Long wait = repository.rateWait(owner, settings.rateWindow().toSeconds(), settings.rateLimit());
        if (wait != null) throw new RateLimitedException(wait);
    }

    /**
     * Admits {@code submission}: a new QUEUED input, or the stored one when the key and the body repeat.
     *
     * @throws app.mnema.learning.platform.api.CapabilityUnavailableException the capability is off, unconfigured or temporarily down
     * @throws SpeechConsentRequiredException no consent for the region that would process the clip
     * @throws ResourceNotFoundException the deck is not the owner's
     * @throws IdempotencyConflictException the key was used for another body
     * @throws InvalidRequestException the clip is larger than its declared duration can be
     * @throws RateLimitedException more inputs than {@code learning.speech.rate-limit} in the window
     * @throws app.mnema.learning.usage.UsageLimitReachedException the seconds do not fit the STT bucket
     */
    Accepted submit(Submission submission) {
        capabilities.requireSpeechToText();
        // no usable route cannot reach this line (the capability refused above); the strictest region is the safe default
        Transcription.Region region = transcription.region(submission.lang()).orElse(Transcription.Region.ABROAD);
        consents.require(submission.owner(), region);
        if (!plausibleSize(submission.mimeType(), submission.declaredMs(), submission.audio().length)) throw new InvalidRequestException();
        if (submission.deck() != null && !repository.ownsDeck(submission.owner(), submission.deck())) throw new ResourceNotFoundException();
        byte[] hash = hash(submission);
        UUID id = UUID.randomUUID();
        Accepted accepted = transaction.execute(status -> {
            repository.lock(submission.owner());
            Optional<SpeechInputRepository.Row> existing = repository.byKey(submission.owner(), submission.key());
            if (existing.isPresent()) {
                if (!MessageDigest.isEqual(existing.get().bodyHash(), hash)) throw new IdempotencyConflictException();
                return new Accepted(existing.get().id(), true, existing.get().expiresAt());
            }
            Long wait = repository.waitIfFull(submission.owner(), settings.rateWindow().toSeconds(), settings.rateLimit());
            if (wait != null) throw new RateLimitedException(wait);
            long seconds = repository.openSeconds(submission.owner()) + (submission.declaredMs() + 999) / 1000;
            ledger.requireFairUse(submission.owner(), Bucket.STT, seconds);
            Instant expires = repository.insert(id, submission.owner(), submission.key(), hash, submission.purpose(), submission.lang(), submission.deck(),
                    submission.mimeType(), submission.declaredMs(), submission.audio(), submission.script(), region, settings.deadline(), settings.ttl());
            return new Accepted(id, false, expires);
        });
        if (!accepted.replayed()) {
            LOG.info("speech_input_queued speech_input_id={} purpose={} bytes={} declared_ms={} deck={} region={}", accepted.id(), submission.purpose(),
                    submission.audio().length, submission.declaredMs(), submission.deck() != null, region);
            // after the commit: the worker of this process takes it at once, any other process within its sweep interval
            worker.ifAvailable(SpeechInputWorker::wake);
        }
        return accepted;
    }

    /** The {@code 202} body: a fresh admission and its replay are the same answer. */
    ObjectNode acceptedBody(Accepted accepted) {
        ObjectNode body = JSON.createObjectNode();
        body.put("speechInputId", accepted.id().toString()).put("state", "QUEUED").put("pollAfterMs", settings.pollAfterMs())
                .put("expiresAt", accepted.expiresAt().toString());
        return body;
    }

    /** The input of the owner as the client polls it; an absent, expired or foreign one is {@link ResourceNotFoundException}. */
    ObjectNode read(UUID owner, UUID id) {
        SpeechInputRepository.Row row = repository.find(owner, id).orElseThrow(ResourceNotFoundException::new);
        ObjectNode body = JSON.createObjectNode();
        body.put("speechInputId", row.id().toString()).put("state", row.state());
        if ("DONE".equals(row.state())) body.put("text", row.text()); else body.putNull("text");
        if (row.seconds() == null) body.putNull("seconds"); else body.put("seconds", row.seconds().intValue());
        if (row.lang() == null) body.putNull("lang"); else body.put("lang", row.lang());
        body.put("garbled", row.garbled());
        if (row.errorCode() == null) body.putNull("errorCode"); else body.put("errorCode", row.errorCode());
        body.put("expiresAt", row.expiresAt().toString());
        return body;
    }

    /** Deletes the input and its audio; idempotent (an absent one is not an error). */
    void delete(UUID owner, UUID id) {
        repository.delete(owner, id);
    }

    /** The SHA-256 of the audio and of every member that changes what the input means: the same key with another clip, purpose, language or deck conflicts. */
    static byte[] hash(Submission submission) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(submission.audio());
            String members = "|" + submission.purpose() + "|" + submission.mimeType() + "|" + submission.declaredMs() + "|"
                    + (submission.lang() == null ? "" : submission.lang()) + "|" + (submission.deck() == null ? "" : submission.deck()) + "|"
                    + (submission.script() == null ? "-" : "s" + submission.script());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(submission.audio().length).array());
            digest.update(members.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
