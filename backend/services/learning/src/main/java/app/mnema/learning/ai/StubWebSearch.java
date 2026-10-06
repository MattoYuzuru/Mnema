package app.mnema.learning.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic web search for local runs and CI: no network, no key. It exists only with {@code learning.ai.provider=stub}. Every query gets three
 * results on {@code https://example.org/stub/research/<n>} (the number runs on over the queries of a request, so the results of one request never
 * repeat) with Russian titles; every query is one paid request of cost zero. Two markers in a query simulate failures:
 * {@code [[stub:search-down]]} (every provider is down) and {@code [[stub:search-empty]]} (nothing found).
 */
final class StubWebSearch implements WebSearch {
    static final String DOWN = "[[stub:search-down]]";
    static final String EMPTY = "[[stub:search-empty]]";
    static final int PER_QUERY = 3;

    @Override
    public AiResult<Answer> search(Request request) {
        if (request.queries().stream().anyMatch(query -> query.contains(DOWN))) return AiResult.failed(new AiFailure.Transient("stub_down"));
        List<Result> results = new ArrayList<>();
        int number = 0;
        for (int query = 0; query < request.queries().size(); query++) {
            boolean empty = request.queries().get(query).contains(EMPTY);
            for (int rank = 1; rank <= PER_QUERY && rank <= request.maxResults(); rank++) {
                number++;
                if (empty) continue;
                results.add(new Result("https://example.org/stub/research/" + number, "Источник " + number + ": " + clip(request.queries().get(query)),
                        "Пример выдачи поиска для запроса «" + clip(request.queries().get(query)) + "», результат " + rank + ".", "2026-01-01",
                        Provider.STUB, query, rank));
            }
        }
        return AiResult.ok(new Answer(results, request.queries().size(), 0));
    }

    private static String clip(String query) {
        String text = query.replace(DOWN, "").replace(EMPTY, "").strip();
        return text.length() <= 60 ? text : text.substring(0, 60).strip();
    }
}
