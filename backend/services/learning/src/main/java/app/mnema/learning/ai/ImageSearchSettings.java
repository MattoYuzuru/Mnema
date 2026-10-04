package app.mnema.learning.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code learning.ai.image-search.*}: which sources are asked and in which order (the order is the interleaving order of the results), the
 * answer cache (Pixabay's terms require 24 hours; the same TTL serves every source), the User-Agent of the etiquette of the Wikimedia API and the
 * time boxes of one source call and one download. The sources' own settings (keys, base URLs, egress) are {@code learning.ai.providers.*}.
 */
@ConfigurationProperties("learning.ai.image-search")
public record ImageSearchSettings(@DefaultValue({"pixabay", "openverse", "wikimedia"}) List<String> sources,
                                  @DefaultValue("PT24H") Duration cacheTtl,
                                  @DefaultValue("Mnema/1.0 (https://github.com/MattoYuzuru/Mnema)") String userAgent,
                                  @DefaultValue("PT10S") Duration searchTimeout,
                                  @DefaultValue("PT20S") Duration fetchTimeout) {
    static final Set<String> KNOWN = Set.of("pixabay", "openverse", "wikimedia");

    @ConstructorBinding
    public ImageSearchSettings {
        sources = sources == null ? List.of() : sources.stream().map(value -> value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (!KNOWN.containsAll(sources)) throw new IllegalArgumentException("Unknown learning.ai.image-search.sources entry");
        if (cacheTtl == null || cacheTtl.compareTo(Duration.ofHours(24)) < 0 || cacheTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("learning.ai.image-search.cache-ttl must be between 24 hours and 7 days");
        }
        if (userAgent == null || userAgent.isBlank() || userAgent.length() > 200 || userAgent.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            throw new IllegalArgumentException("Invalid learning.ai.image-search.user-agent");
        }
        if (searchTimeout == null || searchTimeout.isNegative() || searchTimeout.isZero() || searchTimeout.compareTo(Duration.ofSeconds(60)) > 0
                || fetchTimeout == null || fetchTimeout.isNegative() || fetchTimeout.isZero() || fetchTimeout.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException("Invalid image search timeouts");
        }
    }

    /** Defaults, for code that builds the router without Spring binding. */
    static ImageSearchSettings defaults() {
        return new ImageSearchSettings(List.of("pixabay", "openverse", "wikimedia"), Duration.ofHours(24),
                "Mnema/1.0 (https://github.com/MattoYuzuru/Mnema)", Duration.ofSeconds(10), Duration.ofSeconds(20));
    }
}
