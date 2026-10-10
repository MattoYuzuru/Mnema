package app.mnema.learning.platform.jobs;

import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** The steps handler used by the tests: {@code payload.steps} slices, each writing one effect row in the slice's own transaction. */
final class JobTestSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private JobTestSupport() {
    }

    static ObjectNode payload(int steps) {
        return JSON.createObjectNode().put("steps", steps);
    }

    static void createEffectTable(JdbcClient jdbc) {
        jdbc.sql("CREATE TABLE IF NOT EXISTS app_learning.job_test_effect (job_id UUID NOT NULL, step INTEGER NOT NULL)").update();
    }

    static List<Integer> effects(JdbcClient jdbc, UUID job) {
        return jdbc.sql("SELECT step FROM app_learning.job_test_effect WHERE job_id=:job ORDER BY step").param("job", job).query(Integer.class).list();
    }

    /** One step per slice: writes the effect of step n, then continues with {@code next=n+1} or finishes after the last step. */
    static Function<Job, Slice> steps(JdbcClient jdbc, java.util.function.BiConsumer<Job, Integer> beforeCommit) {
        return job -> {
            int step = job.progress() == null ? 0 : job.progress().path("next").intValue(-1);
            jdbc.sql("INSERT INTO app_learning.job_test_effect(job_id, step) VALUES (:job, :step)").param("job", job.id()).param("step", step).update();
            if (beforeCommit != null) beforeCommit.accept(job, step);
            int steps = job.payload().path("steps").intValue(1);
            if (step + 1 >= steps) return Slice.done(steps);
            return Slice.proceed(JSON.createObjectNode().put("next", step + 1), step + 1);
        };
    }

    static Function<Job, Slice> steps(JdbcClient jdbc) {
        return steps(jdbc, null);
    }
}
