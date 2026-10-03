package app.mnema.learning.generation;

import app.mnema.learning.platform.api.ProblemExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An owned pinned note or material exists but no longer matches its pin: {@code 409 SOURCE_UNAVAILABLE}. The problem
 * lists the client's own identifiers only, never titles or text; an unknown or foreign source is a 404 instead.
 */
public final class SourceUnavailableException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    /** @param type {@code NOTE} or {@code ITEM} */
    public record Unavailable(String type, UUID id) { }

    private final transient List<Unavailable> sources;

    public SourceUnavailableException(List<Unavailable> sources) {
        super("Source unavailable", null, false, false);
        this.sources = List.copyOf(sources);
    }

    @Override
    public ProblemExtension extension() {
        return ProblemExtension.builder().put("sources", sources.stream()
                .map(source -> Map.of("type", source.type(), "id", source.id().toString())).toList()).build();
    }
}
