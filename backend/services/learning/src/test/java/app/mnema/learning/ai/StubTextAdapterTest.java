package app.mnema.learning.ai;

import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmResult;
import app.mnema.learning.generation.mbm.MbmSlot;
import app.mnema.learning.generation.mbm.RandomIdAllocator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StubTextAdapterTest {
    private final StubTextAdapter stub = new StubTextAdapter();

    private static TextRequest request(String task, OutputContract output, StreamListener listener) {
        return new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.system("ядро", true), TextRequest.Segment.user(task, false)),
                output, 500, 0.5, Duration.ofSeconds(5), AiTestSupport.KEY, listener, null, 1);
    }

    private static TextResponse ok(AiResult<TextResponse> result) { return ((AiResult.Ok<TextResponse>) result).value(); }

    private static AiFailure failure(AiResult<TextResponse> result) { return ((AiResult.Failed<TextResponse>) result).failure(); }

    @Test
    void theAnswerIsAPureFunctionOfTheInput() {
        TextResponse first = ok(stub.attempt("stub", request("тема А", OutputContract.MBM_TEXT, null), Duration.ofSeconds(1)));
        TextResponse again = ok(stub.attempt("stub", request("тема А", OutputContract.MBM_TEXT, null), Duration.ofSeconds(1)));
        assertThat(again).isEqualTo(first);
        assertThat(first.route()).isEqualTo(new TextResponse.RouteUsed("stub", "stub"));
        assertThat(first.costMicros()).isZero();
        assertThat(first.providerRequestId()).startsWith("stub-");
        assertThat(first.usage().cacheHitTokens()).isEqualTo(TokenCounter.estimate("ядро"));
        // different inputs reach different documents across the pool
        var texts = new java.util.HashSet<String>();
        for (int index = 0; index < 40; index++) {
            texts.add(ok(stub.attempt("stub", request("тема " + index, OutputContract.MBM_TEXT, null), Duration.ofSeconds(1))).text());
        }
        assertThat(texts.size()).isGreaterThan(2);
    }

    @Test
    void everyStubDocumentCompilesAndEqualsItsContractFixture() throws IOException {
        var compiler = new MbmCompiler();
        for (String name : List.of("headings", "blockquote-divider", "lists", "ruby", "table")) {
            String stubText = new String(StubTextAdapterTest.class.getResourceAsStream("/ai/stub/" + name + ".mbm").readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(stubText).as(name).isEqualTo(Files.readString(contracts().resolve("mbm-v1/valid/" + name + ".mbm")));
            assertThat(compiler.compile(stubText, MbmOptions.create(), new RandomIdAllocator())).as(name)
                    .isInstanceOf(MbmResult.Success.class);
        }
        String invalid = new String(StubTextAdapterTest.class.getResourceAsStream("/ai/stub/invalid.mbm").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(invalid).isEqualTo(Files.readString(contracts().resolve("mbm-v1/invalid/heading-level-4.mbm")));
        assertThat(compiler.compile(invalid, MbmOptions.create(), new RandomIdAllocator())).isInstanceOf(MbmResult.Failure.class);
    }

    private static Path contracts() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/mbm-v1/codes.json"))) root = root.getParent();
        return root.resolve("contracts/generation");
    }

    @Test
    void jsonOutputIsAFixedShapeObject() {
        TextResponse response = ok(stub.attempt("stub", request("верни json", OutputContract.JSON, null), Duration.ofSeconds(1)));
        assertThat(response.text()).matches("\\{\"stub\":true,\"digest\":\"[0-9a-f]{16}\"\\}");
    }

    @Test
    void streamingDeliversTheSameTextInChunks() {
        List<String> deltas = new ArrayList<>();
        TextResponse response = ok(stub.attempt("stub", request("поток", OutputContract.MBM_TEXT, deltas::add), Duration.ofSeconds(1)));
        assertThat(String.join("", deltas)).isEqualTo(response.text());
        assertThat(deltas.get(0).length()).isLessThanOrEqualTo(48);
    }

    @Test
    void markersSimulateEveryFailureAndInvalidOnesStopApplyingOnRepair() {
        Duration budget = Duration.ofSeconds(1);
        assertThat(failure(stub.attempt("stub", request("[[stub:rate-limit]]", OutputContract.MBM_TEXT, null), budget)))
                .isEqualTo(new AiFailure.RateLimited(Duration.ofSeconds(1)));
        assertThat(failure(stub.attempt("stub", request("[[stub:transient]]", OutputContract.MBM_TEXT, null), budget)))
                .isEqualTo(new AiFailure.Transient("stub_transient"));
        assertThat(failure(stub.attempt("stub", request("[[stub:timeout]]", OutputContract.MBM_TEXT, null), budget)))
                .isEqualTo(new AiFailure.Timeout());
        assertThat(failure(stub.attempt("stub", request("[[stub:refusal]]", OutputContract.MBM_TEXT, null), budget)))
                .isEqualTo(new AiFailure.Refusal("stub_refusal"));
        TextRequest invalid = request("[[stub:invalid]]", OutputContract.MBM_TEXT, null);
        assertThat(failure(stub.attempt("stub", invalid, budget))).isEqualTo(new AiFailure.InvalidOutput("stub_invalid"));
        assertThat(stub.attempt("stub", invalid.withRepair("пусто"), budget)).isInstanceOf(AiResult.Ok.class);

        TextRequest badDocument = request("[[stub:invalid-mbm]]", OutputContract.MBM_TEXT, null);
        assertThat(ok(stub.attempt("stub", badDocument, budget)).text()).startsWith("####");
        assertThat(ok(stub.attempt("stub", badDocument.withRepair("MBM_HEADING_LEVEL"), budget)).text()).doesNotStartWith("####");
        assertThat(stub.configured()).isTrue();
        assertThat(stub.provider()).isEqualTo("stub");
    }

    private static final String IMAGE_LINE = "медиа: картинка из поиска (::image mode=search);";
    private static final String BOTH_LINE = "медиа: аудио (::audio), картинка из поиска (::image mode=search);";

    private static String materialTask(String mediaLine, String request) {
        return "<task>\n<request>" + request + "</request>\nТип: free; " + mediaLine + "\n</task>";
    }

    private MbmResult.Success compile(String text) {
        var result = new MbmCompiler().compile(text, MbmOptions.create().withMaxMedia(8), new RandomIdAllocator());
        assertThat(result).as(text).isInstanceOf(MbmResult.Success.class);
        return (MbmResult.Success) result;
    }

    @Test
    void aMaterialThatMayHaveMediaGetsTheDirectivesAppendedAndTheSlotsCompile() {
        String task = materialTask(BOTH_LINE, "  лиса\nзимой [[stub:image-none]]  ");
        String text = ok(stub.attempt("stub", request(task, OutputContract.MBM_TEXT, null), Duration.ofSeconds(1))).text();

        assertThat(text).contains("\n\n::image{slot=\"i1\" mode=\"search\" alt=\"Иллюстрация к материалу\"} лиса зимой [[stub:image-none]]\n")
                .contains("\n\n::audio{slot=\"a1\" lang=\"ru\" title=\"Озвучка\"} ");
        String heading = text.lines().filter(line -> line.matches("#{1,3} .+")).findFirst().map(line -> line.replaceFirst("^#+ ", "")).orElse("Озвучка");
        assertThat(text).contains("title=\"Озвучка\"} " + heading + "\n");
        assertThat(compile(text).slots()).extracting(MbmSlot::slotKey, MbmSlot::kind)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("i1", MbmSlot.Kind.IMAGE), org.assertj.core.groups.Tuple.tuple("a1", MbmSlot.Kind.AUDIO));
    }

    @Test
    void theImageQueryIsBoundedAndFallsBackToIllustrationAndNothingIsAddedWithoutTheTaskLine() {
        String long300 = ok(stub.attempt("stub", request(materialTask(IMAGE_LINE, "я".repeat(500)), OutputContract.MBM_TEXT, null), Duration.ofSeconds(1))).text();
        assertThat(long300).contains("} " + "я".repeat(300) + "\n").doesNotContain("я".repeat(301));
        String blank = ok(stub.attempt("stub", request(materialTask(IMAGE_LINE, "  "), OutputContract.MBM_TEXT, null), Duration.ofSeconds(1))).text();
        assertThat(blank).contains("} illustration\n").doesNotContain("::audio");
        compile(blank);

        String none = ok(stub.attempt("stub", request(materialTask("медиа: нет, не добавляй медиа-директивы;", "тема"), OutputContract.MBM_TEXT, null),
                Duration.ofSeconds(1))).text();
        assertThat(none).doesNotContain("::image").doesNotContain("::audio");
        assertThat(none).isIn(List.of("headings", "blockquote-divider", "lists", "ruby", "table").stream().map(name -> {
            try {
                return Files.readString(contracts().resolve("mbm-v1/valid/" + name + ".mbm"));
            } catch (IOException unreadable) {
                throw new IllegalStateException(unreadable);
            }
        }).toList());
    }

    @Test
    void theAudioTextIsTheFirstHeadingOfTheDocumentBoundedToSixHundred() {
        String document = StubTextAdapter.withMedia("# Глагол 行く\n\nТекст.\n", materialTask(BOTH_LINE, "тема"));
        assertThat(document).endsWith("\n\n::audio{slot=\"a1\" lang=\"ru\" title=\"Озвучка\"} Глагол 行く\n");
        assertThat(compile(document).slots()).hasSize(2);
        String audioLine = StubTextAdapter.withMedia("# " + "ж".repeat(700) + "\n", materialTask(BOTH_LINE, "тема")).lines()
                .filter(line -> line.startsWith("::audio")).findFirst().orElseThrow();
        assertThat(audioLine).endsWith("} " + "ж".repeat(600));
    }

    @Test
    void aRepairAnswerCarriesNoMedia() {
        TextRequest repair = request(materialTask(BOTH_LINE, "тема"), OutputContract.MBM_TEXT, null).withRepair("MBM_X");
        assertThat(ok(stub.attempt("stub", repair, Duration.ofSeconds(1))).text()).doesNotContain("::image").doesNotContain("::audio");
    }
}
