package app.mnema.learning.generation;

import app.mnema.learning.ai.ResearchSettings;
import app.mnema.learning.usage.AdmissionPricing;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Creates the steps of one material: its {@code TEXT_DRAFT} alone, or, for a material that is fact-checked, a {@code RESEARCH} step first and the draft
 * waiting for it ({@code WAITING_DEPENDENCIES}; it becomes READY when the research ends, whatever it found). The artifact stays QUEUED while it
 * researches: the Workshop reads the {@code RESEARCH} in the active steps. The price of the research is held with the material at admission
 * ({@code WEB_SEARCH_QUERY} x the effort's cap) and debited by the research step per request actually made.
 */
@Component
class ResearchSteps {
    /** The rate-card operation of one search request. */
    static final String OPERATION = "WEB_SEARCH_QUERY";

    private final StepRepository steps;
    private final AdmissionPricing pricing;
    private final ResearchSettings settings;

    ResearchSteps(StepRepository steps, AdmissionPricing pricing, ResearchSettings settings) {
        this.steps = steps;
        this.pricing = pricing;
        this.settings = settings;
    }

    /** The cap of search requests of a material written with {@code effective} settings: 0 without a fact check, for short, or when the global cap is 0. */
    int cap(MaterialsSpec.Effective effective) {
        return effective.researchCap(settings.maxRequests());
    }

    /**
     * Inserts the steps of one material. {@code draftInput} is the input of the {@code TEXT_DRAFT} step as it is without research (effort, operation,
     * credits and, for a retried artifact, its own {@code reservationId}); {@code number} numbers the attempts of the artifact (the draft key is
     * {@code draft:<artifact>:<number>}).
     */
    void queue(UUID sessionId, UUID artifactId, UUID owner, ObjectNode draftInput, int cap, int number) {
        String draftKey = "draft:" + artifactId + ":" + number;
        if (cap < 1) {
            steps.insert(UUID.randomUUID(), sessionId, artifactId, owner, TextDraftExecutor.KIND, "TEXT", draftInput, draftKey);
            return;
        }
        UUID research = UUID.randomUUID();
        ObjectNode input = Json.object().put("effort", draftInput.path("effort").stringValue("MEDIUM")).put("operation", OPERATION)
                .put("cap", cap).put("credits", cap * pricing.credits(OPERATION)).put("draftCredits", draftInput.path("credits").asInt(0));
        if (draftInput.has("reservationId")) input.put("reservationId", draftInput.path("reservationId").stringValue(""));
        steps.insert(research, sessionId, artifactId, owner, ResearchExecutor.KIND, "SEARCH", input, "research:" + artifactId + ":" + number);
        steps.insertWaiting(UUID.randomUUID(), sessionId, artifactId, owner, TextDraftExecutor.KIND, "TEXT", draftInput, draftKey, research, 0);
    }
}
