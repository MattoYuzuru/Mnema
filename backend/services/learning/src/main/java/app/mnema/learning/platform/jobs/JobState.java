package app.mnema.learning.platform.jobs;

/** The lifecycle of a job: {@code QUEUED -> RUNNING -> SUCCEEDED | FAILED | CANCELLED}, with {@code RUNNING -> QUEUED} for a retry or a lost lease. */
public enum JobState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
