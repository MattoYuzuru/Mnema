/**
 * How a copy that is being prepared is watched: the deck is read again after 1.5 s, 2 s, 3 s, 5 s, 8 s and then every 10 s, at most
 * {@link FORK_POLL_LIMIT} times (about 25 minutes). After that the page stops asking and offers «Проверить снова». There is no push
 * and no progress figure on the server, only the flags of `fork`; polling stops the moment `preparing` is false.
 */
export const FORK_POLL_STEPS_MS: readonly number[] = [1500, 2000, 3000, 5000, 8000];
export const FORK_POLL_CAP_MS = 10_000;
export const FORK_POLL_LIMIT = 150;

/** The wait before the check number `attempt` (0 is the first). */
export function forkPollDelay(attempt: number): number {
    return FORK_POLL_STEPS_MS[Math.min(Math.max(0, Math.trunc(attempt)), FORK_POLL_STEPS_MS.length)] ?? FORK_POLL_CAP_MS;
}
