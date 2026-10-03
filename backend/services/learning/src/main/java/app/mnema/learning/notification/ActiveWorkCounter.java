package app.mnema.learning.notification;

import java.util.UUID;

/**
 * Counts the owner's background work that will produce notifications; the client polls faster while it is above zero.
 * The generation module implements it with the owner's sessions in {@code PLANNING} or {@code RUNNING}
 * ({@code contracts/notifications} README, "List envelope"). Whether media processing of generated assets also counts is an
 * open question of the contract.
 */
public interface ActiveWorkCounter {
    int count(UUID owner);
}
