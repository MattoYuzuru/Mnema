package app.mnema.learning.ai.prompt;

import java.util.List;

/** Fully populated placeholder values for every task of prompt v1. */
final class PromptFixtures {
    private PromptFixtures() { }

    static PromptValues deckBrief(String title) {
        return PromptValues.create().text("deck.title", title).text("deck.description", "Японский для начинающих")
                .text("lang.output", "ru").text("lang.target", "японский").text("level", "A1")
                .number("counts.items", 12).number("counts.exercises", 30).text("deck_terms", "глагол, частица")
                .number("style_card.words", 180).number("style_card.headings", 2).number("style_card.lists", 1)
                .number("style_card.tables", 0).number("style_card.examples", 3).number("style_card.audio", 1)
                .block("exemplar_blocks", PromptBlocks.exemplar("E1", "starred", "# Пример\n\nТекст."))
                .text("recent_material", "# Последний материал")
                .number("outline.total", 12).number("outline.shown", 12)
                .block("outline.lines", PromptBlocks.lines(List.of("m1 · Глагол 行く · первая строка · exercises: 3")));
    }

    static PromptValues material(String title, List<String> notes) {
        PromptValues values = deckBrief(title);
        int index = 0;
        StringBuilder blocks = new StringBuilder();
        for (String note : notes) blocks.append(PromptBlocks.note("N" + ++index, note)).append('\n');
        return values.block("allowed_links", PromptBlocks.allowedLinks(List.of("https://jisho.org/")))
                .block("note_blocks", blocks.toString().stripTrailing())
                .block("search_result_blocks", "").text("request", "Объясни глагол").text("task.skill", "vocabulary")
                .number("task.words", 200).text("task.media", "аудио");
    }

    static PromptValues edit() {
        return deckBrief("Колода").block("document", "<target>[[b1]] Абзац</target>").text("history", "короче")
                .text("preset", "SHORTER").text("instruction", "сократи вдвое");
    }

    static PromptValues exercises() {
        return PromptValues.create().block("schema", "{\"type\":\"object\"}")
                .block("material_blocks", "<material id=\"m1\">[[b1]] Текст</material>")
                .block("objective_lines", "t1 · вспомнить перевод").block("existing_exercise_lines", "")
                .block("neighbor_lines", "").number("task.count", 3).text("task.mechanics", "CHOICE, CLOZE")
                .text("lang.output", "ru");
    }

    static PromptValues assessment() {
        return PromptValues.create().text("exercise.prompt", "Объясните, зачем нужен индекс").text("exercise.reference", "Ускоряет поиск")
                .block("criteria_lines", "c1 · ускоряет поиск").block("misconception_lines", "")
                .text("material_fragment", "Индекс ускоряет поиск.").text("feedback_language", "ru").text("answer_source", "TYPED")
                .text("learner_answer_json", "Индекс ускоряет \"поиск\" <b>строк</b>");
    }
}
