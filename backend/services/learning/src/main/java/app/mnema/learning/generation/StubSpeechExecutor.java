package app.mnema.learning.generation;

import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.generation.Rows.Turn;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The {@code TTS} step of a media turn of an exercise ({@code REVISE_EXERCISE} with {@code media}, #294) for the Stub provider: local runs
 * and CI only (it exists only with {@code learning.ai.provider=stub}, so production has no executor and the media action stays unavailable).
 *
 * <p>Real synthesis and its acceptance are AI-09 ({@link app.mnema.learning.ai.SpeechSynthesis}, #297). Until then this is a deliberate
 * stand-in that makes the whole turn real: it re-points every audio slot of the exercise to the asset it already has, records the
 * requested voice in the slot's spec, and ends the turn with a new revision (cause {@code MEDIA}) of the same exercise. No audio bytes are
 * made and nothing is debited (the turn's hold is released unspent), so the exercise's audio does not change; a client can tell by the
 * slot's {@code assetId}, which is the one the audio block already used. It claims only the steps of a media turn (input member
 * {@code turnId}), never the media steps of a material's slots.
 */
@Component
@ConditionalOnProperty(name = "learning.ai.provider", havingValue = "stub")
class StubSpeechExecutor implements StepExecutor {
    static final String KIND = "TTS";
    static final String ROUTE = "stub:tts";
    private static final Logger LOG = LoggerFactory.getLogger(StubSpeechExecutor.class);

    private final EditLifecycle edits;
    private final MeterRegistry meters;

    StubSpeechExecutor(EditLifecycle edits, MeterRegistry meters) {
        this.edits = edits;
        this.meters = meters;
    }

    @Override public String kind() { return KIND; }

    @Override public String requiredInput() { return "turnId"; }

    @Override public AiCapability capability() { return AiCapability.TTS; }

    @Override
    public void execute(StepClaim claim, StepControl control) {
        Optional<Turn> began = edits.begin(claim);
        if (began.isEmpty()) return;
        if (control.lost()) return;
        boolean stored = edits.succeedMedia(claim, began.get().voice() == null ? "female" : began.get().voice(), ROUTE);
        meters.counter("mnema_generation_steps_total", "kind", KIND, "outcome", stored ? "succeeded" : "void").increment();
        LOG.info("generation_step_done step_id={} session_id={} kind={} attempt={} outcome={} operation=AUDIO_REGENERATE", claim.stepId(),
                claim.sessionId(), KIND, claim.attempt(), stored ? "succeeded" : "void");
    }
}
