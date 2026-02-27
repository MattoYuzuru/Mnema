package app.mnema.ai.service;

import app.mnema.ai.client.core.CoreApiClient;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardPage;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
public class CardNoveltyService {

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final int PAGE_SIZE = 200;
    private static final int MAX_INDEX_CARDS = 3000;
    private static final int VECTOR_DIM = 256;
    private static final double SEMANTIC_SIMILARITY_THRESHOLD = 0.92d;

    private final CoreApiClient coreApiClient;

    public CardNoveltyService(CoreApiClient coreApiClient) {
        this.coreApiClient = coreApiClient;
    }

    public NoveltyIndex buildIndex(UUID deckId, String accessToken, List<String> fields) {
        NoveltyIndex index = new NoveltyIndex();
        if (deckId == null || accessToken == null || accessToken.isBlank() || fields == null || fields.isEmpty()) {
            return index;
        }

        int page = 1;
        int loaded = 0;
        while (loaded < MAX_INDEX_CARDS) {
            CoreUserCardPage cardPage = coreApiClient.getUserCards(deckId, page, PAGE_SIZE, accessToken);
            if (cardPage == null || cardPage.content() == null || cardPage.content().isEmpty()) {
                break;
            }

            for (CoreUserCardResponse card : cardPage.content()) {
                if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                    continue;
                }
                Fingerprint fingerprint = fingerprint(card.effectiveContent(), fields);
                if (fingerprint == null) {
                    continue;
                }
                index.add(fingerprint);
                loaded++;
                if (loaded >= MAX_INDEX_CARDS) {
                    break;
                }
            }

            if (cardPage.content().size() < PAGE_SIZE) {
                break;
            }
            page++;
        }

        return index;
    }

    public <T> FilterResult<T> filterCandidates(List<T> candidates,
                                                Function<T, JsonNode> contentExtractor,
                                                List<String> fields,
                                                NoveltyIndex index,
                                                int limit) {
        if (candidates == null || candidates.isEmpty() || contentExtractor == null || fields == null || fields.isEmpty() || limit <= 0) {
            return new FilterResult<>(List.of(), 0, 0, 0, 0);
        }
        NoveltyIndex safeIndex = index == null ? new NoveltyIndex() : index;

        List<T> accepted = new ArrayList<>();
        int droppedEmpty = 0;
        int droppedExact = 0;
        int droppedPrimary = 0;
        int droppedSemantic = 0;

        for (T candidate : candidates) {
            if (accepted.size() >= limit) {
                break;
            }
            JsonNode content = contentExtractor.apply(candidate);
            Fingerprint fp = fingerprint(content, fields);
            if (fp == null) {
                droppedEmpty++;
                continue;
            }
            if (safeIndex.containsExact(fp.exactKey())) {
                droppedExact++;
                continue;
            }
            if (fp.primaryKey() != null && safeIndex.containsPrimary(fp.primaryKey())) {
                droppedPrimary++;
                continue;
            }
            if (safeIndex.hasSemanticMatch(fp.vector(), fp.primaryKey())) {
                droppedSemantic++;
                continue;
            }

            safeIndex.add(fp);
            accepted.add(candidate);
        }

        return new FilterResult<>(List.copyOf(accepted), droppedEmpty, droppedExact, droppedPrimary, droppedSemantic);
    }

    public List<String> buildAvoidSnippets(NoveltyIndex index, int limit) {
        if (index == null || limit <= 0) {
            return List.of();
        }
        return index.examples(limit);
    }

    private Fingerprint fingerprint(JsonNode content, List<String> fields) {
        if (content == null || !content.isObject() || fields == null || fields.isEmpty()) {
            return null;
        }

        List<String> values = new ArrayList<>(fields.size());
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                values.add("");
                continue;
            }
            JsonNode node = content.get(field);
            values.add(normalize(nodeToText(node)));
        }

        boolean hasAny = values.stream().anyMatch(value -> value != null && !value.isBlank());
        if (!hasAny) {
            return null;
        }

        String exactKey = String.join("\u001f", values);
        String primaryKey = resolvePrimary(values);
        String combined = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse("");
        float[] vector = vectorize(combined);
        String example = combined.length() > 120 ? combined.substring(0, 120) : combined;
        return new Fingerprint(exactKey, primaryKey, vector, example);
    }

    private String resolvePrimary(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String first = values.getFirst();
        if (first != null && !first.isBlank()) {
            return first;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String nodeToText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText("");
        }
        return "";
    }

    private String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String lowered = input.toLowerCase(Locale.ROOT);
        String compact = NON_ALNUM.matcher(lowered).replaceAll(" ").trim();
        if (compact.isBlank()) {
            return "";
        }
        return compact.replaceAll("\\s+", " ");
    }

    private float[] vectorize(String normalized) {
        float[] vector = new float[VECTOR_DIM];
        if (normalized == null || normalized.isBlank()) {
            return vector;
        }

        String text = " " + normalized + " ";
        if (text.length() < 3) {
            int idx = Math.floorMod(text.hashCode(), VECTOR_DIM);
            vector[idx] += 1f;
            return normalizeVector(vector);
        }

        for (int i = 0; i <= text.length() - 3; i++) {
            String gram = text.substring(i, i + 3);
            int hash = gram.hashCode();
            int idx = Math.floorMod(hash, VECTOR_DIM);
            float sign = ((hash >>> 31) == 0) ? 1f : -1f;
            vector[idx] += sign;
        }

        return normalizeVector(vector);
    }

    private float[] normalizeVector(float[] vector) {
        double norm = 0d;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm <= 0d) {
            return vector;
        }
        float inv = (float) (1d / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] * inv;
        }
        return vector;
    }

    private record Fingerprint(String exactKey, String primaryKey, float[] vector, String example) {
    }

    public record FilterResult<T>(
            List<T> accepted,
            int droppedEmpty,
            int droppedExact,
            int droppedPrimary,
            int droppedSemantic
    ) {
    }

    public static final class NoveltyIndex {
        private final Set<String> exactKeys = new HashSet<>();
        private final Set<String> primaryKeys = new HashSet<>();
        private final List<float[]> vectors = new ArrayList<>();
        private final List<String> vectorPrimary = new ArrayList<>();
        private final List<String> examples = new ArrayList<>();
        private final Map<String, List<Integer>> primaryBuckets = new HashMap<>();

        private void add(Fingerprint fp) {
            if (fp == null) {
                return;
            }
            exactKeys.add(fp.exactKey());
            if (fp.primaryKey() != null && !fp.primaryKey().isBlank()) {
                primaryKeys.add(fp.primaryKey());
            }
            int idx = vectors.size();
            vectors.add(fp.vector());
            vectorPrimary.add(fp.primaryKey());
            if (fp.primaryKey() != null && !fp.primaryKey().isBlank()) {
                primaryBuckets.computeIfAbsent(fp.primaryKey(), __ -> new ArrayList<>()).add(idx);
            }
            if (fp.example() != null && !fp.example().isBlank() && examples.size() < 512) {
                examples.add(fp.example());
            }
        }

        private boolean containsExact(String exactKey) {
            return exactKey != null && !exactKey.isBlank() && exactKeys.contains(exactKey);
        }

        private boolean containsPrimary(String primaryKey) {
            return primaryKey != null && !primaryKey.isBlank() && primaryKeys.contains(primaryKey);
        }

        private boolean hasSemanticMatch(float[] vector, String primaryKey) {
            if (vector == null || vector.length == 0 || vectors.isEmpty()) {
                return false;
            }

            List<Integer> candidates = null;
            if (primaryKey != null && !primaryKey.isBlank()) {
                candidates = primaryBuckets.get(primaryKey);
            }

            if (candidates != null && !candidates.isEmpty()) {
                for (Integer idx : candidates) {
                    if (idx == null || idx < 0 || idx >= vectors.size()) {
                        continue;
                    }
                    if (cosine(vectors.get(idx), vector) >= SEMANTIC_SIMILARITY_THRESHOLD) {
                        return true;
                    }
                }
                return false;
            }

            for (float[] existing : vectors) {
                if (cosine(existing, vector) >= SEMANTIC_SIMILARITY_THRESHOLD) {
                    return true;
                }
            }
            return false;
        }

        private float cosine(float[] a, float[] b) {
            int len = Math.min(a.length, b.length);
            float dot = 0f;
            for (int i = 0; i < len; i++) {
                dot += a[i] * b[i];
            }
            return dot;
        }

        private List<String> examples(int limit) {
            if (examples.isEmpty()) {
                return List.of();
            }
            int safeLimit = Math.min(limit, examples.size());
            return List.copyOf(examples.subList(0, safeLimit));
        }
    }
}
