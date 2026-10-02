package app.mnema.learning.ai;

import java.util.Objects;

/** Success or a typed {@link AiFailure}; provider ports never throw for provider problems. */
public sealed interface AiResult<T> {
    record Ok<T>(T value) implements AiResult<T> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failed<T>(AiFailure failure) implements AiResult<T> {
        public Failed {
            Objects.requireNonNull(failure, "failure");
        }
    }

    static <T> AiResult<T> ok(T value) { return new Ok<>(value); }

    static <T> AiResult<T> failed(AiFailure failure) { return new Failed<>(failure); }
}
