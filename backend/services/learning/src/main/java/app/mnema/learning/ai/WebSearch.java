package app.mnema.learning.ai;

import java.util.List;

/** Web search port for research steps (AI-18). Interface only. */
public interface WebSearch {
    AiResult<List<Result>> search(Request request);

    record Request(String query, int maxResults, List<String> domains) { }

    record Result(String url, String title, String snippet) { }
}
