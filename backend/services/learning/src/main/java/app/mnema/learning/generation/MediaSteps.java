package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;

/** Which steps are the initial media steps of a slot (as opposed to the step of a turn): they fail their slot, not an artifact or a turn. */
final class MediaSteps {
    /** The kinds a {@code TEXT_DRAFT} creates one child step of per media slot that has an executor: licensed image search and speech. */
    static final String IMAGE_SEARCH = "IMAGE_SEARCH";
    static final String TTS = "TTS";

    private MediaSteps() { }

    /** The initial step of a slot: an {@code IMAGE_SEARCH} or {@code TTS} step without a turn. */
    static boolean isSlotStep(String kind, JsonNode input) {
        return (IMAGE_SEARCH.equals(kind) || TTS.equals(kind)) && !input.has("turnId");
    }
}
