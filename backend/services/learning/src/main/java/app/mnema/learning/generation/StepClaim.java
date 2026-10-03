package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One claimed step: the fencing {@code token} that every result write must present, the claim's {@code attempt}
 * ({@code attempts} after the increment) and the deadline of this run.
 */
record StepClaim(UUID stepId, UUID sessionId, UUID artifactId, UUID ownerId, String kind, String capability, UUID token,
                 int attempt, Instant deadlineAt, JsonNode input) { }
