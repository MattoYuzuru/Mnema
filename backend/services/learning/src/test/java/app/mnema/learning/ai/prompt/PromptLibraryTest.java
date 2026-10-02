package app.mnema.learning.ai.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLibraryTest {
    private static final String GOOD = "---\nsection: demo\nprompt_version: v1\npurpose: \"Demo: with a colon\"\n---\nТекст {{a.b}} и {{c|\"по умолчанию\"}}\n\n";

    @Test
    void theShippedV1LibraryLoadsWithEveryDocumentedSection() {
        PromptLibrary library = PromptLibrary.fromClasspath("v1");
        assertThat(library.version()).isEqualTo("v1");
        assertThat(library.versions()).contains("v1");
        for (String name : List.of("system", "style", "skill-vocabulary", "skill-grammar", "skill-stem-concept", "skill-code",
                "skill-exam-summary", "deck-brief", "material", "edit", "exercises", "assessment")) {
            PromptSection section = library.section(name);
            assertThat(section.version()).isEqualTo("v1");
            assertThat(section.purpose()).isNotBlank();
            assertThat(section.body()).isNotBlank().doesNotEndWith("\n");
        }
        for (String staticName : List.of("system", "style", "skill-vocabulary", "skill-grammar", "skill-stem-concept", "skill-code",
                "skill-exam-summary")) {
            assertThat(library.section(staticName).isStatic()).as(staticName).isTrue();
        }
        assertThat(library.section("deck-brief").placeholders()).extracting(PromptSection.Placeholder::name)
                .contains("deck.title", "exemplar_blocks", "outline.lines", "level");
        assertThat(library.section("deck-brief").placeholders()).anySatisfy(placeholder -> {
            assertThat(placeholder.name()).isEqualTo("level");
            assertThat(placeholder.defaultValue()).isEqualTo("не указан");
        });
    }

    @Test
    void everyRuntimePlaceholderOfV1HasADeclaredKind() {
        PromptLibrary library = PromptLibrary.fromClasspath("v1");
        for (String name : List.of("deck-brief", "material", "edit", "exercises", "assessment")) {
            for (PromptSection.Placeholder placeholder : library.section(name).placeholders()) {
                // block-named placeholders are code-rendered, everything else is escaped text; no name is ambiguous
                boolean block = PromptValues.isBlockName(placeholder.name());
                assertThat(block == (placeholder.name().endsWith("_blocks") || placeholder.name().endsWith("_lines")
                        || placeholder.name().endsWith(".lines") || List.of("allowed_links", "document", "schema")
                        .contains(placeholder.name()))).as(placeholder.name()).isTrue();
            }
        }
    }

    @Test
    void frontMatterIsParsedStrictly() {
        PromptSection section = PromptLibrary.parse("v1", "demo.md", GOOD);
        assertThat(section.name()).isEqualTo("demo");
        assertThat(section.purpose()).isEqualTo("Demo: with a colon");
        assertThat(section.body()).isEqualTo("Текст {{a.b}} и {{c|\"по умолчанию\"}}");
        assertThat(section.placeholders()).containsExactly(new PromptSection.Placeholder("a.b", null),
                new PromptSection.Placeholder("c", "по умолчанию"));
        assertThat(PromptLibrary.parse("v1", "crlf.md", GOOD.replace("\n", "\r\n")).body()).isEqualTo(section.body());
        // an unquoted value may carry a trailing comment
        assertThat(PromptLibrary.parse("v1", "c.md", "---\nsection: demo   # unique name\nprompt_version: v1\npurpose: p\n---\nx").name())
                .isEqualTo("demo");
    }

    @Test
    void malformedFilesFailFastWithoutQuotingTheirContent() {
        for (String broken : List.of(
                "no front matter",
                "---\nsection: a\nprompt_version: v1\npurpose: p\n",
                "---\nsection: a\nprompt_version: v1\n---\nx",
                "---\nsection: a\nprompt_version: v1\npurpose: p\nextra: 1\n---\nx",
                "---\nsection: a\nsection: b\nprompt_version: v1\npurpose: p\n---\nx",
                "---\nsection: a\nprompt_version: v2\npurpose: p\n---\nx",
                "---\nbogus line\nsection: a\nprompt_version: v1\npurpose: p\n---\nx",
                "---\nsection: a\nprompt_version: v1\npurpose: \"p\n---\nx",
                "---\nsection: a\nprompt_version: v1\npurpose: p\n---\nbroken {{ placeholder",
                "---\nsection: system\nprompt_version: v1\npurpose: p\n---\nstatic {{must.not}}")) {
            assertThatThrownBy(() -> PromptLibrary.parse("v1", "bad.md", broken)).isInstanceOf(PromptException.class);
        }
    }

    @Test
    void sectionsAreAddressedByVersionAndDuplicatesAreRejected() {
        PromptSection v1 = PromptLibrary.parse("v1", "a.md", GOOD);
        PromptSection v2 = PromptLibrary.parse("v2", "a.md", GOOD.replace("v1", "v2"));
        PromptLibrary library = PromptLibrary.of("v2", List.of(v1, v2));
        assertThat(library.section("demo").version()).isEqualTo("v2");
        assertThat(library.section("v1", "demo")).isSameAs(v1);
        assertThat(library.versions()).containsExactly("v1", "v2");
        assertThatThrownBy(() -> library.section("missing")).isInstanceOf(PromptException.class);
        assertThatThrownBy(() -> library.section("v9", "demo")).isInstanceOf(PromptException.class);
        assertThatThrownBy(() -> PromptLibrary.of("v1", List.of(v1, v1))).isInstanceOf(PromptException.class);
        assertThatThrownBy(() -> PromptLibrary.of("v3", List.of(v1))).isInstanceOf(PromptException.class);
    }
}
