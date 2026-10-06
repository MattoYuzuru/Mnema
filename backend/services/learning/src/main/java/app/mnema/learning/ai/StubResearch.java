package app.mnema.learning.ai;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stub's answer to a research planner request (the prompt carries {@code <task kind="research">}): as many queries as the cap in the task allows, all
 * about the topic of the request (the first note when the request names none), so the whole flow of «Проверять факты» runs without a provider. The
 * markers of {@link StubWebSearch} in the request are copied into every query, so a test can make the search fail or find nothing. Three markers of the
 * request exercise the planner itself: {@code [[stub:research-invalid]]} (the first answer is not the format, the repair is valid),
 * {@code [[stub:research-invalid-always]]} (every answer is) and {@code [[stub:research-over]]} (more queries than the cap, which the server must clamp).
 */
final class StubResearch {
    static final String TASK = "<task kind=\"research\">";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern REQUEST = Pattern.compile("(?m)^<request>(.*?)</request>$", Pattern.DOTALL);
    private static final Pattern NOTE = Pattern.compile("<note id=\"[^\"]*\">(.*?)</note>", Pattern.DOTALL);
    private static final Pattern CAP = Pattern.compile("Не больше запросов: (\\d{1,3})");
    private static final String[] ASPECTS = {"определение", "ключевые факты", "официальная документация", "примеры", "история", "типичные ошибки",
            "сравнение", "ограничения"};

    private StubResearch() { }

    static boolean isResearchRequest(String prompt) {
        return prompt.contains(TASK);
    }

    static String answer(String prompt, boolean repair) {
        if (prompt.contains("[[stub:research-invalid-always]]") || (!repair && prompt.contains("[[stub:research-invalid]]"))) return "{\"queries\":\"много\"}";
        Matcher cap = CAP.matcher(prompt);
        int count = cap.find() ? Integer.parseInt(cap.group(1)) : 1;
        if (prompt.contains("[[stub:research-over]]")) count += 3;
        Matcher found = REQUEST.matcher(prompt);
        String topic = found.find() ? found.group(1).strip() : "";
        if (topic.isBlank() || topic.equals("по источникам выше")) {
            Matcher note = NOTE.matcher(prompt);
            topic = note.find() ? note.group(1).strip() : "тема";
        }
        String markers = (prompt.contains(StubWebSearch.DOWN) ? " " + StubWebSearch.DOWN : "") + (prompt.contains(StubWebSearch.EMPTY) ? " " + StubWebSearch.EMPTY : "");
        topic = topic.replace(StubWebSearch.DOWN, "").replace(StubWebSearch.EMPTY, "").replaceAll("\\[\\[(?:stub|fake|t):[a-z-]+]]", "").replaceAll("\\s+", " ").strip();
        topic = topic.length() <= 60 ? topic : topic.substring(0, 60).strip();
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode queries = answer.putArray("queries");
        for (int index = 0; index < count; index++) {
            queries.add((topic + " " + ASPECTS[index % ASPECTS.length] + (index >= ASPECTS.length ? " " + (index / ASPECTS.length + 1) : "")).toLowerCase(Locale.ROOT)
                    + markers);
        }
        return answer.toString();
    }
}
