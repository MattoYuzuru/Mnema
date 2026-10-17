package app.mnema.learning.onboarding;

/** The states of the tour; {@code NOT_OFFERED} is the absence of a row and is never stored. */
enum JourneyState { NOT_OFFERED, OFFERED, IN_PROGRESS, PAUSED, SKIPPED, COMPLETED }
