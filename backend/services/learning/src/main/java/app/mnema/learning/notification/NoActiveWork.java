package app.mnema.learning.notification;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Placeholder until AI-04 (#287) introduces generation sessions; see {@link ActiveWorkCounter}. */
@Component
final class NoActiveWork implements ActiveWorkCounter {
    @Override
    public int count(UUID owner) { return 0; }
}
