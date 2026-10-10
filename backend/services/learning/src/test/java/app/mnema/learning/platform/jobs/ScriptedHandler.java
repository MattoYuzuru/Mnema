package app.mnema.learning.platform.jobs;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** A handler whose slice is a lambda of the test; counts its calls. */
final class ScriptedHandler implements JobHandler {
    private final String queue;
    private final Function<Job, Slice> body;
    private final BiConsumer<Job, JobState> ended;
    final AtomicInteger calls = new AtomicInteger();

    ScriptedHandler(String queue, Function<Job, Slice> body) {
        this(queue, body, null);
    }

    ScriptedHandler(String queue, Function<Job, Slice> body, BiConsumer<Job, JobState> ended) {
        this.queue = queue;
        this.body = body;
        this.ended = ended;
    }

    @Override
    public void onEnded(Job job, JobState state) {
        if (ended != null) ended.accept(job, state);
    }

    @Override
    public String queue() {
        return queue;
    }

    @Override
    public Slice run(Job job) {
        calls.incrementAndGet();
        return body.apply(job);
    }
}
