package app.mnema.learning.onboarding;

/** The states of the tour; {@code NOT_OFFERED} is the absence of a row, or (V75) a stored row that only holds the weekly rhythm and the preferences of an account never offered the tour. */
enum JourneyState { NOT_OFFERED, OFFERED, IN_PROGRESS, PAUSED, SKIPPED, COMPLETED }
