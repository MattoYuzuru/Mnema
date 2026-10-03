package app.mnema.learning.study.attempt;

import java.util.UUID;

/** Published inside the submit transaction when an answer starts to be assessed; the runner reacts after the commit. */
record AssessmentAccepted(UUID attemptId) { }
