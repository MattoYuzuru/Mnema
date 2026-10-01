package app.mnema.learning.catalog.exercise;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The one place that decides whether a set of {@code source -> target} links is a complete, well-formed mapping
 * between two identifier sets. It serves authored keys and learner responses alike.
 *
 * <p>Two rule sets share the same totality core (every source appears exactly once, nothing is foreign) and are
 * deliberately kept apart: {@link #bijection} is MATCH (every target is used exactly once, so pairs never
 * collapse) and {@link #totalManyToOne} is CATEGORIZE (targets may repeat or stay unused, e.g. a distractor
 * category). Neither rule set implies the other.
 */
public final class MappingRules {
    private MappingRules() { }

    /** One directed link, e.g. a left item to its partner or an item to its category. */
    public record Link(UUID source, UUID target) { }

    /** MATCH: exact one-to-one correspondence of {@code sources} and {@code targets}. */
    public static boolean bijection(Collection<Link> links, Set<UUID> sources, Set<UUID> targets) {
        return total(links, sources, targets) && usedTargets(links).equals(targets);
    }

    /** CATEGORIZE: every source maps to exactly one existing target; targets may repeat or stay empty. */
    public static boolean totalManyToOne(Collection<Link> links, Set<UUID> sources, Set<UUID> targets) {
        return total(links, sources, targets);
    }

    /** Each source appears exactly once and every target is one of {@code targets}. */
    private static boolean total(Collection<Link> links, Set<UUID> sources, Set<UUID> targets) {
        Set<UUID> seen = new HashSet<>();
        for (Link link : links) {
            if (!sources.contains(link.source()) || !targets.contains(link.target()) || !seen.add(link.source())) {
                return false;
            }
        }
        return seen.size() == sources.size();
    }

    private static Set<UUID> usedTargets(Collection<Link> links) {
        Set<UUID> used = new HashSet<>();
        links.forEach(link -> used.add(link.target()));
        return used;
    }
}
