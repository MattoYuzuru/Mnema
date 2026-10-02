package app.mnema.learning.ai;

import java.util.List;

/** Licensed stock image search port (AI-10). Interface only. */
public interface ImageSearch {
    AiResult<List<Candidate>> search(Request request);

    record Request(String query, int maxResults) { }

    record Candidate(String url, String license, String author, String sourcePage) { }
}
