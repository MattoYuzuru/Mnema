package app.mnema.learning.update.plan;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * The bulkhead of the plan and unit routes: {@code learning.updates.plan.max-concurrent} places per instance. A plan holds one pooled connection for up to its transaction, so a flood
 * of them must not queue for the pool the private API shares. {@code tryAcquire} never waits: the excess request is refused at once, before any transaction or connection.
 */
@Component
public final class UpdatePlanBulkhead {
    private final Semaphore places;

    UpdatePlanBulkhead(UpdatePlanSettings settings) { this.places = new Semaphore(settings.maxConcurrent); }

    public boolean tryAcquire() { return places.tryAcquire(); }

    public void release() { places.release(); }
}
