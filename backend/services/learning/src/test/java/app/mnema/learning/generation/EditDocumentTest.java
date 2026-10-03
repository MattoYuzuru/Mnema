package app.mnema.learning.generation;

import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmResult;
import app.mnema.learning.generation.mbm.RandomIdAllocator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure parts of an edit: the target run, the handles, the replacement of a range and the round trip through MBM. */
class EditDocumentTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MbmCompiler COMPILER = new MbmCompiler();

    private static JsonNode compile(String mbm) {
        return ((MbmResult.Success) COMPILER.compile(mbm,
                MbmOptions.create().withAllowedLinks(List.of("https://example.org/a")), new RandomIdAllocator())).document();
    }

    private static final String SOURCE = """
            # Заголовок

            Первый абзац с **термином** и [ссылкой](https://example.org/a).

            Второй абзац.

            - пункт один
            - пункт два

            ::audio{slot="a1" lang="ja" title="Произношение"} 行く

            Последний абзац.
            """;

    private static List<UUID> ids(JsonNode document, int... indexes) {
        List<JsonNode> blocks = EditDocument.blocks(document);
        return java.util.Arrays.stream(indexes).mapToObj(index -> EditDocument.id(blocks.get(index))).toList();
    }

    @Test
    void aTargetIsAConsecutiveRunOfTopLevelBlocksWhoseMediaIsSetAsideAndNothingElseIsAccepted() {
        JsonNode document = compile(SOURCE);
        assertThat(EditDocument.blocks(document)).hasSize(6);

        EditTarget run = EditTarget.resolve(document, ids(document, 2, 1)).orElseThrow();
        assertThat(run.from()).isEqualTo(1);
        assertThat(run.to()).isEqualTo(2);
        assertThat(run.text()).extracting(EditTarget.Indexed::index).containsExactly(1, 2);
        assertThat(run.media()).isEmpty();
        assertThat(run.noMedia()).isTrue();

        EditTarget withMedia = EditTarget.resolve(document, ids(document, 3, 4, 5)).orElseThrow();
        assertThat(withMedia.text()).extracting(EditTarget.Indexed::index).containsExactly(3, 5);
        assertThat(withMedia.media()).hasSize(1);
        assertThat(withMedia.onlyMedia()).isFalse();
        assertThat(EditTarget.resolve(document, ids(document, 4)).orElseThrow().onlyMedia()).isTrue();

        // a gap, a repeat, an unknown id, a nested node and nothing at all are not targets
        assertThat(EditTarget.resolve(document, ids(document, 1, 3))).isEmpty();
        assertThat(EditTarget.resolve(document, List.of(ids(document, 1).getFirst(), ids(document, 1).getFirst()))).isEmpty();
        assertThat(EditTarget.resolve(document, List.of(UUID.randomUUID()))).isEmpty();
        UUID nested = EditDocument.id(EditDocument.blocks(document).get(1).path("content").get(0));
        assertThat(EditTarget.resolve(document, List.of(nested))).isEmpty();
        assertThat(EditTarget.resolve(document, List.of())).isEmpty();
    }

    @Test
    void handlesNumberTheTopLevelBlocksInOrder() {
        JsonNode document = compile(SOURCE);
        var handles = EditDocument.handles(document);
        assertThat(handles.keySet()).containsExactly("b1", "b2", "b3", "b4", "b5", "b6");
        assertThat(handles.get("b3")).isEqualTo(ids(document, 2).getFirst());
        assertThat(EditDocument.ids(document)).hasSize(6);
        assertThat(EditDocument.idSet(document)).hasSize(6);
        assertThat(EditDocument.isMedia(EditDocument.blocks(document).get(4))).isTrue();
        assertThat(EditDocument.isMedia(EditDocument.blocks(document).get(1))).isFalse();
    }

    @Test
    void replacingARangeKeepsEveryBlockOutsideItByteForByteAndAppendsTheKeptMedia() {
        JsonNode document = compile(SOURCE);
        List<JsonNode> blocks = EditDocument.blocks(document);
        JsonNode newBlocks = compile("Новый абзац.\n\nЕщё один.");

        JsonNode replaced = EditDocument.replace(document, 3, 4, EditDocument.blocks(newBlocks), List.of(blocks.get(4)));

        List<JsonNode> after = EditDocument.blocks(replaced);
        assertThat(after).hasSize(7);
        for (int index : new int[] {0, 1, 2, 5}) {
            int moved = index == 5 ? 6 : index;
            // the same JSON and the same serialized bytes
            assertThat(after.get(moved)).isEqualTo(blocks.get(index));
            assertThat(after.get(moved).toString()).isEqualTo(blocks.get(index).toString());
        }
        assertThat(after.get(3).path("content").get(0).path("attrs").path("text").stringValue(null)).isEqualTo("Новый абзац.");
        assertThat(after.get(5)).isEqualTo(blocks.get(4));
        // the source document was not touched
        assertThat(EditDocument.blocks(document)).hasSize(6);
        assertThat(replaced.path("formatVersion").intValue()).isEqualTo(1);
        List<JsonNode> without = EditDocument.blocks(EditDocument.without(document, Set.of(EditDocument.id(blocks.get(4)))));
        assertThat(without).hasSize(5).doesNotContain(blocks.get(4));
    }

    @Test
    void renderingATargetAndCompilingTheAnswerWithItsHandlesKeepsEveryNodeIdOfABlockWhoseTypeIsUnchanged() {
        JsonNode document = compile(SOURCE);
        EditTarget target = EditTarget.resolve(document, ids(document, 0, 1, 2, 3)).orElseThrow();
        EditContexts.Rendered rendered = new EditContexts(null, null, null).render(target);

        assertThat(rendered.text()).startsWith("[[b1]] # Заголовок\n\n[[b2]] Первый абзац с **термином**").contains("\n\n[[b3]] Второй абзац.\n\n[[b4]] - пункт один\n- пункт два");
        assertThat(rendered.handles().keySet()).containsExactly("b1", "b2", "b3", "b4");
        assertThat(rendered.links()).containsExactly("https://example.org/a");

        // the answer: the second paragraph becomes a heading (new id, a warning), the others keep theirs, a block without a handle is new
        String answer = rendered.text().replace("[[b3]] Второй абзац.", "[[b3]] ## Теперь заголовок") + "\n\nНовый хвост.";
        MbmOptions options = MbmOptions.edit(rendered.handles()).withAllowedLinks(List.copyOf(rendered.links())).withMaxMedia(0);
        MbmResult.Success success = (MbmResult.Success) COMPILER.compile(answer, options, new RandomIdAllocator());
        List<JsonNode> rewritten = EditDocument.blocks(success.document());
        assertThat(rewritten).hasSize(5);
        assertThat(EditDocument.id(rewritten.get(0))).isEqualTo(ids(document, 0).getFirst());
        assertThat(EditDocument.id(rewritten.get(1))).isEqualTo(ids(document, 1).getFirst());
        assertThat(EditDocument.id(rewritten.get(2))).isNotEqualTo(ids(document, 2).getFirst());
        assertThat(rewritten.get(2).path("type").stringValue(null)).isEqualTo("heading");
        assertThat(EditDocument.id(rewritten.get(3))).isEqualTo(ids(document, 3).getFirst());
        assertThat(success.warnings()).extracting(warning -> warning.code().name()).contains("MBM_HANDLE_TYPE_CHANGED");

        // omitting a handle, an unknown handle and a media directive are errors the model is asked to repair
        assertThat(COMPILER.compile("[[b1]] # Заголовок", options, new RandomIdAllocator())).isInstanceOf(MbmResult.Failure.class);
        assertThat(COMPILER.compile(rendered.text() + "\n\n[[b9]] чужой", options, new RandomIdAllocator())).isInstanceOf(MbmResult.Failure.class);
        assertThat(COMPILER.compile(rendered.text() + "\n\n::audio{slot=\"x1\" lang=\"ja\" title=\"t\"} 行く", options, new RandomIdAllocator()))
                .isInstanceOf(MbmResult.Failure.class);
    }

    @Test
    void theEditorOnlyAcceptsTargetsTheModelCanBeGiven() {
        EditContexts contexts = new EditContexts(null, null, null);
        JsonNode plain = compile("Обычный абзац.");
        assertThat(contexts.editable(EditTarget.resolve(plain, ids(plain, 0)).orElseThrow())).isTrue();

        ObjectNode withPhone = (ObjectNode) JSON.readTree(compile("Позвоните: +7 999 123 45 67").toString());
        assertThat(contexts.editable(EditTarget.resolve(withPhone, ids(withPhone, 0)).orElseThrow())).isFalse();
        ObjectNode withMail = (ObjectNode) JSON.readTree(compile("Пишите на ivan@example.org").toString());
        assertThat(contexts.editable(EditTarget.resolve(withMail, ids(withMail, 0)).orElseThrow())).isFalse();
        // a heading of level 4 has no MBM syntax
        ObjectNode deep = (ObjectNode) JSON.readTree(compile("### Заголовок").toString());
        ((ObjectNode) deep.path("root").path("content").get(0).path("attrs")).put("level", 4);
        assertThat(contexts.editable(EditTarget.resolve(deep, ids(deep, 0)).orElseThrow())).isFalse();
    }

    @Test
    void theAnswerIsUnescapedOnceAndAWrappedAnswerIsUnwrapped() {
        assertThat(EditExecutor.unescape("a &lt; b &amp; c &gt; d &quot;x&quot; &amp;amp;")).isEqualTo("a < b & c > d \"x\" &amp;");
        assertThat(EditExecutor.unescape("без сущностей")).isEqualTo("без сущностей");
        assertThat(EditExecutor.unwrap("<target>[[b2]] текст</target>")).isEqualTo("[[b2]] текст");
        assertThat(EditExecutor.unwrap("  <target>\n[[b2]] текст\n</target>\n")).isEqualTo("[[b2]] текст");
        assertThat(EditExecutor.unwrap("[[b2]] <target> внутри")).isEqualTo("[[b2]] <target> внутри");
    }
}
