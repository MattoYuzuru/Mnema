package app.mnema.learning.ai;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stub's answer to an exercise request (the prompt carries {@code <task kind="exercises">}): a valid
 * {@code {"exercises": [...]}} built from what the rendered prompt says, so the whole validation pipeline can run without a
 * provider. It reads the task line {@code Составь N упражнений ... Механики: A, B, C} (or, on a repair, the {@code ровно из N
 * упражнений} of the repair message) and the {@code <material id="m1">} block lines {@code [[b3]] text}; it cycles through the
 * allowed mechanics (so a batch of three or more has at least three different ones when three are allowed) and builds each
 * exercise from a real fragment of a block, so the lint (a CLOZE passage must be a fragment of the material) passes.
 *
 * <p>Markers in the material text break the answer on purpose, like the other Stub markers:
 * {@code [[stub:broken-key]]} makes the first exercise of the first answer a SINGLE choice with two correct options (the lint
 * code {@code CHOICE_CORRECT_COUNT}); the repair answer is valid. {@code [[stub:broken-key-always]]} breaks it on every call, the
 * repair and the strong route included, so the artifact ends {@code FAILED(INVALID_OUTPUT)}.
 */
final class StubExercises {
    static final String TASK = "<task kind=\"exercises\">";
    static final String BROKEN_ONCE = "[[stub:broken-key]]";
    static final String BROKEN_ALWAYS = "[[stub:broken-key-always]]";
    private static final String OBJECTIVE = "Основная мысль материала";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern COUNT = Pattern.compile("Составь (\\d+) упражнений");
    private static final Pattern REPAIR_COUNT = Pattern.compile("ровно из (\\d+) упражнений");
    private static final Pattern MECHANICS = Pattern.compile("Механики: ([A-Z_]+(?:, [A-Z_]+)*)");
    private static final Pattern MATERIAL = Pattern.compile("<material id=\"m1\">\\n(.*?)\\n</material>", Pattern.DOTALL);
    private static final Pattern LINE = Pattern.compile("^\\[\\[(b[0-9]+)]] (.*)$");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,40}");
    private static final int SNIPPET = 300;

    private record Block(String handle, String text) { }

    private StubExercises() { }

    static boolean isExerciseRequest(String prompt) {
        return prompt.contains(TASK);
    }

    /** The answer text; {@code repair} is true when the request carries a repair segment (the once-only marker then stops applying). */
    static String answer(String prompt, boolean repair) {
        int count = number(repair ? REPAIR_COUNT : COUNT, prompt, number(COUNT, prompt, 1));
        List<String> mechanics = mechanics(prompt);
        List<Block> blocks = blocks(prompt);
        ArrayNode exercises = JSON.createArrayNode();
        if (!blocks.isEmpty()) {
            for (int index = 0; index < count; index++) {
                exercises.add(exercise(index, mechanics.get(index % mechanics.size()), blocks.get(index % blocks.size())));
            }
            boolean broken = prompt.contains(BROKEN_ALWAYS) || (!repair && prompt.contains(BROKEN_ONCE));
            if (broken && !exercises.isEmpty()) exercises.set(0, brokenChoice(blocks.getFirst()));
        }
        return JSON.createObjectNode().set("exercises", exercises).toString();
    }

    // ------------------------------------------------------------------------- the prompt

    private static int number(Pattern pattern, String prompt, int fallback) {
        Matcher matcher = pattern.matcher(prompt);
        return matcher.find() ? Math.max(1, Integer.parseInt(matcher.group(1))) : fallback;
    }

    private static List<String> mechanics(String prompt) {
        Matcher matcher = MECHANICS.matcher(prompt);
        List<String> mechanics = new ArrayList<>(List.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE"));
        if (matcher.find()) mechanics = List.of(matcher.group(1).split(", "));
        return mechanics;
    }

    private static List<Block> blocks(String prompt) {
        Matcher material = MATERIAL.matcher(prompt);
        List<Block> blocks = new ArrayList<>();
        if (!material.find()) return blocks;
        for (String line : material.group(1).split("\\n")) {
            Matcher matcher = LINE.matcher(line);
            if (matcher.matches() && !matcher.group(2).isBlank()) blocks.add(new Block(matcher.group(1), unescape(matcher.group(2))));
        }
        return blocks;
    }

    private static String unescape(String text) {
        return text.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    // ------------------------------------------------------------------------ the exercises

    private static ObjectNode exercise(int index, String mechanic, Block block) {
        String snippet = snippet(block.text());
        List<String> words = distinctWords(snippet);
        String variant = " (вариант " + (index + 1) + ")";
        return switch (mechanic) {
            case "FREE_RESPONSE" -> freeResponse(snippet, block, variant);
            case "CLOZE" -> longest(snippet, 2) != null ? cloze(snippet, variant) : selfCheck(block, variant);
            case "CHOICE" -> choice(snippet, variant, false);
            case "MATCH" -> words.size() >= 2 ? match(words, variant) : selfCheck(block, variant);
            case "ORDER" -> words.size() >= 2 ? order(words, variant) : selfCheck(block, variant);
            case "CATEGORIZE" -> categorize(words, variant);
            default -> selfCheck(block, variant);
        };
    }

    private static ObjectNode base(String mechanic, String prompt) {
        ObjectNode exercise = JSON.createObjectNode().put("mechanic", mechanic).put("subject", "m1");
        exercise.putObject("objective").put("title", OBJECTIVE);
        exercise.putArray("prompt").addObject().put("kind", "TEXT").put("text", prompt);
        return exercise;
    }

    private static ObjectNode selfCheck(Block block, String variant) {
        ObjectNode exercise = base("SELF_CHECK", "Перескажите своими словами суть фрагмента материала" + variant + ".");
        exercise.putArray("reference").addObject().put("kind", "MATERIAL").put("ref", "m1:" + block.handle());
        return exercise;
    }

    private static ObjectNode freeResponse(String snippet, Block block, String variant) {
        String answer = longest(snippet, 3);
        if (answer == null) return selfCheck(block, variant);
        String hidden = Pattern.compile(Pattern.quote(answer), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(snippet).replaceAll("…");
        String prompt = "Вспомните пропущенное слово" + variant + ": " + hidden;
        // the fixed words of the question must not hand the answer over either (a material about the word "слово")
        if (prompt.toLowerCase(Locale.ROOT).contains(answer.toLowerCase(Locale.ROOT))) return selfCheck(block, variant);
        ObjectNode exercise = base("FREE_RESPONSE", prompt);
        exercise.putArray("reference").addObject().put("kind", "MATERIAL").put("ref", "m1:" + block.handle());
        exercise.putArray("accepted").add(answer);
        exercise.put("matchingMode", "STRICT");
        return exercise;
    }

    private static ObjectNode cloze(String snippet, String variant) {
        String answer = longest(snippet, 2);
        int start = snippet.indexOf(answer);
        String before = snippet.substring(0, start);
        String after = snippet.substring(start + answer.length());
        if (before.isEmpty() && after.isEmpty()) {
            // a single word: the blank is its second half, so the passage still has a text segment
            int half = answer.offsetByCodePoints(0, Math.max(1, answer.codePointCount(0, answer.length()) / 2));
            before = answer.substring(0, half);
            answer = answer.substring(half);
        }
        ObjectNode exercise = base("CLOZE", "Заполните пропуск" + variant + ".");
        ArrayNode passage = exercise.putArray("passage");
        if (!before.isEmpty()) passage.addObject().put("kind", "TEXT").put("text", before);
        ObjectNode blank = passage.addObject().put("kind", "BLANK").put("blank", "bl1").put("firstLetterHint", false);
        blank.putObject("size").put("mode", "ANSWER_LENGTH");
        if (!after.isEmpty()) passage.addObject().put("kind", "TEXT").put("text", after);
        ObjectNode key = exercise.putArray("blanks").addObject().put("blank", "bl1");
        key.putArray("accepted").add(answer);
        key.put("matchingMode", "STRICT");
        return exercise;
    }

    private static ObjectNode choice(String snippet, String variant, boolean broken) {
        ObjectNode exercise = base("CHOICE", "Какое утверждение соответствует материалу" + variant + "?");
        exercise.put("selectionMode", "SINGLE");
        ArrayNode options = exercise.putArray("options");
        options.addObject().put("id", "o1").put("text", clip(snippet, 120)).put("correct", true);
        ObjectNode second = options.addObject().put("id", "o2").put("text", "Противоположное: " + clip(snippet, 60)).put("correct", broken);
        if (!broken) second.put("whyWrong", "Это противоречит материалу.");
        options.addObject().put("id", "o3").put("text", "В материале об этом не сказано").put("correct", false)
                .put("whyWrong", "Материал говорит об этом прямо.");
        options.addObject().put("id", "o4").put("text", "Зависит от обстоятельств: " + clip(snippet, 40)).put("correct", false)
                .put("whyWrong", "Материал не ставит это в зависимость.");
        return exercise;
    }

    /** A SINGLE choice with two correct options: it fails the lint code {@code CHOICE_CORRECT_COUNT}, and only that. */
    private static ObjectNode brokenChoice(Block block) {
        return choice(snippet(block.text()), " (с ошибкой в ключе)", true);
    }

    private static ObjectNode match(List<String> words, String variant) {
        int pairs = Math.min(3, words.size());
        ObjectNode exercise = base("MATCH", "Соедините слова с описанием" + variant + ".");
        ArrayNode left = exercise.putArray("left");
        ArrayNode right = exercise.putArray("right");
        ArrayNode links = exercise.putArray("pairs");
        for (int index = 0; index < pairs; index++) {
            String word = words.get(index);
            left.addObject().put("id", "l" + (index + 1)).put("text", word);
            right.addObject().put("id", "r" + (index + 1)).put("text", "№" + (index + 1) + " по порядку, знаков: " + word.codePointCount(0, word.length()));
            links.addObject().put("left", "l" + (index + 1)).put("right", "r" + (index + 1));
        }
        return exercise;
    }

    private static ObjectNode order(List<String> words, String variant) {
        ObjectNode exercise = base("ORDER", "Расставьте слова в порядке их появления в материале" + variant + ".");
        ArrayNode items = exercise.putArray("items");
        for (int index = 0; index < Math.min(4, words.size()); index++) items.addObject().put("id", "i" + (index + 1)).put("text", words.get(index));
        return exercise;
    }

    private static ObjectNode categorize(List<String> words, String variant) {
        ObjectNode exercise = base("CATEGORIZE", "Отнесите слова к группам по длине" + variant + ".");
        ArrayNode categories = exercise.putArray("categories");
        categories.addObject().put("id", "c1").put("label", "Короткое слово (до 5 знаков)");
        categories.addObject().put("id", "c2").put("label", "Длинное слово (6 знаков и больше)");
        ArrayNode items = exercise.putArray("items");
        int number = 1;
        for (String word : words.subList(0, Math.min(4, words.size()))) {
            items.addObject().put("id", "i" + number++).put("text", word).put("category", word.codePointCount(0, word.length()) <= 5 ? "c1" : "c2");
        }
        // both groups must hold an item whatever the material says
        items.addObject().put("id", "i" + number++).put("text", "Да").put("category", "c1");
        items.addObject().put("id", "i" + number).put("text", "Классификация").put("category", "c2");
        return exercise;
    }

    // --------------------------------------------------------------------------- text helpers

    private static String snippet(String text) {
        return clip(text, SNIPPET);
    }

    private static String clip(String text, int codePoints) {
        return text.codePointCount(0, text.length()) <= codePoints ? text : text.substring(0, text.offsetByCodePoints(0, codePoints));
    }

    /** The longest word of {@code text} with at least {@code minimum} characters (the first among equals), or null. */
    private static String longest(String text, int minimum) {
        Matcher matcher = WORD.matcher(text);
        String best = null;
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePointCount(0, word.length()) >= minimum && (best == null || word.length() > best.length())) best = word;
        }
        return best;
    }

    /** Distinct words (ignoring case) of at least three characters, in order of appearance. */
    private static List<String> distinctWords(String text) {
        Map<String, String> words = new LinkedHashMap<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePointCount(0, word.length()) >= 3) words.putIfAbsent(word.toLowerCase(Locale.ROOT), word);
        }
        return List.copyOf(words.values());
    }
}
