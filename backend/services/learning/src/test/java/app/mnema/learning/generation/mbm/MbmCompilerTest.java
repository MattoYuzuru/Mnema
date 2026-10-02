package app.mnema.learning.generation.mbm;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behavior of the compiler beyond the fixtures: positions, bounds, handles, allocator rules, hostile input. */
class MbmCompilerTest {

    private static final MbmCompiler COMPILER = new MbmCompiler();
    private static final UUID KEPT_3 = UUID.fromString("7d000000-0000-4000-8000-000000000003");
    private static final UUID KEPT_4 = UUID.fromString("7d000000-0000-4000-8000-000000000004");

    private static MbmResult compile(String source) {
        return COMPILER.compile(source, MbmOptions.create(), new MbmFixtures.SequentialIds());
    }

    private static MbmResult compile(String source, MbmOptions options) {
        return COMPILER.compile(source, options, new MbmFixtures.SequentialIds());
    }

    private static List<String> errors(String source, MbmOptions options) {
        MbmResult result = compile(source, options);
        assertThat(result).as(source).isInstanceOf(MbmResult.Failure.class);
        return ((MbmResult.Failure) result).errors().stream()
                .map(error -> error.line() + ":" + error.column() + ":" + error.code().name().substring(4)
                        + (error.attribute() == null ? "" : ":" + error.attribute())).toList();
    }

    private static List<String> errors(String source) {
        return errors(source, MbmOptions.create());
    }

    private static MbmResult.Success success(String source, MbmOptions options) {
        MbmResult result = compile(source, options);
        assertThat(result).as(source).isInstanceOf(MbmResult.Success.class);
        return (MbmResult.Success) result;
    }

    private static String texts(MbmResult.Success success) {
        var out = new ArrayList<String>();
        collect(success.document().path("root"), out);
        return String.join("|", out);
    }

    private static void links(JsonNode node, List<String> labels) {
        if (node.path("type").stringValue("").equals("link")) {
            labels.add(node.path("content").get(0).path("attrs").path("text").stringValue());
        }
        node.path("content").forEach(child -> links(child, labels));
        if (node.has("root")) {
            links(node.path("root"), labels);
        }
    }

    private static void collect(JsonNode node, List<String> out) {
        out.add(node.path("type").stringValue() + (node.path("type").stringValue().equals("text")
                ? "=" + node.path("attrs").path("text").stringValue() + node.path("attrs").path("marks") : ""));
        node.path("content").forEach(child -> collect(child, out));
    }

    // ------------------------------------------------------------------ blocks

    @Test
    void headingsNeedTheSpaceAndStopAtLevelThree() {
        assertThat(texts(success("#no space\n####\n", MbmOptions.create()))).isEqualTo("doc|paragraph|text=#no space ####[]");
        assertThat(errors("####### seven")).containsExactly("1:1:HEADING_LEVEL");
        assertThat(texts(success("# \n", MbmOptions.create()))).isEqualTo("doc|heading");
    }

    @Test
    void paragraphsEndAtBlockStartsAndJoinTrimmedLinesWithOneSpace() {
        assertThat(texts(success("one\n   two  \n- item\nthree\n---\nfour\n> q\n", MbmOptions.create()))).isEqualTo(
                "doc|paragraph|text=one two[]|bullet_list|list_item|paragraph|text=item three[]|divider|paragraph|text=four[]"
                        + "|blockquote|paragraph|text=q[]");
    }

    @Test
    void listsSplitByKindByTwoBlankLinesAndNumberOnlyTheFirstItem() {
        MbmResult.Success success = success("1. a\n5. b\n\n- c\n\n\n- d\n0. z\n", MbmOptions.create());
        assertThat(success.document().path("root").path("content")).hasSize(4);
        assertThat(success.document().path("root").path("content").get(0).path("attrs").has("order")).isFalse();
        assertThat(texts(success)).startsWith("doc|ordered_list|list_item|paragraph|text=a[]|list_item|paragraph|text=b[]|bullet_list");
        assertThat(success("7. a\n8. b\n", MbmOptions.create()).document().path("root").path("content").get(0).path("attrs")
                .path("order").intValue()).isEqualTo(7);
        // ten digits are not a marker
        assertThat(texts(success("1234567890. a\n", MbmOptions.create()))).isEqualTo("doc|paragraph|text=1234567890. a[]");
    }

    @Test
    void indentedListMarkersAreNestedListsEverywhere() {
        assertThat(errors("  - lone")).containsExactly("1:1:NESTED_LIST");
        assertThat(errors("text\n   1. nested")).containsExactly("2:1:NESTED_LIST");
        assertThat(errors("- a\n\t- b\n- c")).containsExactly("2:1:NESTED_LIST");
        assertThat(texts(success("  indented text\n", MbmOptions.create()))).isEqualTo("doc|paragraph|text=indented text[]");
    }

    @Test
    void blockquotesSplitParagraphsOnEmptyQuoteLinesAndEmptyOnesGetAnEmptyParagraph() {
        assertThat(texts(success("> a\n> b\n>\n> c\nd\n>\n", MbmOptions.create())))
                .isEqualTo("doc|blockquote|paragraph|text=a b[]|paragraph|text=c[]|paragraph|text=d[]|blockquote|paragraph");
        assertThat(texts(success(">\n", MbmOptions.create()))).isEqualTo("doc|blockquote|paragraph");
    }

    @Test
    void dividerAcceptsTrailingSpacesOnly() {
        assertThat(texts(success("---  \n----\n", MbmOptions.create()))).isEqualTo("doc|divider|paragraph|text=----[]");
    }

    @Test
    void aFenceOutsideMermaidIsACodeBlockWhoseSourceIsKeptVerbatim() {
        MbmResult.Success success = success("```sql\nSELECT 1;\n\n\tFROM t   \n```\ntext", MbmOptions.create());
        assertThat(texts(success)).isEqualTo("doc|code_block|paragraph|text=text[]");
        JsonNode code = success.document().path("root").path("content").get(0);
        assertThat(code.path("attrs").path("lang").stringValue()).isEqualTo("sql");
        assertThat(code.path("attrs").path("source").stringValue()).isEqualTo("SELECT 1;\n\n\tFROM t   ");

        JsonNode bare = success("```\n  x\n```", MbmOptions.create()).document().path("root").path("content").get(0);
        assertThat(bare.path("attrs").has("lang")).isFalse();
        assertThat(bare.path("attrs").path("source").stringValue()).isEqualTo("  x");
        // a fence interrupts a paragraph and needs no blank line after it
        assertThat(texts(success("before\n```c++\nint x;\n```\nafter", MbmOptions.create())))
                .isEqualTo("doc|paragraph|text=before[]|code_block|paragraph|text=after[]");
        // a longer fence closes only with at least as many backticks; the markers inside are code
        JsonNode nested = success("````text\n```\n# not a heading\n[[b1]] ::table\n````  \nafter", MbmOptions.create())
                .document().path("root").path("content").get(0);
        assertThat(nested.path("attrs").path("source").stringValue()).isEqualTo("```\n# not a heading\n[[b1]] ::table");
        assertThat(texts(success("```inline``` code on one line\n", MbmOptions.create()))).contains("text=");
    }

    @Test
    void codeFenceInfoStringIsOneLowercaseIdentifierAndMermaidBelongsToItsDirective() {
        assertThat(errors("```mermaid\ngraph\n```\ntext")).containsExactly("1:1:CODE_LANGUAGE_INVALID:lang");
        assertThat(errors("```SQL\nx\n```")).containsExactly("1:1:CODE_LANGUAGE_INVALID:lang");
        assertThat(errors("```sql server\nx\n```")).containsExactly("1:1:CODE_LANGUAGE_INVALID:lang");
        assertThat(errors("```" + "a".repeat(33) + "\nx\n```")).containsExactly("1:1:CODE_LANGUAGE_INVALID:lang");
        assertThat(errors("```-a\nx\n```")).containsExactly("1:1:CODE_LANGUAGE_INVALID:lang");
        for (String lang : List.of("c#", "c++", "objective-c.2", "a".repeat(32), "0")) {
            JsonNode code = success("```" + lang + "\nx\n```", MbmOptions.create()).document().path("root").path("content").get(0);
            assertThat(code.path("attrs").path("lang").stringValue()).isEqualTo(lang);
        }
        // spaces around the info string are not part of it
        assertThat(success("```  sql  \nx\n```", MbmOptions.create()).document().path("root").path("content").get(0)
                .path("attrs").path("lang").stringValue()).isEqualTo("sql");
    }

    @Test
    void codeBlocksNeedCodeAClosingFenceAndFitTheBound() {
        assertThat(errors("```sql\n \n\t\n```")).containsExactly("1:1:EMPTY_CODE_BLOCK");
        assertThat(errors("```\n```")).containsExactly("1:1:EMPTY_CODE_BLOCK");
        assertThat(errors("```\nnever closed\n# deep\n#### x")).containsExactly("1:1:UNTERMINATED_FENCE");
        assertThat(errors("````sql\nSELECT 1;\n```")).containsExactly("1:1:UNTERMINATED_FENCE");
        assertThat(errors("```\n" + "x".repeat(16385) + "\n```")).containsExactly("1:1:VALUE_TOO_LONG");
        assertThat(success("```\n" + "x".repeat(16384) + "\n```", MbmOptions.create()).document().path("root")
                .path("content").get(0).path("attrs").path("source").stringValue()).hasSize(16384);
        // every finding of every block is reported; the code block's content is skipped
        assertThat(errors("```x y\n#### deep\n```\n#### z")).containsExactly("1:1:CODE_LANGUAGE_INVALID:lang", "4:1:HEADING_LEVEL");
    }

    @Test
    void aHandleKeepsTheNodeIdOfACodeBlockAndATypeChangeIsAWarning() {
        MbmOptions options = MbmOptions.edit(Map.of("b1", new MbmOptions.Handle(KEPT_3, "code_block")));
        MbmResult.Success kept = success("[[b1]] ```sql\nSELECT 1;\n```", options);
        assertThat(kept.document().path("root").path("content").get(0).path("id").stringValue()).isEqualTo(KEPT_3.toString());
        assertThat(kept.warnings()).isEmpty();
        MbmOptions changed = MbmOptions.edit(Map.of("b1", new MbmOptions.Handle(KEPT_3, "paragraph")));
        assertThat(success("[[b1]] ```sql\nSELECT 1;\n```", changed).warnings()).extracting(MbmFinding::code)
                .containsExactly(MbmCode.MBM_HANDLE_TYPE_CHANGED);
    }

    @Test
    void emptyDocumentsAreErrorsIncludingOnlyBlankInput() {
        assertThat(errors("")).containsExactly("1:null:EMPTY_DOCUMENT");
        assertThat(errors("   \n\t\n")).containsExactly("1:null:EMPTY_DOCUMENT");
    }

    @Test
    void errorsAreOrderedByPositionAndCappedAtTwenty() {
        String source = "#### a\n\n".repeat(30);
        List<String> errors = errors(source);
        assertThat(errors).hasSize(20).first().isEqualTo("1:1:HEADING_LEVEL");
        assertThat(errors.get(19)).isEqualTo("39:1:HEADING_LEVEL");
    }

    @Test
    void lineEndingsAreNormalizedAndNulAndLoneSurrogatesBecomeReplacementCharacters() {
        assertThat(texts(success("a\rb\r\nc\n", MbmOptions.create()))).isEqualTo("doc|paragraph|text=a b c[]");
        assertThat(texts(success("a\u0000b\uD800c\uDC00d\uD83D\uDE00\n", MbmOptions.create())))
                .isEqualTo("doc|paragraph|text=a\uFFFDb\uFFFDc\uFFFDd\uD83D\uDE00[]");
    }

    // ------------------------------------------------------------------ inline

    @Test
    void rubyNeedsOneBarAndNonemptyParts() {
        assertThat(texts(success("{a|} {|b} {a|b|c} {a{b|c} {ab} {a|b", MbmOptions.create())))
                .isEqualTo("doc|paragraph|text={a|} {|b} {a|b|c} {a[]|ruby|text= {ab} {a|b[]");
    }

    @Test
    void codeSpansNeedAnExactRunAndAreVerbatim() {
        assertThat(texts(success("`a` ``b ` c`` ```x`` y", MbmOptions.create())))
                .isEqualTo("doc|paragraph|text=a[\"code\"]|text= []|text=b ` c[\"code\"]|text= ```x`` y[]");
    }

    @Test
    void emphasisClosersSkipEscapesAndCodeSpansAndNestingKeepsUniqueMarks() {
        assertThat(texts(success("*a `*` \\* b* **c *d* ***e***", MbmOptions.create()))).contains("text=a ")
                .contains("[\"em\"]");
        MbmResult.Success same = success("**a **b** c**", MbmOptions.create());
        assertThat(texts(same)).doesNotContain("strong\",\"strong");
    }

    @Test
    void linksNeedNonemptyLabelAndUrlWithoutWhitespaceAndMatchAfterLowercasingSchemeAndHost() {
        MbmOptions options = MbmOptions.create().withAllowedLinks(List.of("https://allowed.example/Path"));
        MbmResult.Success success = success("[a](HTTPS://Allowed.EXAMPLE/Path) [](https://allowed.example/Path) [b](x y) [c]() "
                + "[d] (https://allowed.example/Path) [e](https://allowed.example/path)", options);
        assertThat(texts(success)).contains("link").contains("text=a");
        assertThat(success.document().toString()).contains("\"href\":\"https://allowed.example/Path\"");
        // empty label, spaced url, empty url and a different path stay text; only the path differs in case for the last
        assertThat(success.warnings()).extracting(MbmFinding::code).containsOnly(MbmCode.MBM_LINK_NOT_ALLOWED);
        assertThat(success.warnings()).hasSize(1);
    }

    @Test
    void nestedLinksAreOneErrorPerBlockAndAlsoInsideEmphasis() {
        MbmOptions options = MbmOptions.create().withAllowedLinks(List.of("https://a.example/", "https://b.example/"));
        assertThat(errors("[x **[y](https://a.example/)** [z](https://b.example/)](https://a.example/)", options))
                .containsExactly("1:null:NESTED_LINK");
        assertThat(errors("[a](https://a.example/)\n\n[b [c](https://a.example/)](https://b.example/)", options))
                .containsExactly("3:null:NESTED_LINK");
    }

    @Test
    void bracketsAndEscapesInLinkLabels() {
        MbmOptions options = MbmOptions.create().withAllowedLinks(List.of("https://a.example/"));
        MbmResult.Success success = success("[a [b] \\] c](https://a.example/)", options);
        assertThat(texts(success)).isEqualTo("doc|paragraph|link|text=a [b] ] c[]");
    }

    @Test
    void literalDelimiterWarningsAreReportedPerRunOnTheBlockLine() {
        MbmResult.Success success = success("ok\n\n*open and **open2 and ****\n", MbmOptions.create());
        assertThat(success.warnings()).extracting(MbmFinding::line).containsExactly(3, 3, 3);
        assertThat(success.warnings()).extracting(MbmFinding::column).containsOnlyNulls();
    }

    // ------------------------------------------------------------------ directives

    @Test
    void directiveHeadsReportOneSyntaxErrorAndNothingElse() {
        assertThat(errors("::audio{slot=\"a1\" lang=\"ja\" title=\"t\" 行く")).containsExactly("1:1:ATTRIBUTE_SYNTAX");
        assertThat(errors("::audio{slot=\"a1\"lang=\"ja\"} x")).containsExactly("1:1:ATTRIBUTE_SYNTAX");
        assertThat(errors("::audio{Slot=\"a1\"} x")).containsExactly("1:1:ATTRIBUTE_SYNTAX");
        assertThat(errors("::audio{slot=\"a1\"}x")).containsExactly("1:1:ATTRIBUTE_SYNTAX");
        assertThat(errors("::audio{slot}")).containsExactly("1:1:ATTRIBUTE_SYNTAX");
        assertThat(errors("::audio{slot=\"a1\" ")).containsExactly("1:1:ATTRIBUTE_SYNTAX");
        assertThat(errors("::audio{slot=\"unclosed}")).containsExactly("1:1:ATTRIBUTE_SYNTAX");
        assertThat(errors("::table{caption=x}\n| a |\n|---|\n| 1 |\n\n::mermaid{x\n```mermaid\nm\n```\n\n::sources{\n[1] u"))
                .containsExactly("1:1:ATTRIBUTE_SYNTAX", "6:1:ATTRIBUTE_SYNTAX", "11:1:ATTRIBUTE_SYNTAX");
    }

    @Test
    void attributeEscapesAndBlanksAreHandled() {
        MbmResult.Success success = success("::mermaid{title=\"a \\\"q\\\" \\\\ \\n\" description=\"d\"}\n```mermaid\ng\n```\n",
                MbmOptions.create());
        assertThat(success.document().path("root").path("content").get(0).path("attrs").path("title").stringValue())
                .isEqualTo("a \"q\" \\ \\n");
        assertThat(errors("::audio{slot=\" \" lang=\"ja\" title=\"t\"} x")).containsExactly("1:1:MISSING_ATTRIBUTE:slot");
        assertThat(errors("::audio{slot=\"a1\" lang=\"ja\" title=\"t\" voice=\"\"} x")).containsExactly("1:1:INVALID_ATTRIBUTE_VALUE:voice");
        assertThat(errors("::audio{foo=\"1\" foo=\"2\" slot=\"a1\" lang=\"ja\" title=\"t\"} x"))
                .containsExactly("1:1:UNKNOWN_ATTRIBUTE:foo");
    }

    @Test
    void mediaDirectivesReportEveryIndependentFinding() {
        assertThat(errors("::audio{slot=\"A\" lang=\"x_y\" title=\"" + "t".repeat(1025) + "\" voice=\"robot\"} " + "x".repeat(601)))
                .containsExactly("1:1:INVALID_SLOT_KEY:slot", "1:1:VALUE_TOO_LONG:title",
                        "1:1:INVALID_ATTRIBUTE_VALUE:lang", "1:1:INVALID_ATTRIBUTE_VALUE:voice", "1:1:AUDIO_TEXT_TOO_LONG");
        assertThat(errors("::video{slot=\"v1\" title=\"t\"}")).containsExactly("1:1:CAPABILITY_OFF", "1:1:DIRECTIVE_BODY_MISSING");
        assertThat(errors("::image{slot=\"i\" mode=\"generate\" alt=\"a\"} " + "q".repeat(301),
                MbmOptions.create().withCapabilities(new MbmOptions.Capabilities(false, true)))).containsExactly("1:1:VALUE_TOO_LONG");
    }

    @Test
    void mediaBoundCountsExistingMediaAndIsReportedOnceAtTheFirstExcess() {
        String two = "::audio{slot=\"a1\" lang=\"ja\" title=\"t\"} x\n\n::audio{slot=\"a2\" lang=\"ja\" title=\"t\"} x\n";
        assertThat(errors(two, MbmOptions.create().withMaxMedia(1))).containsExactly("3:1:TOO_MANY_MEDIA");
        assertThat(errors(two, MbmOptions.edit(Map.of()).withMaxMedia(8).withExistingMediaCount(7))).containsExactly("3:1:TOO_MANY_MEDIA");
        assertThat(errors(two + "\n::audio{slot=\"a3\" lang=\"ja\" title=\"t\"} x\n", MbmOptions.create().withMaxMedia(0)))
                .containsExactly("1:1:TOO_MANY_MEDIA");
        assertThat(MbmOptions.create().withMaxMedia(99).maxMedia()).isEqualTo(8);
        assertThat(success(two, MbmOptions.edit(Map.of()).withExistingMediaCount(6)).slots()).hasSize(2);
        // existingMediaCount is an EDIT-only option
        assertThat(success(two, MbmOptions.create().withExistingMediaCount(8)).slots()).hasSize(2);
    }

    @Test
    void slotKeysAreCheckedAgainstExistingSlotsOnlyInEditMode() {
        String audio = "::audio{slot=\"a9\" lang=\"ja\" title=\"t\"} x\n";
        assertThat(success(audio, MbmOptions.create().withExistingSlotKeys(Set.of("a9"))).slots()).hasSize(1);
        assertThat(errors(audio, MbmOptions.edit(Map.of()).withExistingSlotKeys(Set.of("a9")))).containsExactly("1:1:DUPLICATE_SLOT:slot");
    }

    @Test
    void mediaSlotsCarryTheSpecAndThePreAllocatedAssetId() {
        MbmResult.Success success = success("::audio{slot=\"a1\" lang=\"ja\" title=\"t\" voice=\"male\"}  x y \n\n"
                + "::image{slot=\"i1\" mode=\"generate\" alt=\"alt\"} draw\n\n::video{slot=\"v1\" title=\"vt\"} film\n",
                MbmOptions.create().withCapabilities(new MbmOptions.Capabilities(true, true)));
        assertThat(success.slots()).extracting(MbmSlot::slotKey).containsExactly("a1", "i1", "v1");
        assertThat(success.slots().get(0).spec()).containsExactly(Map.entry("lang", "ja"), Map.entry("voice", "male"),
                Map.entry("text", "x y"));
        assertThat(success.slots().get(1).spec()).containsExactly(Map.entry("mode", "generate"), Map.entry("prompt", "draw"));
        assertThat(success.slots().get(2).spec()).containsExactly(Map.entry("prompt", "film"));
        assertThat(success.slots()).extracting(MbmSlot::assetId).doesNotHaveDuplicates();
    }

    @Test
    void tablesReportTheirOwnProblems() {
        assertThat(errors("::table{caption=\"c\"}\n| a |\n")).containsExactly("2:1:TABLE_MALFORMED");
        assertThat(errors("::table{caption=\"c\"}\n| a | b |\n|---|\n")).containsExactly("2:1:TABLE_MALFORMED");
        assertThat(errors("::table{caption=\"c\"}\n| a |\n| x |\n| 1 |")).containsExactly("2:1:TABLE_MALFORMED");
        assertThat(errors("::table{caption=\"c\"}\n| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |\n" + "| " + "z".repeat(4097) + " | 1 |\n"))
                .containsExactly("4:1:TABLE_RAGGED", "5:1:TABLE_RAGGED", "6:1:VALUE_TOO_LONG");
        assertThat(errors("::table{caption=\"c\"}\n| " + "h".repeat(1025) + " |\n|---|\n")).containsExactly("2:1:VALUE_TOO_LONG");
        assertThat(errors("::table{caption=\"c\" caption=\"d\" x=\"1\"}\n| a |\n|---|")).containsExactly("1:1:DUPLICATE_ATTRIBUTE:caption",
                "1:1:UNKNOWN_ATTRIBUTE:x");
        assertThat(errors("::table\n| a |\n|---|")).containsExactly("1:1:MISSING_ATTRIBUTE:caption");
    }

    @Test
    void tableCellsAreTrimmedLiteralTextWithTwoEscapes() {
        MbmResult.Success success = success("::table{caption=\"c\"}\n|a|b\\\\|\\x|\n| :-: | --: | - |\n|*x*| `y` \\| | z\n||||\n", MbmOptions.create());
        JsonNode attrs = success.document().path("root").path("content").get(0).path("attrs");
        assertThat(attrs.path("columns").toString()).isEqualTo("[\"a\",\"b\\\\\",\"\\\\x\"]");
        assertThat(attrs.path("rows").toString()).isEqualTo("[[\"*x*\",\"`y` |\",\"z\"],[\"\",\"\",\"\"]]");
    }

    @Test
    void mermaidFencesNeedTheExactInfoStringAndAClosingFenceOfEnoughBackticks() {
        String head = "::mermaid{title=\"t\" description=\"d\"}\n";
        assertThat(errors(head + "```sql\nx\n```")).containsExactly("1:1:DIRECTIVE_BODY_MISSING");
        assertThat(errors(head + "\n```mermaid\nx\n```")).containsExactly("1:1:DIRECTIVE_BODY_MISSING", "3:1:CODE_LANGUAGE_INVALID:lang");
        assertThat(errors(head + "```mermaid\n   \n```")).containsExactly("1:1:DIRECTIVE_BODY_MISSING");
        assertThat(errors(head + "```mermaid\n" + "x".repeat(16385) + "\n```")).containsExactly("1:1:VALUE_TOO_LONG");
        assertThat(errors(head + "````mermaid\n```\n")).containsExactly("2:1:UNTERMINATED_FENCE");
        assertThat(errors(head)).containsExactly("1:1:DIRECTIVE_BODY_MISSING");
        MbmResult.Success success = success(head + "````mermaid\n```\ncode\n````  \nafter", MbmOptions.create());
        assertThat(success.document().path("root").path("content").get(0).path("attrs").path("source").stringValue()).isEqualTo("```\ncode");
    }

    @Test
    void sourcesNeedWellFormedLinesThatMatchResearchAndUseTheTitleOrTheUrlAsLabel() {
        MbmOptions options = MbmOptions.create().withResearch(List.of(
                new MbmOptions.ResearchSource(1, "https://a.example/", " A "),
                new MbmOptions.ResearchSource(1, "https://dup.example/", "dup"),
                new MbmOptions.ResearchSource(2, "https://b.example/", "  "),
                new MbmOptions.ResearchSource(3, "http://bad.example/", "bad")));
        MbmResult.Success success = success("::sources\n[1] https://a.example/\n[2] https://b.example/\n", options);
        assertThat(success.warnings()).extracting(MbmFinding::line).containsExactly(0);
        assertThat(success.document().toString()).contains("\"text\":\"A\"").contains("\"text\":\"https://b.example/\"")
                .contains("\"text\":\"Sources\"");
        assertThat(errors("::sources\n[1] https://a.example/ extra\n[x] y\n[3] http://bad.example/", options))
                .containsExactly("2:1:SOURCE_NOT_IN_RESEARCH", "3:1:SOURCE_NOT_IN_RESEARCH", "4:1:SOURCE_NOT_IN_RESEARCH");
        assertThat(errors("::sources{x=\"1\"} text\n[1] https://a.example/", options))
                .containsExactly("1:1:UNKNOWN_ATTRIBUTE:x", "1:1:UNEXPECTED_DIRECTIVE_TEXT");
        assertThat(errors("::sources\n\nafter", options)).containsExactly("1:1:DIRECTIVE_BODY_MISSING");
    }

    @Test
    void directiveNamesAreExactAndAnythingAfterTheColonsIsAnUnknownDirective() {
        assertThat(errors("::")).containsExactly("1:1:UNKNOWN_DIRECTIVE");
        assertThat(errors("::Audio{}")).containsExactly("1:1:UNKNOWN_DIRECTIVE");
        assertThat(errors("::tablex")).containsExactly("1:1:UNKNOWN_DIRECTIVE");
        assertThat(errors(":::table")).containsExactly("1:1:UNKNOWN_DIRECTIVE");
        assertThat(texts(success("a :: b ::table\n", MbmOptions.create()))).contains("text=a :: b ::table");
    }

    // ------------------------------------------------------------------ handles

    private static MbmOptions edit() {
        return MbmOptions.edit(Map.of("b3", new MbmOptions.Handle(KEPT_3, "paragraph"), "b4", new MbmOptions.Handle(KEPT_4, "table")));
    }

    @Test
    void handlesKeepIdsOfSameTypeBlocksAndEveryDeclaredHandleMustAppear() {
        MbmResult.Success success = success("[[b4]] ::table{caption=\"c\"}\n| a |\n|---|\n\n[[b3]] text\n", edit());
        assertThat(success.document().path("root").path("content").get(0).path("id").stringValue()).isEqualTo(KEPT_4.toString());
        assertThat(success.document().path("root").path("content").get(1).path("id").stringValue()).isEqualTo(KEPT_3.toString());
        assertThat(success.document().path("root").path("id").stringValue()).isEqualTo("00000000-0000-4000-8000-000000000000");
        assertThat(errors("text", edit())).containsExactly("1:null:EDIT_HANDLE_OMITTED:b3", "1:null:EDIT_HANDLE_OMITTED:b4");
        assertThat(errors("\n[[b3]] text\n\n\n", edit())).containsExactly("2:null:EDIT_HANDLE_OMITTED:b4");
    }

    @Test
    void handlesAreUnknownInNewDocumentsAndHandleOnlyLinesAddressAnEmptyParagraph() {
        assertThat(errors("[[b3]] text", MbmOptions.create())).containsExactly("1:1:UNKNOWN_HANDLE");
        MbmResult.Success success = success("[[b3]]\n\n[[b4]] ::table{caption=\"c\"}\n| a |\n|---|\n", edit());
        assertThat(success.document().path("root").path("content").get(0).path("content")).isEmpty();
        assertThat(success.warnings()).isEmpty();
        assertThat(texts(success("[[b1]]x [[b3]] y\n", MbmOptions.create()))).isEqualTo("doc|paragraph|text=[[b1]]x [[b3]] y[]");
    }

    @Test
    void aHandleOnAnErroringBlockStillCountsAsPresent() {
        assertThat(errors("[[b3]] #### too deep\n\n[[b4]] ::table{caption=\"c\"}\n| a |\n|---|\n", edit()))
                .containsExactly("1:1:HEADING_LEVEL");
    }

    @Test
    void typeChangeIsAWarningForEveryBlockKindAndSourcesCountAsAHeading() {
        var handles = Map.of("b1", new MbmOptions.Handle(KEPT_3, "heading"));
        MbmOptions options = MbmOptions.edit(handles).withResearch(List.of(new MbmOptions.ResearchSource(1, "https://a.example/", "A")));
        MbmResult.Success sources = success("[[b1]] ::sources\n[1] https://a.example/\n", options);
        assertThat(sources.warnings()).isEmpty();
        assertThat(sources.document().path("root").path("content").get(0).path("id").stringValue()).isEqualTo(KEPT_3.toString());
        for (String block : List.of("> q", "- i", "1. i", "---", "# h", "::mermaid{title=\"t\" description=\"d\"}\n```mermaid\nm\n```",
                "::audio{slot=\"a\" lang=\"ja\" title=\"t\"} x", "::image{slot=\"i\" mode=\"search\" alt=\"a\"} x")) {
            var paragraph = MbmOptions.edit(Map.of("b1", new MbmOptions.Handle(KEPT_3, "paragraph")));
            MbmResult.Success result = success("[[b1]] " + block, paragraph);
            assertThat(result.warnings()).as(block).extracting(MbmFinding::code).containsExactly(MbmCode.MBM_HANDLE_TYPE_CHANGED);
        }
    }

    @Test
    void optionsRejectMisuse() {
        assertThatThrownBy(() -> MbmOptions.create().withMaxMedia(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MbmOptions.create().withExistingMediaCount(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MbmOptions.edit(Map.of("3", new MbmOptions.Handle(KEPT_3, "paragraph"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MbmOptions.edit(Map.of("b1", new MbmOptions.Handle(KEPT_3, "paragraph"),
                "b2", new MbmOptions.Handle(KEPT_3, "table")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MbmOptions.Handle(UUID.fromString("00000000-0000-1000-8000-000000000001"), "paragraph"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MbmOptions.create().withSourcesHeading("  ").sourcesHeading()).isEqualTo("Sources");
        assertThat(new MbmOptions(null, null, null, null, 8, 0, null, null, null).mode()).isEqualTo(MbmOptions.Mode.CREATE);
    }

    // ------------------------------------------------------------------ allocator

    @Test
    void theCompilerAsksTheAllocatorAgainOnCollisionsAndRejectsNonUuidV4() {
        var ids = new MbmFixtures.SequentialIds() {
            private int calls;

            @Override
            public UUID nextNodeId() {
                return calls++ < 2 ? KEPT_3 : super.nextNodeId();
            }
        };
        MbmOptions options = MbmOptions.edit(Map.of("b3", new MbmOptions.Handle(KEPT_3, "paragraph")));
        MbmResult result = COMPILER.compile("[[b3]] kept\n\nnew", options, ids);
        assertThat(((MbmResult.Success) result).document().path("root").path("content").get(1).path("id").stringValue())
                .isEqualTo("00000000-0000-4000-8000-000000000002");

        IdAllocator v1 = new IdAllocator() {
            @Override
            public UUID nextNodeId() {
                return UUID.fromString("00000000-0000-1000-8000-000000000001");
            }

            @Override
            public UUID nextAssetId() {
                return UUID.randomUUID();
            }
        };
        assertThatThrownBy(() -> COMPILER.compile("text", MbmOptions.create(), v1)).isInstanceOf(IllegalStateException.class);
        IdAllocator stuck = new IdAllocator() {
            @Override
            public UUID nextNodeId() {
                return KEPT_3;
            }

            @Override
            public UUID nextAssetId() {
                return KEPT_3;
            }
        };
        assertThatThrownBy(() -> COMPILER.compile("a\n\nb", MbmOptions.create(), stuck)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedCompilationsAllocateNothing() {
        var calls = new AtomicInteger();
        IdAllocator counting = new IdAllocator() {
            @Override
            public UUID nextNodeId() {
                calls.incrementAndGet();
                return UUID.randomUUID();
            }

            @Override
            public UUID nextAssetId() {
                calls.incrementAndGet();
                return UUID.randomUUID();
            }
        };
        assertThat(COMPILER.compile("#### x", MbmOptions.create(), counting)).isInstanceOf(MbmResult.Failure.class);
        assertThat(calls).hasValue(0);
        assertThat(COMPILER.compile("x", MbmOptions.create(), counting)).isInstanceOf(MbmResult.Success.class);
        assertThat(calls.get()).isEqualTo(3);
        assertThat(new RandomIdAllocator().nextNodeId().version()).isEqualTo(4);
        assertThat(new RandomIdAllocator().nextAssetId().version()).isEqualTo(4);
    }

    @Test
    void lintHooksSeeTheNormalizedSourceAndTheDocumentWithoutFailingTheCompilation() {
        var seen = new ArrayList<String>();
        MbmCompiler compiler = new MbmCompiler(List.of((source, document) -> {
            seen.add(source + document.path("formatVersion"));
            return List.of(new MbmLint.LintFinding(1, "COPY"));
        }));
        MbmResult.Success success = (MbmResult.Success) compiler.compile("a\r\nb", MbmOptions.create(), new MbmFixtures.SequentialIds());
        assertThat(seen).containsExactly("a\nb1");
        assertThat(success.lintFindings()).containsExactly(new MbmLint.LintFinding(1, "COPY"));
    }

    // ------------------------------------------------------------------ bounds

    @Test
    void sourcesOverTwoHundredFiftySixKibAreTooLarge() {
        String atLimit = ("a".repeat(10_000) + "\n\n").repeat(26) + "a".repeat(2_092);
        assertThat(atLimit.length()).isEqualTo(MbmCompiler.MAX_SOURCE_BYTES);
        assertThat(compile(atLimit)).isInstanceOf(MbmResult.Success.class);
        assertThat(errors(atLimit + "a")).containsExactly("1:null:DOCUMENT_TOO_LARGE");
        // multi-byte characters count as bytes: 131,073 two-byte characters exceed the bound
        assertThat(errors("я".repeat(MbmCompiler.MAX_SOURCE_BYTES / 2 + 1))).containsExactly("1:null:DOCUMENT_TOO_LARGE");
        assertThat(errors("x".repeat(1 << 20))).containsExactly("1:null:DOCUMENT_TOO_LARGE");
    }

    @Test
    void documentsOverTenThousandNodesAreTooLargeAndTextOverTheScalarBoundIsRejectedByTheReader() {
        assertThat(errors("- a\n".repeat(3_400))).containsExactly("1:null:DOCUMENT_TOO_LARGE");
        assertThat(compile("- a\n".repeat(3_000))).isInstanceOf(MbmResult.Success.class);
        // 32 KiB of UTF-8 in one text node
        assertThat(errors("я".repeat(16_385))).containsExactly("1:null:DOCUMENT_TOO_LARGE");
    }

    @Test
    void hostileInlineInputIsBoundedByTheWorkBudgetAndTheNestingDepth() {
        assertThat(errors("*a ".repeat(80_000))).containsExactly("1:null:DOCUMENT_TOO_LARGE");
        assertThat(errors("[".repeat(200_000))).containsExactly("1:null:DOCUMENT_TOO_LARGE");
        assertThat(errors("{".repeat(100_000) + "|}")).containsExactly("1:null:DOCUMENT_TOO_LARGE");
        assertThat(errors("`a ``".repeat(30_000))).containsExactly("1:null:DOCUMENT_TOO_LARGE");
        assertThat(errors("*a ".repeat(200) + "a* ".repeat(200))).containsExactly("1:null:DOCUMENT_TOO_LARGE");
        assertThat(compile("*a ".repeat(100) + "a* ".repeat(100))).isInstanceOf(MbmResult.Success.class);
    }

    @Test
    void linkAllowlistsAndResearchAreFilteredByTheNativeProfileWithoutExceptions() {
        List<String> hostile = List.of("", " ", "https://", "https://a.example/\n", "https://\uD800.example/", "javascript:alert(1)",
                "https://" + "a".repeat(3000) + ".example/", "https://ok.example/", "HTTPS://OK.example/");
        MbmResult.Success success = success("[x](https://ok.example/)", MbmOptions.create().withAllowedLinks(hostile)
                .withResearch(List.of(new MbmOptions.ResearchSource(1, "https://\u0000", "t"))));
        assertThat(success.warnings()).filteredOn(finding -> finding.code() == MbmCode.MBM_LINK_REJECTED_BY_PROFILE).hasSize(8);
        assertThat(success.document().toString()).contains("\"href\":\"https://ok.example/\"");
    }

    // ------------------------------------------------------------------ review fixes

    @Test
    void imagesAreNotSupportedAndStayLiteralTextWithoutALink() {
        MbmOptions options = MbmOptions.create().withAllowedLinks(List.of("https://a.example/"));
        MbmResult.Success success = success("![x](https://a.example/) and \\![y](https://a.example/) and !![z](https://nope.example/)", options);
        assertThat(texts(success)).isEqualTo("doc|paragraph|text=![x](https://a.example/) and ![]|link|text=y[]"
                + "|text= and !![z](https://nope.example/)[]");
        assertThat(success.warnings()).isEmpty();
    }

    @Test
    void handleRegexAcceptsLineSeparatorsInTheRestOfTheLine() {
        MbmOptions options = MbmOptions.edit(Map.of("b1", new MbmOptions.Handle(KEPT_3, "paragraph")));
        for (String separator : List.of("\u0085", "\u2028", "\u2029")) {
            MbmResult.Success success = success("[[b1]] a" + separator + "b", options);
            assertThat(success.document().path("root").path("content").get(0).path("id").stringValue()).isEqualTo(KEPT_3.toString());
        }
    }

    @Test
    void researchTitlesAndTheSourcesHeadingAreSanitizedAndBounded() {
        String hostileTitle = "t\u0000\uD800" + "x".repeat(2000) + "\n";
        MbmOptions options = MbmOptions.create().withSourcesHeading("h\u0000\n" + "H".repeat(500))
                .withResearch(List.of(new MbmOptions.ResearchSource(1, "https://a.example/", hostileTitle),
                        new MbmOptions.ResearchSource(2, "https://b.example/", "😀".repeat(600))));
        assertThat(options.sourcesHeading()).hasSize(200).startsWith("h\uFFFD H");
        MbmResult.Success success = success("::sources\n[1] https://a.example/\n[2] https://b.example/\n", options);
        var labels = new ArrayList<String>();
        links(success.document(), labels);
        assertThat(labels.get(0)).hasSize(1024).startsWith("t\uFFFD\uFFFDx");
        assertThat(labels.get(1)).hasSize(1024).endsWith("😀");
        assertThat(MbmOptions.create().withSourcesHeading("\u0000").sourcesHeading()).isEqualTo("\uFFFD");
    }

    @Test
    void echoedAttributeNamesAreBoundedAndTheRepairListIsCapped() {
        String key = "k".repeat(10_000);
        List<String> found = errors("::table{" + key + "=\"1\" caption=\"c\"}\n|a|\n|-|");
        assertThat(found).containsExactly("1:1:UNKNOWN_ATTRIBUTE:" + "k".repeat(32));
        var many = new ArrayList<MbmFinding>();
        for (int i = 1; i <= 20; i++) {
            many.add(new MbmFinding(i, 1, MbmCode.MBM_INVALID_ATTRIBUTE_VALUE, "a".repeat(100)));
        }
        String list = MbmRepairList.format(many);
        assertThat(list.length()).isLessThanOrEqualTo(MbmRepairList.MAX_CHARACTERS);
        assertThat(list.lines().count()).isBetween(10L, 19L);
        assertThat(list).doesNotContain("a".repeat(33));
    }

    @Test
    void theSuccessResultOwnsItsDocument() {
        MbmResult.Success success = success("text", MbmOptions.create());
        ((tools.jackson.databind.node.ObjectNode) success.document()).put("formatVersion", 99);
        assertThat(success.document().path("formatVersion").intValue()).isEqualTo(1);
    }
}
