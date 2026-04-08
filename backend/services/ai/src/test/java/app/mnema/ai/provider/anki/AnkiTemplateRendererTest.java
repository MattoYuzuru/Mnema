package app.mnema.ai.provider.anki;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnkiTemplateRendererTest {

    private final AnkiTemplateRenderer renderer = new AnkiTemplateRenderer();

    @Test
    void rendersConditionalsBuiltinsAndFilters() {
        AnkiTemplate template = new AnkiTemplate(
                "{{#Front}}{{furigana:Front}}{{/Front}}{{^Missing}}<span>fallback</span>{{/Missing}}",
                "{{FrontSide}}|{{text:Back}}|{{cloze:Cloze}}|{{type:Back}}|{{Deck}}",
                ".card{}",
                "basic",
                "Basic",
                "Card 1"
        );

        AnkiRendered rendered = renderer.render(template, Map.of(
                "Front", "日本語[にほんご;]",
                "Back", "<b>answer</b>",
                "Cloze", "{{c1::language}}"
        ));

        assertThat(rendered.frontHtml()).contains("<ruby>日本語<rt>にほんご</rt></ruby>");
        assertThat(rendered.frontHtml()).contains("<span>fallback</span>");
        assertThat(rendered.backHtml()).contains(rendered.frontHtml());
        assertThat(rendered.backHtml()).contains("answer");
        assertThat(rendered.backHtml()).contains("<span class=\"cloze\">language</span>");
        assertThat(rendered.backHtml()).doesNotContain("{{Deck}}");
    }

    @Test
    void returnsNullForMissingFrontTemplateAndNormalizesFieldLookup() {
        assertThat(renderer.render(new AnkiTemplate("", "{{Front}}", null, null, null, null), Map.of("Front", "Q"))).isNull();

        AnkiRendered rendered = renderer.render(
                new AnkiTemplate("{{Front Text}}", "{{/Front Text}}{{Unknown}}", null, null, null, null),
                Map.of("front_text", "Question")
        );

        assertThat(rendered.frontHtml()).isEqualTo("Question");
        assertThat(rendered.backHtml()).isEmpty();
    }
}
