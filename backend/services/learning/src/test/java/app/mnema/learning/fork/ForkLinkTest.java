package app.mnema.learning.fork;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The "failed" state of a copy: unfinished, and its job ended without success or its record is gone. */
class ForkLinkTest {
    private static final UUID SOURCE = UUID.randomUUID();

    @Test
    void anUnfinishedCopyIsFailedExactlyWhenItsJobCannotFinishIt() {
        assertThat(ForkLink.of(SOURCE, "c", true, "QUEUED", false).failed()).isFalse();
        assertThat(ForkLink.of(SOURCE, "c", true, "RUNNING", false).failed()).isFalse();
        assertThat(ForkLink.of(SOURCE, "c", true, "FAILED", false).failed()).isTrue();
        assertThat(ForkLink.of(SOURCE, "c", true, "CANCELLED", false).failed()).isTrue();
        assertThat(ForkLink.of(SOURCE, "c", true, null, false).failed()).as("the job's record was purged").isTrue();
    }

    @Test
    void aReadyCopyIsNeverFailedWhateverBecameOfItsJob() {
        assertThat(ForkLink.of(SOURCE, "c", false, "SUCCEEDED", false).failed()).isFalse();
        assertThat(ForkLink.of(SOURCE, "c", false, null, true)).isEqualTo(new ForkLink(SOURCE, "c", false, false, true));
    }
}
