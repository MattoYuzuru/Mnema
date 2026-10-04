package app.mnema.learning.ai;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Licensed stock image search port (AI-10, #296): finds candidate images whose license allows commercial use and downloads the
 * chosen ones through a safe fetcher. Candidates are never hot-linked: the caller stages the bytes as the owner's own media asset.
 *
 * <p>Implementations never throw for provider problems; a source that fails is not a failure of the search while another source
 * answers. No method here logs or returns a key, a token or a provider message.
 */
public interface ImageSearch {
    /**
     * @return the licensed candidates in display order (best first, the sources interleaved), possibly empty when nothing licensed was
     *         found; {@code Failed} only when every configured source failed
     */
    AiResult<List<Candidate>> search(Request request);

    /** Downloads the bytes of {@code candidate} (https, allowlisted host, at most 10 MiB, verified image type). */
    AiResult<Image> fetch(Candidate candidate);

    /** Whether at least one source can be called now (a key or credentials present, the Stub selected). */
    default boolean configured() { return true; }

    /**
     * @param lang the script of the query for sources that take a language ({@code ru}, {@code ko}, {@code ja}, {@code zh}, else {@code en})
     * @param excludeKeys {@link Candidate#key()} values that are already known (dropped from the answer)
     * @param stepId the generation step for the call journal, null outside a step
     */
    record Request(String query, String lang, int maxResults, Set<String> excludeKeys, UUID stepId, int attempt) {
        public Request {
            query = query == null ? "" : query.strip();
            lang = lang == null || lang.isBlank() ? "en" : lang;
            excludeKeys = excludeKeys == null ? Set.of() : Set.copyOf(excludeKeys);
            if (maxResults < 1 || maxResults > 50) throw new IllegalArgumentException("Invalid maxResults");
            attempt = Math.max(1, attempt);
        }
    }

    /** The sources a candidate can come from; the name is the wire value of {@code candidates[].source}. */
    enum Source {
        PIXABAY("Pixabay"), OPENVERSE("Openverse"), WIKIMEDIA("Wikimedia Commons"), STUB("Тестовый источник");

        private final String label;

        Source(String label) { this.label = label; }

        /** The name of the source as an attribution shows it. */
        public String label() { return label; }
    }

    /**
     * One licensed result. {@code downloadUrl} is only for {@link #fetch}; it is not part of {@link #toString} (URLs of some sources carry
     * tokens). Text members are stripped to plain text and bounded: author 200, title 300.
     *
     * @param licenseUrl https, or null
     * @param sourcePageUrl https page of the image at its source
     * @param shareAlike the license is BY-SA: the client marks it
     */
    record Candidate(Source source, String sourceId, String title, String author, String license, String licenseUrl,
                     String sourcePageUrl, boolean shareAlike, int width, int height, String downloadUrl) {
        /** {@code SOURCE:sourceId}, the identity used for de-duplication and for excluding known results. */
        public String key() { return source.name() + ":" + sourceId; }

        @Override
        public String toString() {
            return "Candidate[" + key() + ", license=" + license + ", " + width + "x" + height + "]";
        }
    }

    /** The downloaded bytes and their verified type. */
    record Image(byte[] bytes, String mimeType) {
        @Override
        public String toString() { return "Image[" + mimeType + ", " + bytes.length + " bytes]"; }
    }
}
