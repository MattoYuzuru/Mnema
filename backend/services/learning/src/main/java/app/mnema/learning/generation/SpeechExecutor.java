package app.mnema.learning.generation;

import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.generation.GenerationRepository.ProviderSpend;
import app.mnema.learning.generation.Rows.Artifact;
import app.mnema.learning.generation.Rows.Revision;
import app.mnema.learning.generation.Rows.Slot;
import app.mnema.learning.generation.Rows.Turn;
import app.mnema.learning.media.GeneratedMediaStager;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code TTS} step (#297): speech synthesis through the {@link app.mnema.learning.ai.SpeechSynthesis} port and the speech cache, into the media
 * pipeline. Three inputs share the kind:
 * <ul>
 *   <li><b>the initial step of a slot</b> (input {@code slotKey, assetId, revisionId, spec}, created by {@code TEXT_DRAFT}): synthesise the directive's
 *       text (or take it from the cache), stage it under the slot's own pre-allocated asset, wait for the verification and make the slot READY; no new revision;</li>
 *   <li><b>the turn of a material</b> (input {@code turnId, slotKey}): {@code AUDIO_REGENERATE} of one audio node, a new asset in a new revision;</li>
 *   <li><b>the turn of an exercise</b> (input {@code turnId}, no {@code slotKey}): every audio block that has a transcript is made again in the requested
 *       voice, new assets in a new exercise revision.</li>
 * </ul>
 * Nothing here holds a database transaction while it synthesises, stages or waits ({@link SpeechClips}); each write is one short transaction of
 * {@link SpeechLifecycle} fenced by the lease token. A clip is debited only when this run called a provider.
 */
@Component
class SpeechExecutor implements StepExecutor {
    static final String KIND = "TTS";
    private static final Logger LOG = LoggerFactory.getLogger(SpeechExecutor.class);
    private static final String DEFAULT_VOICE = "female";

    private final SpeechClips clips;
    private final SpeechLifecycle lifecycle;
    private final ImageSearchLifecycle slots;
    private final EditLifecycle edits;
    private final GenerationRepository repository;
    private final ClipRepository clipRows;
    private final GeneratedMediaStager stager;
    private final GenerationSettings settings;
    private final MeterRegistry meters;

    SpeechExecutor(SpeechClips clips, SpeechLifecycle lifecycle, ImageSearchLifecycle slots, EditLifecycle edits, GenerationRepository repository,
                   ClipRepository clipRows, GeneratedMediaStager stager, GenerationSettings settings, MeterRegistry meters) {
        this.clips = clips;
        this.lifecycle = lifecycle;
        this.slots = slots;
        this.edits = edits;
        this.repository = repository;
        this.clipRows = clipRows;
        this.stager = stager;
        this.settings = settings;
        this.meters = meters;
    }

    @Override public String kind() { return KIND; }

    @Override public AiCapability capability() { return AiCapability.TTS; }

    @Override
    public void execute(StepClaim claim, StepControl control) {
        if (!claim.input().has("turnId")) slot(claim, control);
        else if (claim.input().has("slotKey")) materialTurn(claim, control);
        else exerciseTurn(claim, control);
    }

    // --------------------------------------------------------------- the slot

    private void slot(StepClaim claim, StepControl control) {
        Optional<Slot> began = slots.beginSlot(claim);
        if (began.isEmpty() || control.lost()) return;
        Slot slot = began.get();
        JsonNode spec = slot.spec();
        String voice = voiceOf(spec);
        int take = spec.path("take").asInt(0);
        String lang = spec.path("lang").stringValue("en");
        Instant deadline = claim.deadlineAt();
        SpeechClips.Outcome.Staged staged;
        if (staged(stager.assetState(claim.ownerId(), slot.assetId()))) {
            // an earlier attempt already staged the clip: only its verification is left (and its debit and cache entry, if it called a provider)
            staged = resumed(claim, slot.assetId(), new SpeechClips.Clip(spec.path("text").stringValue(""), lang, voice, take));
        } else {
            SpeechClips.Outcome made = clips.stage(claim, control, claim.ownerId(), slot.assetId(),
                    new SpeechClips.Clip(spec.path("text").stringValue(""), lang, voice, take), deadline);
            switch (made) {
                case SpeechClips.Outcome.Interrupted interrupted -> {
                    if (interrupted.cancelled()) endSlot(claim, null, true);
                    return;
                }
                case SpeechClips.Outcome.Failed failed -> {
                    endSlot(claim, failed.code(), false);
                    return;
                }
                case SpeechClips.Outcome.Staged ok -> staged = ok;
            }
        }
        if (!lifecycle.slotStaged(claim)) {
            outcome("void");
            return;
        }
        SpeechClips.Verdict verdict = clips.await(claim, control, claim.ownerId(), staged, deadline);
        if (control.lost()) return;
        switch (verdict) {
            case READY -> {
                boolean stored = lifecycle.slotReady(claim, voice, lang, take, staged.synthesized(), rub(staged.costMicros()));
                outcome(stored ? "succeeded" : "void");
                LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} cache={}", claim.stepId(), claim.sessionId(), KIND,
                        claim.attempt(), stored ? "succeeded" : "void", staged.synthesized() ? "miss" : "hit");
            }
            case REJECTED -> endSlot(claim, "VERIFICATION_REJECTED", false);
            case LATE -> endSlot(claim, "DEADLINE_EXCEEDED", false);
            case CANCELLED -> endSlot(claim, null, true);
            case LOST -> { }
        }
    }

    private void endSlot(StepClaim claim, String errorCode, boolean cancelled) {
        boolean stored = slots.slotFailed(claim, errorCode, cancelled);
        outcome(stored && !cancelled ? "failed" : cancelled ? "cancelled" : "void");
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome=failed error_code={} stored={}", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), errorCode == null ? "-" : errorCode, stored);
    }

    // ------------------------------------------------------ the turn of a material

    private void materialTurn(StepClaim claim, StepControl control) {
        Optional<Turn> began = edits.begin(claim);
        if (began.isEmpty() || control.lost()) return;
        Slot slot = repository.slotsOf(claim.artifactId()).stream()
                .filter(each -> each.slotKey().equals(claim.input().path("slotKey").stringValue(""))).findFirst().orElse(null);
        if (slot == null || !slot.kind().equals("AUDIO") || slot.state().equals("REMOVED")) {
            failTurn(claim, "VERIFICATION_REJECTED");
            return;
        }
        String voice = claim.input().path("voice").stringValue(voiceOf(slot.spec()));
        // the same voice again is a new take (a real synthesis, above every take the slot has had: a revert moves the slot back to an older one);
        // another voice starts at take 0 and may be in the cache
        int take = voice.equals(voiceOf(slot.spec())) ? clipRows.maxTake(slot.artifactId(), slot.slotKey()) + 1 : 0;
        String lang = slot.spec().path("lang").stringValue("en");
        // the asset is a function of the step: a retry resumes the one an earlier attempt staged
        UUID asset = SpeechClips.assetOf(claim.stepId(), 0);
        Instant deadline = claim.deadlineAt();
        SpeechClips.Clip clip = new SpeechClips.Clip(slot.spec().path("text").stringValue(""), lang, voice, take);
        SpeechClips.Outcome made = staged(stager.assetState(claim.ownerId(), asset))
                ? resumed(claim, asset, clip) : clips.stage(claim, control, claim.ownerId(), asset, clip, deadline);
        SpeechClips.Outcome.Staged staged;
        switch (made) {
            case SpeechClips.Outcome.Interrupted interrupted -> {
                if (interrupted.cancelled()) cancelTurn(claim);
                return;
            }
            case SpeechClips.Outcome.Failed failed -> {
                failTurn(claim, failed.code());
                return;
            }
            case SpeechClips.Outcome.Staged ok -> staged = ok;
        }
        SpeechClips.Verdict verdict = clips.await(claim, control, claim.ownerId(), staged, deadline);
        if (control.lost()) return;
        switch (verdict) {
            case READY -> {
                boolean stored = lifecycle.succeedClip(claim, asset, voice, take, staged.synthesized(), rub(staged.costMicros()));
                outcome(stored ? "succeeded" : "void");
                LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} cache={} operation=AUDIO_REGENERATE", claim.stepId(),
                        claim.sessionId(), KIND, claim.attempt(), stored ? "succeeded" : "void", staged.synthesized() ? "miss" : "hit");
            }
            case REJECTED -> failTurn(claim, "VERIFICATION_REJECTED");
            case LATE -> failTurn(claim, "DEADLINE_EXCEEDED");
            case CANCELLED -> cancelTurn(claim);
            case LOST -> { }
        }
    }

    // ----------------------------------------------------- the turn of an exercise

    private void exerciseTurn(StepClaim claim, StepControl control) {
        Optional<Turn> began = edits.begin(claim);
        if (began.isEmpty() || control.lost()) return;
        Artifact artifact = repository.artifact(claim.sessionId(), claim.artifactId()).orElse(null);
        Revision current = artifact == null ? null : repository.revision(artifact.artifactId(), artifact.currentRevisionId()).orElse(null);
        if (current == null) {
            failTurn(claim, "PROVIDER_UNAVAILABLE");
            return;
        }
        ObjectNode command = (ObjectNode) current.payload().path("command").deepCopy();
        List<ObjectNode> blocks = new ArrayList<>();
        audioBlocks(command.path("exercise").path("content"), blocks);
        String voice = claim.input().path("voice").stringValue(DEFAULT_VOICE);
        Instant deadline = claim.deadlineAt();
        ProviderSpend spend = repository.providerSpend(claim.stepId(), "TTS");
        List<SpeechLifecycle.ExerciseClip> made = new ArrayList<>();
        List<Integer> resumedAt = new ArrayList<>();
        long costMicros = 0;
        int synthesizedNow = 0;
        for (int index = 0; index < blocks.size(); index++) {
            ObjectNode block = blocks.get(index);
            String transcript = block.path("transcript").stringValue("");
            String slotKey = "audio" + (index + 1);
            Slot slot = repository.slotsOf(claim.artifactId()).stream().filter(each -> each.slotKey().equals(slotKey)).findFirst().orElse(null);
            if (transcript.isBlank() || slot == null) continue;
            // an exercise's audio block has no language of its own: it is read from the script of its transcript
            String lang = ImageSearchExecutor.language(transcript);
            int take = voice.equals(slot.spec().path("voice").stringValue(null)) ? clipRows.maxTake(slot.artifactId(), slotKey) + 1 : 0;
            // the asset is a function of the step and the clip: a retry resumes the ones an earlier attempt staged
            UUID asset = SpeechClips.assetOf(claim.stepId(), index);
            SpeechClips.Clip clip = new SpeechClips.Clip(transcript, lang, voice, take);
            boolean resume = staged(stager.assetState(claim.ownerId(), asset));
            SpeechClips.Outcome outcome = resume ? resumed(claim, asset, clip, spend) : clips.stage(claim, control, claim.ownerId(), asset, clip, deadline);
            SpeechClips.Outcome.Staged staged;
            switch (outcome) {
                case SpeechClips.Outcome.Interrupted interrupted -> {
                    if (interrupted.cancelled()) cancelTurn(claim);
                    return;
                }
                case SpeechClips.Outcome.Failed failed -> {
                    failTurn(claim, failed.code());
                    return;
                }
                case SpeechClips.Outcome.Staged ok -> staged = ok;
            }
            SpeechClips.Verdict verdict = clips.await(claim, control, claim.ownerId(), staged, deadline);
            if (control.lost()) return;
            switch (verdict) {
                case READY -> { }
                case REJECTED -> {
                    failTurn(claim, "VERIFICATION_REJECTED");
                    return;
                }
                case LATE -> {
                    failTurn(claim, "DEADLINE_EXCEEDED");
                    return;
                }
                case CANCELLED -> {
                    cancelTurn(claim);
                    return;
                }
                case LOST -> {
                    return;
                }
            }
            block.put("assetId", asset.toString());
            if (resume) resumedAt.add(made.size());
            else if (staged.synthesized()) synthesizedNow++;
            made.add(new SpeechLifecycle.ExerciseClip(slotKey, asset, voice, lang, take, staged.synthesized()));
            if (!resume) costMicros += staged.costMicros();
        }
        if (!resumedAt.isEmpty()) {
            // clips of an earlier attempt: the journal knows how many calls answered over all attempts and what they cost, not which clip each made
            int owed = Math.max(0, spend.calls() - synthesizedNow);
            for (int position = 0; position < resumedAt.size(); position++) {
                SpeechLifecycle.ExerciseClip each = made.get(resumedAt.get(position));
                made.set(resumedAt.get(position), new SpeechLifecycle.ExerciseClip(each.slotKey(), each.assetId(), each.voice(), each.lang(), each.take(), position < owed));
            }
            costMicros = Math.max(costMicros, spend.costMicros());
        }
        if (made.isEmpty()) {
            failTurn(claim, "PROVIDER_UNAVAILABLE");
            return;
        }
        boolean stored = lifecycle.succeedExercise(claim, command, made, rub(costMicros));
        outcome(stored ? "succeeded" : "void");
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} clips={} misses={} operation=AUDIO_REGENERATE", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), stored ? "succeeded" : "void", made.size(), made.stream().filter(SpeechLifecycle.ExerciseClip::synthesized).count());
    }

    /** The {@code AUDIO} blocks of an exercise's content in document order (the order of its slots), the nodes themselves. */
    static void audioBlocks(JsonNode node, List<ObjectNode> blocks) {
        if (node.isObject() && node.path("kind").stringValue("").equals("AUDIO")) blocks.add((ObjectNode) node);
        node.forEach(child -> audioBlocks(child, blocks));
    }

    // ----------------------------------------------------------------- shared

    /** The clip an earlier attempt staged: debited as a miss when the journal says a provider answered for the step, at the cost it recorded. */
    private SpeechClips.Outcome.Staged resumed(StepClaim claim, UUID asset, SpeechClips.Clip clip) {
        return resumed(claim, asset, clip, repository.providerSpend(claim.stepId(), "TTS"));
    }

    private SpeechClips.Outcome.Staged resumed(StepClaim claim, UUID asset, SpeechClips.Clip clip, ProviderSpend spend) {
        return clips.resume(asset, clip, spend.calls() > 0, spend.costMicros());
    }

    private static String voiceOf(JsonNode spec) {
        String voice = spec.path("voice").stringValue(null);
        return "male".equals(voice) ? "male" : DEFAULT_VOICE;
    }

    private void failTurn(StepClaim claim, String errorCode) {
        boolean stored = lifecycle.failTurn(claim, errorCode);
        outcome(stored ? "failed" : "void");
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome=failed error_code={} stored={}", claim.stepId(), claim.sessionId(),
                KIND, claim.attempt(), errorCode, stored);
    }

    private void cancelTurn(StepClaim claim) {
        boolean stored = edits.fail(claim, SessionLifecycle.Failure.cancelled());
        outcome(stored ? "cancelled" : "void");
    }

    /** Micro-US-dollars of the provider to the ledger's millionths of a rouble, rounded up. */
    private long rub(long usdMicros) {
        return BigDecimal.valueOf(usdMicros).multiply(settings.usdRubRate()).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private void outcome(String outcome) {
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", outcome).increment();
    }

    /**
     * Whether an earlier attempt already handed bytes to the media pipeline for this asset. A reservation without its transfer ({@code PENDING}, a
     * crash between the two, #296) counts as not staged: the clip is made again (or taken from the cache) and staged under the same asset.
     */
    private static boolean staged(GeneratedMediaStager.State state) {
        return state != GeneratedMediaStager.State.MISSING && state != GeneratedMediaStager.State.PENDING;
    }
}
