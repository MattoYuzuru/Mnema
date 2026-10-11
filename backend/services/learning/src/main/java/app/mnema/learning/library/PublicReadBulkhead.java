package app.mnema.learning.library;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * The bulkhead shared by every public route that holds a database connection (reads of a deck and its media): {@code max-concurrent} places in all, of
 * which guests may hold at most one less, so a place is always free for a signed-in viewer. {@code tryAcquire} never waits: the excess request is refused
 * at once, before any transaction or connection, and never queues for the pool. One bulkhead for all public routes keeps the connection budget
 * ({@link PublicRouteConnectionBudget}) a single number.
 */
@Component
final class PublicReadBulkhead {
    private final Semaphore all;
    private final Semaphore guests;

    PublicReadBulkhead(PublicRouteSettings settings) {
        this.all = new Semaphore(settings.maxConcurrent);
        this.guests = new Semaphore(settings.maxConcurrent - 1);
    }

    /** Takes a place (a guest also needs one of the guest share); {@code false} when none is free. */
    boolean tryAcquire(boolean guest) {
        if (guest && !guests.tryAcquire()) return false;
        if (all.tryAcquire()) return true;
        if (guest) guests.release();
        return false;
    }

    /** Gives back a place taken by {@link #tryAcquire}. */
    void release(boolean guest) {
        all.release();
        if (guest) guests.release();
    }
}
