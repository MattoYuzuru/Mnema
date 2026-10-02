package app.mnema.learning.ai;

import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmResult;
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
}
