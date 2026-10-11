package app.mnema.learning.library;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ExercisePromptsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void theSummaryJoinsThePromptTextBlocksAndSkipsEverythingElse() throws Exception {
        var content = JSON.readTree("""
                {"prompt":[{"kind":"TEXT","text":"Что такое  память?"},{"kind":"IMAGE","assetId":"x","alt":"картинка"},
                           {"kind":"MATERIAL","memberKey":"m","itemRevisionId":"r","nodeId":"n"},{"kind":"TEXT","text":"Кратко."}],
                 "reference":[{"kind":"TEXT","text":"SECRET REFERENCE"}],"options":[{"blocks":[{"kind":"TEXT","text":"SECRET OPTION"}]}]}
                """);
        assertThat(ExercisePrompts.summary("FREE_RESPONSE", content)).isEqualTo("Что такое память? Кратко.");
    }

    @Test
    void aClozeWithoutPromptTextShowsItsPassageWithEllipsesForBlanks() throws Exception {
        var content = JSON.readTree("""
                {"prompt":[],"passage":[{"kind":"TEXT","text":"Yo"},{"kind":"BLANK","blankId":"b","size":{"mode":"FIXED","length":5},"firstLetterHint":false},
                                        {"kind":"TEXT","text":"a casa."}]}
                """);
        assertThat(ExercisePrompts.summary("CLOZE", content)).isEqualTo("Yo … a casa.");
    }

    @Test
    void anExerciseWithoutPlainTextHasNoSummary() throws Exception {
        assertThat(ExercisePrompts.summary("CHOICE", JSON.readTree("{\"prompt\":[{\"kind\":\"IMAGE\",\"assetId\":\"x\",\"alt\":\"a\"}]}"))).isNull();
        assertThat(ExercisePrompts.summary("MATCH", JSON.readTree("{}"))).isNull();
        assertThat(ExercisePrompts.summary("SELF_CHECK", JSON.readTree("{\"prompt\":[{\"kind\":\"TEXT\"}]}"))).isNull();
        assertThat(ExercisePrompts.summary("FREE_RESPONSE", JSON.readTree("{\"prompt\":[{\"kind\":\"TEXT\",\"text\":\"   \"}]}"))).isNull();
    }

    @Test
    void aLongQuestionIsCutAtTwoHundredCodePointsWithoutSplittingAPair() throws Exception {
        String emoji = "😀".repeat(300);
        var content = JSON.createObjectNode();
        content.putArray("prompt").addObject().put("kind", "TEXT").put("text", emoji);
        String summary = ExercisePrompts.summary("SELF_CHECK", content);
        assertThat(summary.codePointCount(0, summary.length())).isEqualTo(ExercisePrompts.MAX_CODE_POINTS + 1);
        assertThat(summary).endsWith("…");
        assertThat(Character.isLowSurrogate(summary.charAt(summary.length() - 2))).isTrue();
        var exact = JSON.createObjectNode();
        exact.putArray("prompt").addObject().put("kind", "TEXT").put("text", "x".repeat(200));
        assertThat(ExercisePrompts.summary("SELF_CHECK", exact)).isEqualTo("x".repeat(200));
    }
}
