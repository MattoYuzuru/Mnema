package app.mnema.learning.fork;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The "failed" state of a copy: unfinished, and its job ended without success or its record is gone. */
class ForkLinkTest {
    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID BASE = UUID.randomUUID();

    private static ForkLink of(boolean preparing, String jobState, boolean detached) {
        return ForkLink.of(SOURCE, "c", preparing, jobState, detached, BASE, false, false, null);
    }

    @Test
    void anUnfinishedCopyIsFailedExactlyWhenItsJobCannotFinishIt() {
        assertThat(of(true, "QUEUED", false).failed()).isFalse();
        assertThat(of(true, "RUNNING", false).failed()).isFalse();
        assertThat(of(true, "FAILED", false).failed()).isTrue();
        assertThat(of(true, "CANCELLED", false).failed()).isTrue();
        assertThat(of(true, null, false).failed()).as("the job's record was purged").isTrue();
    }

    @Test
    void aReadyCopyIsNeverFailedWhateverBecameOfItsJob() {
        assertThat(of(false, "SUCCEEDED", false).failed()).isFalse();
        assertThat(of(false, null, true)).isEqualTo(new ForkLink(SOURCE, "c", false, false, true, BASE, false, false, null));
    }
}
