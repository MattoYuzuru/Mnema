package app.mnema.learning.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stub's answer to an edit request (the prompt carries {@code <task kind="edit">}): the blocks of the {@code <target>} echoed in
 * their order, each with its {@code [[bN]]} handle, and every plain paragraph rewritten by one added sentence that names the preset
 * ({@code Переписано: Проще.}), so an edit visibly changes the text while the handles, headings, lists, code and tables stay as they
 * were. A block that is not a plain paragraph (a divider included) is returned unchanged. The answer is valid MBM for exactly the target's handles; the Stub's
 * failure markers ({@code [[stub:refusal]]} and the others) still apply to the whole prompt, so an instruction can carry one.
 */
final class StubEdits {
    static final String TASK = "<task kind=\"edit\">";
    private static final Pattern TARGET = Pattern.compile("</context_before>\\n<target>(.*?)</target>\\n<context_after>", Pattern.DOTALL);
    private static final Pattern PRESET = Pattern.compile("Пресет: ([^.\\n]+)\\.");
    private static final Pattern HANDLE = Pattern.compile("^(\\[\\[[A-Za-z][0-9]+]])(?: (.*))?$", Pattern.DOTALL);
    private static final Pattern STRUCTURED = Pattern.compile("^(?:#|[-*] |\\d+[.)] |>|::|```|\\||-{3,}$).*", Pattern.DOTALL);

    private StubEdits() { }

    static boolean isEditRequest(String prompt) {
        return prompt.contains(TASK);
    }

    /** The answer text for the target of {@code prompt}; an empty target gives an empty answer (the compiler then rejects it). */
    static String answer(String prompt) {
        Matcher target = TARGET.matcher(prompt);
        if (!target.find()) return "";
        Matcher preset = PRESET.matcher(prompt);
        String label = preset.find() ? preset.group(1).strip() : "нет";
        List<String> blocks = new ArrayList<>();
        for (String block : target.group(1).strip().split("\\n\\s*\\n")) {
            if (!block.isBlank()) blocks.add(rewrite(block.strip(), label));
        }
        return String.join("\n\n", blocks) + "\n";
    }

    private static String rewrite(String block, String label) {
        Matcher handle = HANDLE.matcher(block);
        String head = "";
        String body = block;
        if (handle.matches()) {
            head = handle.group(1);
            body = handle.group(2) == null ? "" : handle.group(2);
        }
        if (body.isBlank() || STRUCTURED.matcher(body).matches()) return block;
        return head + " " + body.stripTrailing() + " Переписано: " + label + ".";
    }
}
