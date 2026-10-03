package app.mnema.learning.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stub's answer to a grading request (the prompt carries the {@code <grader>} rules and a {@code <learner_answer>} JSON
 * string): strict JSON in the shape of {@code assessment.md}, so the whole assessment flow runs without a provider.
 *
 * <p>Markers inside the learner answer choose the outcome ({@code c1} is the first rubric point, in rubric order):
 * <ul>
 *   <li>{@code [[stub:assess-complete]]}: every point {@code MET}, each with a quote of the answer;</li>
 *   <li>{@code [[stub:assess-shallow]]}: {@code c1} {@code MET}, {@code c2} {@code PARTLY}, the rest {@code MET} (a shallow answer: a
 *       lenient level accepts it, a strict one asks for more, provided c1 and c2 are core points);</li>
 *   <li>{@code [[stub:assess-partial]]}: {@code c1} {@code MET}, every other point {@code NOT_MET};</li>
 *   <li>{@code [[stub:assess-contradicted]]}: {@code c1} {@code CONTRADICTED}, the rest {@code MET};</li>
 *   <li>{@code [[stub:assess-offtopic]]}: every point {@code NOT_MET} and the {@code OFF_TOPIC} flag;</li>
 *   <li>{@code [[stub:assess-unclear]]}: every point {@code UNCLEAR} (provider uncertainty, so self-check);</li>
 *   <li>{@code [[stub:assess-asr]]}: every point {@code MET} and the {@code ASR_GARBLED} flag (self-check);</li>
 *   <li>{@code [[stub:assess-disagree]]}: the first run of a pair answers as {@code complete}, the second as {@code offtopic}
 *       (run disagreement, so self-check at the strict levels; a single run sees {@code complete});</li>
 *   <li>{@code [[stub:assess-slow]]}: a provider that takes {@link #SLOW} (8 s) and then answers as {@code complete}: the waiting
 *       state, the «Оценить себя» offer at 5 s and the discarded late grade can be seen without a test double (the Stub waits at most
 *       for the budget of the call and then times out);</li>
 *   <li>{@code [[stub:assess-injection]]}: the default heuristic plus the {@code INJECTION} flag;</li>
 *   <li>{@code [[stub:assess-invalid]]}: an answer that never fits the schema, so the repair fails and grading is unavailable.</li>
 * </ul>
 * Without a marker a lexical heuristic decides: a point is {@code MET} when the answer holds at least two content words of its
 * description (a word of at least four letters; the first five letters must match, so inflections do) or any acceptable term
 * the prompt lists, {@code PARTLY} with one, else {@code NOT_MET}; no match at all raises {@code OFF_TOPIC}. The other markers
 * of the Stub ({@code [[stub:timeout]]}, {@code [[stub:transient]]}, ...) apply to grading requests too.
 */
final class StubAssessments {
    /** How long {@code [[stub:assess-slow]]} makes the Stub wait before it answers. */
    static final java.time.Duration SLOW = java.time.Duration.ofSeconds(8);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern CRITERIA = Pattern.compile("<criteria>\\n(.*?)\\n</criteria>", Pattern.DOTALL);
    private static final Pattern POINT = Pattern.compile("^(c[0-9]+) · (.*)$");
    private static final Pattern TERMS = Pattern.compile("^\\(допустимые синонимы и переводы терминов: (.*)\\)$");
    private static final Pattern ANSWER = Pattern.compile("<learner_answer>(\"(?:[^\"\\\\]|\\\\.)*\")</learner_answer>");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern MARKERS = Pattern.compile("\\[\\[stub:[a-z-]+]]");
    private static final String MARKER = "[[stub:assess-";
    private static final int STEM = 5;
    private static final int MAX_SPAN = 160;

    private record Point(String id, String description) { }

    private record Match(int start, int end) { }

    private StubAssessments() { }

    /** True when the learner answer asks for the slow provider. */
    static boolean slow(String prompt) {
        return answer(prompt).contains(MARKER + "slow]]");
    }

    static boolean isAssessmentRequest(String prompt) {
        return prompt.contains("<grader>") && ANSWER.matcher(prompt).find();
    }

    /** @param run 1 or 2: the pass of a pair, so that a marker can make the passes disagree */
    static String answer(String prompt, int run) {
        String answer = answer(prompt);
        List<Point> points = points(prompt);
        List<String> terms = terms(prompt);
        ObjectNode result = JSON.createObjectNode();
        ArrayNode criteria = result.putArray("criteria");
        ArrayNode flags = result.putArray("flags");
        String mode = mode(answer);
        if (mode.equals("disagree")) mode = run == 1 ? "complete" : "offtopic";
        switch (mode) {
            case "invalid" -> {
                return "{\"criteria\":[]}";
            }
            case "complete", "asr", "slow" -> {
                for (Point point : points) verdict(criteria, point, "MET", quote(answer), "Пункт есть в ответе.");
                if (mode.equals("asr")) flags.add("ASR_GARBLED");
            }
            case "shallow" -> {
                for (int index = 0; index < points.size(); index++) {
                    boolean partly = index == 1;
                    verdict(criteria, points.get(index), partly ? "PARTLY" : "MET", quote(answer),
                            partly ? "Пункт назван лишь отчасти." : "Пункт есть в ответе.");
                }
            }
            case "partial" -> {
                for (int index = 0; index < points.size(); index++) {
                    if (index == 0) verdict(criteria, points.get(0), "MET", quote(answer), "Пункт есть в ответе.");
                    else verdict(criteria, points.get(index), "NOT_MET", null, "Этого пункта в ответе нет.");
                }
            }
            case "contradicted" -> {
                for (int index = 0; index < points.size(); index++) {
                    if (index == 0) verdict(criteria, points.get(0), "CONTRADICTED", null, "Ответ утверждает обратное.");
                    else verdict(criteria, points.get(index), "MET", quote(answer), "Пункт есть в ответе.");
                }
            }
            case "offtopic" -> {
                for (Point point : points) verdict(criteria, point, "NOT_MET", null, "Этого пункта в ответе нет.");
                flags.add("OFF_TOPIC");
            }
            case "unclear" -> {
                for (Point point : points) verdict(criteria, point, "UNCLEAR", null, "Не удалось определить.");
            }
            default -> {
                boolean any = false;
                for (Point point : points) any |= heuristic(criteria, point, answer, terms);
                if (!any) flags.add("OFF_TOPIC");
                if (answer.contains(MARKER + "injection]]")) flags.add("INJECTION");
            }
        }
        return result.toString();
    }

    // ------------------------------------------------------------------------------------------------- prompt

    private static String mode(String answer) {
        for (String mode : List.of("complete", "slow", "shallow", "partial", "contradicted", "offtopic", "unclear", "asr", "disagree",
                "invalid")) {
            if (answer.contains(MARKER + mode + "]]")) return mode;
        }
        return "heuristic";
    }

    private static String answer(String prompt) {
        Matcher matcher = ANSWER.matcher(prompt);
        if (!matcher.find()) return "";
        try {
            return unescape(JSON.readValue(matcher.group(1), String.class));
        } catch (JacksonException exception) {
            return "";
        }
    }

    private static List<Point> points(String prompt) {
        List<Point> points = new ArrayList<>();
        Matcher block = CRITERIA.matcher(prompt);
        if (!block.find()) return points;
        for (String line : block.group(1).split("\\n")) {
            Matcher point = POINT.matcher(line);
            if (point.matches()) points.add(new Point(point.group(1), unescape(point.group(2))));
        }
        return points;
    }

    private static List<String> terms(String prompt) {
        Matcher block = CRITERIA.matcher(prompt);
        List<String> terms = new ArrayList<>();
        if (!block.find()) return terms;
        for (String line : block.group(1).split("\\n")) {
            Matcher matcher = TERMS.matcher(line);
            if (matcher.matches()) {
                for (String term : unescape(matcher.group(1)).split(", ")) if (!term.isBlank()) terms.add(term.strip());
            }
        }
        return terms;
    }

    private static String unescape(String text) {
        return text.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    // ---------------------------------------------------------------------------------------------- verdicts

    private static void verdict(ArrayNode criteria, Point point, String verdict, String quote, String note) {
        ObjectNode node = criteria.addObject().put("id", point.id());
        node.put("quote", quote == null ? "" : quote).put("note", note).put("verdict", verdict);
    }

    /** The first words of the first marker-free stretch of the answer: a verbatim fragment; the answer itself when it is only markers. */
    private static String quote(String answer) {
        for (String stretch : MARKERS.split(answer)) {
            Matcher words = WORD.matcher(stretch);
            int start = -1;
            int end = 0;
            int count = 0;
            while (count < 8 && words.find()) {
                if (start < 0) start = words.start();
                end = words.end();
                count++;
            }
            if (start >= 0) return stretch.substring(start, end);
        }
        return answer.strip();
    }

    /** @return whether the answer touched the point at all */
    private static boolean heuristic(ArrayNode criteria, Point point, String answer, List<String> terms) {
        List<Match> matches = new ArrayList<>();
        for (String word : contentWords(point.description())) {
            String stem = word.substring(0, Math.min(STEM, word.length()));
            Matcher token = WORD.matcher(answer);
            while (token.find()) {
                if (token.group().toLowerCase(Locale.ROOT).startsWith(stem)) {
                    matches.add(new Match(token.start(), token.end()));
                    break;
                }
            }
        }
        Match term = null;
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String candidate : terms) {
            int at = lower.indexOf(candidate.toLowerCase(Locale.ROOT));
            if (at >= 0) {
                term = new Match(at, at + candidate.length());
                break;
            }
        }
        if (matches.size() >= 2 || term != null) {
            Match span = term != null && matches.size() < 2 ? term : span(matches);
            verdict(criteria, point, "MET", answer.substring(span.start(), span.end()), "Пункт есть в ответе.");
            return true;
        }
        if (matches.size() == 1) {
            Match only = matches.getFirst();
            verdict(criteria, point, "PARTLY", answer.substring(only.start(), only.end()), "Пункт назван лишь отчасти.");
            return true;
        }
        verdict(criteria, point, "NOT_MET", null, "Этого пункта в ответе нет.");
        return false;
    }

    /** From the first to the last matched word when that is short enough to be a quote, else the first matched word. */
    private static Match span(List<Match> matches) {
        int start = matches.stream().mapToInt(Match::start).min().orElseThrow();
        int end = matches.stream().mapToInt(Match::end).max().orElseThrow();
        if (end - start <= MAX_SPAN) return new Match(start, end);
        return matches.stream().min((left, right) -> Integer.compare(left.start(), right.start())).orElseThrow();
    }

    private static List<String> contentWords(String description) {
        List<String> words = new ArrayList<>();
        Matcher token = WORD.matcher(description);
        while (token.find()) {
            String word = token.group().toLowerCase(Locale.ROOT);
            if (word.length() >= 4 && !words.contains(word)) words.add(word);
        }
        return words;
    }
}
