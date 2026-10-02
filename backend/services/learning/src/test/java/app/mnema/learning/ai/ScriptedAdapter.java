package app.mnema.learning.ai;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/** A text adapter whose answers are scripted per call; once the script is empty it answers with a plain success. */
final class ScriptedAdapter implements TextAdapter {
    record Call(String model, TextRequest request, Duration budget) { }

    private final String provider;
    private final Deque<Function<Call, AiResult<TextResponse>>> script = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();
    private boolean configured = true;

    ScriptedAdapter(String provider) { this.provider = provider; }

    static AiResult<TextResponse> success(String provider, String model, String text) {
        return AiResult.ok(new TextResponse(text, TextResponse.FinishReason.STOP, new Usage(100, 40, 60, 20), 500,
                "req-1", new TextResponse.RouteUsed(provider, model)));
    }

    ScriptedAdapter then(AiFailure failure) { return thenDo(call -> AiResult.failed(failure)); }

    ScriptedAdapter then(AiFailure failure, int times) {
        for (int index = 0; index < times; index++) then(failure);
        return this;
    }

    ScriptedAdapter thenOk(String text) { return thenDo(call -> success(provider, call.model(), text)); }

    ScriptedAdapter thenDo(Function<Call, AiResult<TextResponse>> step) {
        script.add(step);
        return this;
    }

    ScriptedAdapter unconfigured() {
        configured = false;
        return this;
    }

    synchronized List<Call> calls() { return new ArrayList<>(calls); }

    @Override public String provider() { return provider; }

    @Override public boolean configured() { return configured; }

    @Override
    public AiResult<TextResponse> attempt(String model, TextRequest request, Duration budget) {
        Call call = new Call(model, request, budget);
        Function<Call, AiResult<TextResponse>> step;
        synchronized (this) {
            calls.add(call);
            step = script.poll();
        }
        return step == null ? success(provider, model, "ok") : step.apply(call);
    }
}
