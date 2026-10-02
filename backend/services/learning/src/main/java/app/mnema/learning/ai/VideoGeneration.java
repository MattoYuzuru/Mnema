package app.mnema.learning.ai;

/** Video generation port, reserved: not part of the first release. Asynchronous: submit, then poll the job. */
public interface VideoGeneration {
    AiResult<Job> submit(Request request);

    AiResult<Job> poll(String jobId);

    record Request(String prompt, int seconds) { }

    record Job(String jobId, boolean done, byte[] bytes) { }
}
