package app.mnema.learning.media;

import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict v1 result boundary; worker output is rehashed before any object-store write. */
final class MediaWorkerResult {
    private static final int MAX_JSON_BYTES = 65_536;
    private static final long MAX_VARIANT_BYTES = 1L << 30;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> ROOT = Set.of("formatVersion", "assetId", "generation", "kind", "source", "variants");
    private static final Set<String> SOURCE = Set.of("sha256", "byteLength", "mimeType", "durationMs", "width", "height");
    private static final Set<String> VARIANT = Set.of("purpose", "profile", "path", "sha256",
            "byteLength", "mimeType", "durationMs", "width", "height");
    private static final Map<String, Profile> PROFILES = Map.of(
            "image_webp_2048_v1", new Profile("playback", "image/webp", "webp"),
            "image_webp_320_v1", new Profile("thumbnail", "image/webp", "webp"),
            "image_gif_2048_v1", new Profile("playback", "image/gif", "gif"),
            "image_gif_poster_webp_320_v1", new Profile("thumbnail", "image/webp", "webp"),
            "audio_aac_m4a_v1", new Profile("playback", "audio/mp4", "m4a"),
            "video_h264_aac_sdr_1080_v1", new Profile("playback", "video/mp4", "mp4"),
            "video_poster_webp_960_v1", new Profile("poster", "image/webp", "webp"));

    private MediaWorkerResult() { }

    static Verified read(Path output, UUID assetId, long generation, String kind,
                         long sourceLength, String sourceSha, long maxDurationMs) {
        try {
            Path manifest = output.resolve("result.json");
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(manifest) > MAX_JSON_BYTES) throw invalid();
            JsonNode root = new ContentJsonReader(MAX_JSON_BYTES, 8, 200)
                    .read(Files.readAllBytes(manifest));
            if (!fields(root, ROOT) || !integer(root.path("formatVersion"), 1, 1)
                    || !assetId.toString().equals(root.path("assetId").textValue())
                    || !integer(root.path("generation"), generation, generation)
                    || !kind.equals(root.path("kind").textValue())
                    || !fields(root.path("source"), SOURCE)
                    || !root.path("variants").isArray()) throw invalid();
            JsonNode source = root.path("source");
            String digest = sha(source.path("sha256"));
            if (!digest.equals(sourceSha) || !integer(source.path("byteLength"), sourceLength, sourceLength)) {
                throw invalid();
            }
            String sourceMime = text(source.path("mimeType"));
            if (!sourceMimeAllowed(kind, sourceMime)) throw invalid();
            long durationCeiling = kind.equals("image") ? 60_050 : maxDurationMs + 50;
            Long sourceDuration = nullableLong(source.path("durationMs"), durationCeiling);
            if (!kind.equals("image") && sourceDuration == null) throw invalid();
            Integer sourceWidth = nullableInt(source.path("width"));
            Integer sourceHeight = nullableInt(source.path("height"));
            dimensions(kind.equals("audio"), sourceWidth, sourceHeight);
            Set<String> expected = expected(kind, sourceMime);
            if (root.path("variants").size() != expected.size()) throw invalid();
            var verified = new ArrayList<Variant>(expected.size());
            var seen = new HashSet<String>();
            long totalBytes = 0;
            for (JsonNode item : root.path("variants")) {
                if (!fields(item, VARIANT)) throw invalid();
                String name = text(item.path("profile"));
                Profile profile = PROFILES.get(name);
                if (profile == null || !expected.contains(name) || !seen.add(name)
                        || !profile.purpose().equals(item.path("purpose").textValue())
                        || !profile.mimeType().equals(item.path("mimeType").textValue())
                        || !(name + "." + profile.extension()).equals(item.path("path").textValue())) {
                    throw invalid();
                }
                long length = number(item.path("byteLength"), 1, MAX_VARIANT_BYTES - 1);
                totalBytes += length;
                if (totalBytes > MAX_VARIANT_BYTES) throw invalid();
                String hash = sha(item.path("sha256"));
                Long duration = nullableLong(item.path("durationMs"), durationCeiling);
                Integer width = nullableInt(item.path("width"));
                Integer height = nullableInt(item.path("height"));
                dimensions(profile.mimeType().equals("audio/mp4"), width, height);
                Path path = output.resolve(name + "." + profile.extension());
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(path) != length || !hash(path).equals(hash)) throw invalid();
                verified.add(new Variant(profile.purpose(), name, profile.mimeType(), path,
                        hash, length, duration, width, height));
            }
            if (!seen.equals(expected)) throw invalid();
            return new Verified(new Source(digest, sourceLength, sourceMime, sourceDuration,
                    sourceWidth, sourceHeight), List.copyOf(verified));
        } catch (IOException | IllegalArgumentException failure) {
            // Never include paths, parser source, object keys or user-provided metadata.
            throw invalid();
        }
    }

    private static Set<String> expected(String kind, String mime) {
        return switch (kind) {
            case "image" -> mime.equals("image/gif")
                    ? Set.of("image_gif_2048_v1", "image_gif_poster_webp_320_v1")
                    : Set.of("image_webp_2048_v1", "image_webp_320_v1");
            case "audio" -> Set.of("audio_aac_m4a_v1");
            case "video" -> Set.of("video_h264_aac_sdr_1080_v1", "video_poster_webp_960_v1");
            default -> throw invalid();
        };
    }

    private static boolean sourceMimeAllowed(String kind, String mime) {
        return switch (kind) {
            case "image" -> Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(mime);
            case "audio" -> Set.of("audio/mpeg", "audio/mp4", "audio/webm").contains(mime);
            case "video" -> Set.of("video/mp4", "video/quicktime", "video/webm").contains(mime);
            default -> false;
        };
    }

    private static boolean fields(JsonNode node, Set<String> fields) {
        return node.isObject() && node.size() == fields.size()
                && node.properties().stream().allMatch(entry -> fields.contains(entry.getKey()));
    }

    private static String text(JsonNode node) {
        if (!node.isTextual()) throw invalid();
        return node.textValue();
    }

    private static String sha(JsonNode node) {
        String value = text(node);
        if (!SHA256.matcher(value).matches()) throw invalid();
        return value;
    }

    private static boolean integer(JsonNode node, long minimum, long maximum) {
        return node.isIntegralNumber() && node.canConvertToLong()
                && node.longValue() >= minimum && node.longValue() <= maximum;
    }

    private static long number(JsonNode node, long minimum, long maximum) {
        if (!integer(node, minimum, maximum)) throw invalid();
        return node.longValue();
    }

    private static Long nullableLong(JsonNode node, long maximum) {
        return node.isNull() ? null : number(node, 0, maximum);
    }

    private static Integer nullableInt(JsonNode node) {
        return node.isNull() ? null : Math.toIntExact(number(node, 1, 32_768));
    }

    private static void dimensions(boolean audio, Integer width, Integer height) {
        if ((width == null) != (height == null) || (audio && width != null) || (!audio && width == null)) {
            throw invalid();
        }
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid media worker result");
    }

    record Verified(Source source, List<Variant> variants) { }
    record Source(String sha256, long byteLength, String mimeType, Long durationMs,
                  Integer width, Integer height) { }
    record Variant(String purpose, String profile, String mimeType, Path path, String sha256,
                   long byteLength, Long durationMs, Integer width, Integer height) { }
    private record Profile(String purpose, String mimeType, String extension) { }
}
