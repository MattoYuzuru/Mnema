package app.mnema.learning.notification;

import java.util.UUID;

/**
 * Counts the owner's background work that will produce notifications; the client polls faster while it is above zero.
 *
 * <p>TODO(AI-04, #287): generation sessions do not exist yet, so the only implementation reports zero. The generation
 * runtime must replace {@link NoActiveWork} with a count of the owner's sessions in {@code PLANNING} or {@code RUNNING}
 * (contracts/notifications README, "List envelope") and delete the placeholder. Whether media processing of generated
 * assets also counts is an open question of the contract.
 */
public interface ActiveWorkCounter {
    int count(UUID owner);
}
