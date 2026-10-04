package app.mnema.learning.ai;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stub's answer to a plan request (the prompt carries {@code <task kind="plan">}, {@code ai/prompts/v1/plan.md}), deterministic, so the
 * whole planner runs without a provider.
 *
 * <ul>
 *   <li>{@code Вид: EXERCISES}: one item per target of {@code <targets>} (the lines {@code m1 · title · ...}), the allowed mechanics of the
 *       line {@code Механики: A, B, C.} round-robin (two mechanics per item when there are at least two), the count of the line
 *       {@code На материал: N.} or 3;</li>
 *   <li>{@code Вид: MATERIALS}: one item per {@code <note id="n1">} (the title is the first words of the note, {@code MEDIUM}), or three
 *       items without a source when there are no notes.</li>
 * </ul>
 * Two markers in the prompt exercise the repair: {@code [[stub:plan-invalid]]} (the first answer is not a plan, the repair is valid) and
 * {@code [[stub:plan-invalid-always]]} (every answer is not). The Stub's other markers apply to the whole prompt as everywhere.
 */
final class StubPlans {
    static final String TASK = "<task kind=\"plan\">";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TARGET = Pattern.compile("(?m)^(m\\d{1,3}) · ");
    private static final Pattern MECHANICS = Pattern.compile("Механики: ([A-Z_, ]+?)\\.");
    private static final Pattern PER_TARGET = Pattern.compile("На материал: (\\d{1,3})\\.");
    private static final Pattern NOTE = Pattern.compile("(?s)<note id=\"(n\\d{1,3})\">(.*?)</note>");
    private static final int TITLE_CHARACTERS = 48;

    private StubPlans() { }

    static boolean isPlanRequest(String prompt) {
        return prompt.contains(TASK);
    }

    static String answer(String prompt, boolean repair) {
        if (prompt.contains("[[stub:plan-invalid-always]]") || (!repair && prompt.contains("[[stub:plan-invalid]]"))) {
            return "{\"items\":\"нет\"}";
        }
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode items = answer.putArray("items");
        if (prompt.contains("Вид: MATERIALS")) {
            materials(prompt, items);
        } else {
            exercises(prompt, items);
        }
        return answer.toString();
    }

    private static void exercises(String prompt, ArrayNode items) {
        List<String> targets = new ArrayList<>();
        Matcher found = TARGET.matcher(prompt);
        while (found.find()) targets.add(found.group(1));
        List<String> allowed = new ArrayList<>();
        Matcher mechanics = MECHANICS.matcher(prompt);
        if (mechanics.find()) for (String name : mechanics.group(1).split(",")) if (!name.isBlank()) allowed.add(name.strip());
        Matcher number = PER_TARGET.matcher(prompt);
        int count = number.find() ? Integer.parseInt(number.group(1)) : 3;
        for (int index = 0; index < targets.size(); index++) {
            ObjectNode item = items.addObject().put("target", targets.get(index));
            ArrayNode chosen = item.putArray("mechanics");
            if (!allowed.isEmpty()) {
                chosen.add(allowed.get(index % allowed.size()));
                if (allowed.size() > 1) chosen.add(allowed.get((index + 1) % allowed.size()));
            }
            item.put("count", count).put("why", "Мало упражнений на этот материал.");
        }
    }

    private static void materials(String prompt, ArrayNode items) {
        Matcher notes = NOTE.matcher(prompt);
        boolean any = false;
        while (notes.find()) {
            any = true;
            items.addObject().put("source", notes.group(1)).put("title", title(notes.group(2))).put("effort", "MEDIUM")
                    .put("why", "Заметка заслуживает отдельного материала.");
        }
        if (any) return;
        for (int index = 1; index <= 3; index++) {
            ObjectNode item = items.addObject();
            item.putNull("source");
            item.put("title", "Тема " + index).put("effort", "MEDIUM").put("why", "Часть темы из просьбы.");
        }
    }

    /** The first words of a note as a title; the prompt carries the note escaped, so the entities are decoded again. */
    private static String title(String note) {
        String plain = note.strip().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
        String line = plain.lines().findFirst().orElse("").strip();
        if (line.isEmpty()) return "Заметка";
        return line.length() <= TITLE_CHARACTERS ? line : line.substring(0, TITLE_CHARACTERS).strip();
    }
}
