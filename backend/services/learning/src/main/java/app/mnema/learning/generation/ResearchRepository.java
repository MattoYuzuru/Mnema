package app.mnema.learning.generation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of what the RESEARCH step of an artifact found ({@code generation_research}, #299): the numbered results, as pointers (url, title, a snippet of at
 * most 300 characters, a date, the provider and the query that found them), and how many paid requests were made. Like the other repositories, a write runs
 * inside a service transaction that took the session lock first.
 */
@Repository
class ResearchRepository {
    /** The table bounds the document at 64 KiB: results are dropped from the end until it fits. */
    static final int MAX_DOCUMENT_BYTES = 65_536;

    /** One numbered result; {@code n} is its 1-based position, the number the material cites. */
    record Source(int n, String url, String title, String snippet, String date, String provider, int queryIndex) { }

    /** The research of one artifact: the paid requests and the results in {@code [n]} order. */
    record Research(int requests, List<Source> results) {
        Research {
            results = List.copyOf(results);
        }
    }

    private final JdbcClient jdbc;

    ResearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores (or replaces) the research of the artifact; the results are cut from the end to fit the table's bound. Returns what was stored. */
    Research upsert(UUID artifactId, UUID sessionId, UUID owner, int requests, List<Source> results) {
        List<Source> kept = new ArrayList<>(results);
        String document = write(kept);
        while (document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES && !kept.isEmpty()) {
            kept.removeLast();
            document = write(kept);
        }
        jdbc.sql("INSERT INTO app_learning.generation_research(artifact_id,session_id,owner_id,requests,results,created_at) VALUES (:artifact,:session,"
                        + ":owner,:requests,CAST(:results AS jsonb),clock_timestamp()) ON CONFLICT (artifact_id) DO UPDATE SET requests=EXCLUDED.requests,"
                        + "results=EXCLUDED.results,created_at=EXCLUDED.created_at")
                .param("artifact", artifactId).param("session", sessionId).param("owner", owner).param("requests", requests).param("results", document).update();
        return new Research(requests, kept);
    }

    Optional<Research> find(UUID artifactId) {
        return jdbc.sql("SELECT requests,results::text AS results FROM app_learning.generation_research WHERE artifact_id=:id").param("id", artifactId)
                .query((row, ignored) -> new Research(row.getInt("requests"), read(Json.read(row.getString("results"))))).optional();
    }

    /** The research of an artifact that is written again from scratch (a retry) goes before the new step makes its own. */
    void delete(UUID artifactId) {
        jdbc.sql("DELETE FROM app_learning.generation_research WHERE artifact_id=:id").param("id", artifactId).update();
    }

    private static String write(List<Source> results) {
        ArrayNode array = Json.array();
        for (Source source : results) {
            ObjectNode node = array.addObject().put("n", source.n()).put("url", source.url()).put("title", source.title())
                    .put("snippet", source.snippet());
            node.put("date", source.date());
            node.put("provider", source.provider()).put("queryIndex", source.queryIndex());
        }
        return Json.write(array);
    }

    private static List<Source> read(JsonNode array) {
        List<Source> results = new ArrayList<>();
        for (JsonNode node : array) {
            results.add(new Source(node.path("n").asInt(), node.path("url").stringValue(""), node.path("title").stringValue(""),
                    node.path("snippet").stringValue(""), node.path("date").stringValue(null), node.path("provider").stringValue(""),
                    node.path("queryIndex").asInt()));
        }
        return results;
    }
}
