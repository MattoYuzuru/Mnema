package app.mnema.learning.capability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code learning.features.<name>.enabled}: the operator's switch per capability. A switch alone never grants a
 * capability; {@link LearningCapabilities} also requires an adapter. Everything defaults to off.
 */
@ConfigurationProperties("learning.features")
public record CapabilityFlags(@DefaultValue Toggle aiAssessment, @DefaultValue Toggle speechToText,
                              @DefaultValue Toggle aiGeneration, @DefaultValue Toggle textToSpeech,
                              @DefaultValue Toggle imageSearch, @DefaultValue Toggle imageGeneration,
                              @DefaultValue Toggle videoGeneration, @DefaultValue Toggle webSearch) {
    public record Toggle(@DefaultValue("false") boolean enabled) { }

    /** Every capability off. */
    public static CapabilityFlags off() { return of(false, false, false, false, false, false, false, false); }

    public static CapabilityFlags of(boolean aiAssessment, boolean speechToText, boolean aiGeneration, boolean textToSpeech,
                                     boolean imageSearch, boolean imageGeneration, boolean videoGeneration,
                                     boolean webSearch) {
        return new CapabilityFlags(new Toggle(aiAssessment), new Toggle(speechToText), new Toggle(aiGeneration),
                new Toggle(textToSpeech), new Toggle(imageSearch), new Toggle(imageGeneration),
                new Toggle(videoGeneration), new Toggle(webSearch));
    }
}
