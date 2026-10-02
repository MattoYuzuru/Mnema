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
                .block("outline.lines", PromptBlocks.outline(List.of(new PromptBlocks.OutlineEntry("m1", "Глагол 行く", "первая строка", 3))));
    }

    static PromptValues material(String title, List<String> notes) {
        PromptValues values = deckBrief(title);
        int index = 0;
        var blocks = new java.util.ArrayList<PromptBlock>();
        for (String note : notes) blocks.add(PromptBlocks.note("N" + ++index, note));
        return values.block("allowed_links", PromptBlocks.allowedLinks(List.of("https://jisho.org/")))
                .block("note_blocks", PromptBlocks.join(blocks))
                .block("search_result_blocks", PromptBlocks.empty()).text("request", "Объясни глагол").text("task.skill", "vocabulary")
                .number("task.words", 200).text("task.media", "аудио");
    }

    static PromptValues edit() {
        return deckBrief("Колода").block("document", PromptBlocks.document("", "[[b1]] Абзац", "")).text("history", "короче")
                .text("preset", "SHORTER").text("instruction", "сократи вдвое");
    }

    static PromptValues exercises() {
        return PromptValues.create().block("schema", PromptBlocks.schema("{\"type\":\"object\"}"))
                .block("material_blocks", PromptBlocks.material("m1", List.of(new PromptBlocks.HandleLine("b1", "Текст"))))
                .block("objective_lines", PromptBlocks.lines(List.of("t1 · вспомнить перевод"))).block("existing_exercise_lines", PromptBlocks.empty())
                .block("neighbor_lines", PromptBlocks.empty()).number("task.count", 3).text("task.mechanics", "CHOICE, CLOZE")
                .text("lang.output", "ru");
    }

    static PromptValues assessment() {
        return PromptValues.create().text("exercise.prompt", "Объясните, зачем нужен индекс").text("exercise.reference", "Ускоряет поиск")
                .block("criteria_lines", PromptBlocks.lines(List.of("c1 · ускоряет поиск"))).block("misconception_lines", PromptBlocks.empty())
                .text("material_fragment", "Индекс ускоряет поиск.").text("feedback_language", "ru").text("answer_source", "TYPED")
                .text("learner_answer_json", "Индекс ускоряет \"поиск\" <b>строк</b>");
    }
}
