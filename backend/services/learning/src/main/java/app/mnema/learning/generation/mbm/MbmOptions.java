package app.mnema.learning.generation.mbm;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Compile options, see {@code contracts/generation/mbm-v1/README.md}. Misuse of the options (a negative count, a
 * malformed handle) is a programming error and throws {@link IllegalArgumentException}; it is never a model error.
 *
 * @param mode {@code CREATE} (default) or {@code EDIT}
 * @param allowedLinks session link allowlist (RESEARCH results and URLs of the user's notes)
 * @param capabilities the {@code /api/capabilities} switches of the session
 * @param research numbered RESEARCH results; consulted only by {@code ::sources}
 * @param maxMedia media directives allowed for the artifact, 0..8
 * @param existingMediaCount EDIT only (ignored in CREATE): media nodes of the artifact outside the range
 * @param handles EDIT only: top-level blocks of the edited range by handle
 * @param existingSlotKeys EDIT only: slot keys of the artifact outside the range
 * @param sourcesHeading heading text that {@code ::sources} emits (sanitized, at most 200 UTF-16 units; default
 *                       {@code Sources})
 */
public record MbmOptions(Mode mode, List<String> allowedLinks, Capabilities capabilities, List<ResearchSource> research,
                         int maxMedia, int existingMediaCount, Map<String, Handle> handles,
                         Set<String> existingSlotKeys, String sourcesHeading) {

    /** The hard ceiling of media directives per artifact. */
    public static final int MEDIA_CEILING = 8;
    /** Research titles are cut to this many UTF-16 units when they become link labels of {@code ::sources}. */
    public static final int MAX_RESEARCH_TITLE = 1_024;
    /** The sources heading is cut to this many UTF-16 units. */
    public static final int MAX_SOURCES_HEADING = 200;
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z][0-9]+");

    /** Whether the source is a whole new document or a range of an existing artifact. */
    public enum Mode { CREATE, EDIT }

    /** The generation capabilities of the session. */
    public record Capabilities(boolean videoGeneration, boolean imageGeneration) {
    }

    /** A numbered RESEARCH result. */
    public record ResearchSource(int n, String url, String title) {

        public ResearchSource {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(title, "title");
        }
    }

    /** A top-level block of the edited range: its node ID and its native type. */
    public record Handle(UUID nodeId, String type) {

        public Handle {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(type, "type");
            if (nodeId.version() != 4 || nodeId.variant() != 2) {
                throw new IllegalArgumentException("handle nodeId must be a UUIDv4");
            }
        }
    }

    public MbmOptions {
        mode = mode == null ? Mode.CREATE : mode;
        allowedLinks = allowedLinks == null ? List.of() : List.copyOf(allowedLinks);
        capabilities = capabilities == null ? new Capabilities(false, false) : capabilities;
        research = research == null ? List.of() : List.copyOf(research);
        if (maxMedia < 0) {
            throw new IllegalArgumentException("maxMedia must not be negative");
        }
        maxMedia = Math.min(maxMedia, MEDIA_CEILING);
        if (existingMediaCount < 0) {
            throw new IllegalArgumentException("existingMediaCount must not be negative");
        }
        handles = handles == null ? Map.of() : validatedHandles(handles);
        existingSlotKeys = existingSlotKeys == null ? Set.of() : Set.copyOf(existingSlotKeys);
        String heading = sourcesHeading == null ? "" : MbmCompiler.sanitizeText(sourcesHeading, MAX_SOURCES_HEADING);
        sourcesHeading = heading.isEmpty() ? "Sources" : heading;
    }

    /** A new document with no links, no capabilities and the default media bound. */
    public static MbmOptions create() {
        return new MbmOptions(Mode.CREATE, null, null, null, MEDIA_CEILING, 0, null, null, null);
    }

    /** An edit of the range whose top-level blocks are {@code handles}. */
    public static MbmOptions edit(Map<String, Handle> handles) {
        return new MbmOptions(Mode.EDIT, null, null, null, MEDIA_CEILING, 0, handles, null, null);
    }

    public MbmOptions withAllowedLinks(List<String> value) {
        return new MbmOptions(mode, value, capabilities, research, maxMedia, existingMediaCount, handles,
                existingSlotKeys, sourcesHeading);
    }

    public MbmOptions withCapabilities(Capabilities value) {
        return new MbmOptions(mode, allowedLinks, value, research, maxMedia, existingMediaCount, handles,
                existingSlotKeys, sourcesHeading);
    }

    public MbmOptions withResearch(List<ResearchSource> value) {
        return new MbmOptions(mode, allowedLinks, capabilities, value, maxMedia, existingMediaCount, handles,
                existingSlotKeys, sourcesHeading);
    }

    public MbmOptions withMaxMedia(int value) {
        return new MbmOptions(mode, allowedLinks, capabilities, research, value, existingMediaCount, handles,
                existingSlotKeys, sourcesHeading);
    }

    public MbmOptions withExistingMediaCount(int value) {
        return new MbmOptions(mode, allowedLinks, capabilities, research, maxMedia, value, handles,
                existingSlotKeys, sourcesHeading);
    }

    public MbmOptions withExistingSlotKeys(Set<String> value) {
        return new MbmOptions(mode, allowedLinks, capabilities, research, maxMedia, existingMediaCount, handles,
                value, sourcesHeading);
    }

    public MbmOptions withSourcesHeading(String value) {
        return new MbmOptions(mode, allowedLinks, capabilities, research, maxMedia, existingMediaCount, handles,
                existingSlotKeys, value);
    }

    private static Map<String, Handle> validatedHandles(Map<String, Handle> source) {
        var copy = new LinkedHashMap<String, Handle>();
        var ids = new LinkedHashSet<UUID>();
        source.forEach((key, handle) -> {
            if (key == null || !HANDLE.matcher(key).matches()) {
                throw new IllegalArgumentException("handle must be a letter followed by digits");
            }
            if (!ids.add(Objects.requireNonNull(handle, "handle").nodeId())) {
                throw new IllegalArgumentException("two handles name the same node");
            }
            copy.put(key, handle);
        });
        return java.util.Collections.unmodifiableMap(copy);
    }
}
