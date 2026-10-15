package app.mnema.learning.community;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The independent Recs switches. Retention maintenance is deliberately independent of both. */
@Component
final class RecommendationSettings {
    final boolean eventsEnabled;
    final boolean forYouEnabled;
    RecommendationSettings(@Value("${learning.community.events.enabled:false}") boolean eventsEnabled,
                @Value("${learning.community.for-you.enabled:false}") boolean forYouEnabled) {
        this.eventsEnabled = eventsEnabled;
        this.forYouEnabled = forYouEnabled;
    }
}
