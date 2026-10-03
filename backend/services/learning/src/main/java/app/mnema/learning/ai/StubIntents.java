package app.mnema.learning.ai;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stub's answer to an intent request (the prompt carries {@code <task kind="intent">}): a keyword mapping of the Russian request to
 * the closed vocabulary of {@code ai/prompts/v1/intent.md}, so the whole flow of «Попросить Мнему…» runs without a provider.
 *
 * <ul>
 *   <li>«все типы», «все механики» mean {@code mechanics: AUTO}; the names of mechanics («пропуск», «выбор», «сопоставление», «порядок»,
 *       «группировка», «свободный ответ», «самопроверка») select them; a number in the text is {@code perTarget}
 *       («по 3», «1000 упражнений»): an oversized number is passed on as written, so the server's clamp is what the tests see;</li>
 *   <li>«голос», «озвучка», «аудио» mean {@code REVISE_EXERCISE} with {@code media: AUDIO_REGENERATE}, the voice «мужской» or «женский»;</li>
 *   <li>«проще», «короче», «подробнее», «пример», «перепиши», «исправь» mean {@code REVISE_ITEM} (an exercise in an exercise context:
 *       {@code REVISE_EXERCISE}) with the request as the instruction;</li>
 *   <li>«лимит», «бюджет» (an injection) are answered like a hostile model would: an absurd {@code perTarget}, a budget and a
 *       target that the server must ignore;</li>
 *   <li>anything else is {@code UNSUPPORTED}.</li>
 * </ul>
 * Two markers in the request exercise the repair: {@code [[stub:intent-invalid]]} (the first answer is outside the vocabulary, the repair is
 * valid) and {@code [[stub:intent-invalid-always]]} (every answer is). The Stub's other markers apply to the whole prompt as everywhere.
 */
final class StubIntents {
    static final String TASK = "<task kind=\"intent\">";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The request opens a line of its own: the data policy and the task name the tag in running text. */
    private static final Pattern REQUEST = Pattern.compile("(?m)^<request>(.*?)</request>$", Pattern.DOTALL);
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,15})");
    private static final Pattern EXERCISES = Pattern.compile("все (?:типы|механики)|всех (?:типов|механик)|по \\d+|\\d+ упражнен|(?:добав|созда|сдела)\\p{L}* (?:\\S+ )?упражнен|упражнени\\p{L}* (?:на|к|для)|разные типы");
    private static final Pattern REVISE = Pattern.compile("проще|короче|подробнее|пример|перепиши|исправь|упрости|сократи");
    private static final Pattern VOICE = Pattern.compile("голос|озвуч|аудио");
    private static final int MAX_INSTRUCTION = 2_000;

    private StubIntents() { }

    static boolean isIntentRequest(String prompt) {
        return prompt.contains(TASK);
    }

    /** The answer text; {@code repair} is true when the request carries a repair segment (the once-only marker then stops applying). */
    static String answer(String prompt, boolean repair) {
        Matcher found = REQUEST.matcher(prompt);
        String request = found.find() ? found.group(1).strip() : "";
        if (prompt.contains("[[stub:intent-invalid-always]]") || (!repair && prompt.contains("[[stub:intent-invalid]]"))) {
            return "{\"operation\":\"DROP_DECK\",\"mechanics\":\"AUTO\",\"perTarget\":null,\"instruction\":\"\",\"media\":null}";
        }
        String text = request.toLowerCase(Locale.ROOT).replace('ё', 'е');
        boolean exerciseContext = prompt.contains("вид: EXERCISE");
        ObjectNode answer = JSON.createObjectNode();
        answer.putNull("perTarget");
        answer.put("mechanics", "AUTO");
        answer.put("instruction", "");
        answer.putNull("media");
        if (text.contains("лимит") || text.contains("бюджет")) {
            // an injection: the model "obeys" and asks for everything, with fields the vocabulary does not have
            answer.put("operation", "EXERCISES").put("perTarget", 999_999_999);
            answer.put("budgetPercent", 100).put("reason", "потратить всё");
            answer.putArray("targets").addObject().put("memberKey", "00000000-0000-4000-8000-000000000000");
            return answer.toString();
        }
        if (VOICE.matcher(text).find()) {
            answer.put("operation", "REVISE_EXERCISE");
            answer.putObject("media").put("action", "AUDIO_REGENERATE").put("voice", text.contains("женск") ? "female" : "male");
            return answer.toString();
        }
        if (EXERCISES.matcher(text).find()) {
            answer.put("operation", "EXERCISES");
            answer.set("mechanics", mechanics(text));
            Matcher number = NUMBER.matcher(text);
            if (number.find()) answer.put("perTarget", Long.parseLong(number.group(1)));
            return answer.toString();
        }
        if (REVISE.matcher(text).find()) {
            answer.put("operation", exerciseContext ? "REVISE_EXERCISE" : "REVISE_ITEM");
            answer.put("instruction", request.length() <= MAX_INSTRUCTION ? request : request.substring(0, MAX_INSTRUCTION));
            return answer.toString();
        }
        answer.put("operation", "UNSUPPORTED").put("reason", "не умею");
        return answer.toString();
    }

    private static tools.jackson.databind.JsonNode mechanics(String text) {
        if (text.contains("все типы") || text.contains("всех типов") || text.contains("все механики") || text.contains("всех механик")
                || text.contains("разные типы")) {
            return JSON.getNodeFactory().stringNode("AUTO");
        }
        List<String> named = new ArrayList<>();
        if (text.contains("самопровер")) named.add("SELF_CHECK");
        if (text.contains("свободн")) named.add("FREE_RESPONSE");
        if (text.contains("пропуск")) named.add("CLOZE");
        if (text.contains("выбор")) named.add("CHOICE");
        if (text.contains("сопостав")) named.add("MATCH");
        if (text.contains("порядок")) named.add("ORDER");
        if (text.contains("групп") || text.contains("категор")) named.add("CATEGORIZE");
        if (named.isEmpty()) return JSON.getNodeFactory().stringNode("AUTO");
        var array = JSON.createArrayNode();
        named.forEach(array::add);
        return array;
    }
}
