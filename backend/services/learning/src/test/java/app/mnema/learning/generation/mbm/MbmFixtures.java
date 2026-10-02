package app.mnema.learning.generation.mbm;

import app.mnema.learning.platform.json.CanonicalJsonHasher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Loads {@code contracts/generation/mbm-v1} fixtures and the deterministic allocator of the golden IDs. */
final class MbmFixtures {

    static final Path MBM = repositoryRoot().resolve("contracts/generation/mbm-v1");
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CanonicalJsonHasher CANONICAL = new CanonicalJsonHasher();

    private MbmFixtures() {
    }

    /** Case names of {@code valid/} or {@code invalid/}, sorted. */
    static List<String> cases(String directory) throws IOException {
        try (Stream<Path> files = Files.list(MBM.resolve(directory))) {
            return files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".mbm"))
                    .map(name -> name.substring(0, name.length() - ".mbm".length())).sorted().toList();
        }
    }

    static String source(String directory, String name) throws IOException {
        return Files.readString(MBM.resolve(directory + "/" + name + ".mbm"), StandardCharsets.UTF_8);
    }

    static JsonNode json(String directory, String name, String suffix) throws IOException {
        return JSON.readTree(Files.readString(MBM.resolve(directory + "/" + name + suffix), StandardCharsets.UTF_8));
    }

    static byte[] canonical(JsonNode node) {
        return CANONICAL.canonicalBytes(node);
    }

    /** The {@code input} object of a meta file as compile options. */
    static MbmOptions options(JsonNode meta) {
        JsonNode input = meta.path("input");
        List<String> links = new ArrayList<>();
        input.path("allowedLinks").forEach(link -> links.add(link.stringValue()));
        List<MbmOptions.ResearchSource> research = new ArrayList<>();
        input.path("research").forEach(entry -> research.add(new MbmOptions.ResearchSource(
                entry.path("n").intValue(), entry.path("url").stringValue(), entry.path("title").stringValue())));
        Map<String, MbmOptions.Handle> handles = new LinkedHashMap<>();
        input.path("handles").properties().forEach(handle -> handles.put(handle.getKey(), new MbmOptions.Handle(
                UUID.fromString(handle.getValue().path("nodeId").stringValue()),
                handle.getValue().path("type").stringValue())));
        java.util.Set<String> slotKeys = new java.util.LinkedHashSet<>();
        input.path("existingSlotKeys").forEach(key -> slotKeys.add(key.stringValue()));
        return new MbmOptions(
                MbmOptions.Mode.valueOf(input.path("mode").stringValue("CREATE")), links,
                new MbmOptions.Capabilities(input.path("capabilities").path("videoGeneration").booleanValue(false),
                        input.path("capabilities").path("imageGeneration").booleanValue(false)),
                research, input.path("maxMedia").intValue(MbmOptions.MEDIA_CEILING),
                input.path("existingMediaCount").intValue(0), handles, slotKeys,
                input.path("sourcesHeading").stringValue(null));
    }

    /** Sequential allocator of the contract's golden IDs. */
    static class SequentialIds implements IdAllocator {
        private long node;
        private long asset;

        @Override
        public UUID nextNodeId() {
            return UUID.fromString(String.format("00000000-0000-4000-8000-%012x", ++node));
        }

        @Override
        public UUID nextAssetId() {
            return UUID.fromString(String.format("00000000-0000-4000-a000-%012x", ++asset));
        }
    }

    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/mbm-v1/codes.json"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Cannot find repository root");
        }
        return root;
    }
}
