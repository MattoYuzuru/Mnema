package app.mnema.learning.platform.jobs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode object(int bytes) {
        return JSON.createObjectNode().put("x", "a".repeat(bytes));
    }

    @Test
    void aSliceCarriesABoundedCursorAndANonNegativeCount() {
        assertThat(Slice.proceed(object(10), 3)).isEqualTo(new Slice.Continue(object(10), 3));
        assertThat(Slice.done(7)).isEqualTo(new Slice.Done(7));
        assertThatThrownBy(() -> Slice.proceed(null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Slice.proceed(object(10), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Slice.done(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Slice.proceed(object(JobJson.MAX_BYTES), 0)).hasMessageContaining("progress");
    }

    @Test
    void jsonIsBoundedInBytesAndReadBackAsAnObject() {
        assertThat(JobJson.write(object(JobJson.MAX_BYTES - 8), "payload")).hasSize(JobJson.MAX_BYTES);
        assertThatThrownBy(() -> JobJson.write(object(JobJson.MAX_BYTES - 7), "payload")).hasMessageContaining("payload");
        // two bytes per character in UTF-8: the bound is on bytes, not on characters
        assertThatThrownBy(() -> JobJson.write(JSON.createObjectNode().put("x", "я".repeat(JobJson.MAX_BYTES / 2)), "payload"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(JobJson.read("{\"a\":1}").path("a").intValue(0)).isEqualTo(1);
        assertThat(JobJson.read(null)).isNull();
        assertThat(JobJson.read("[1]")).isNull();
        assertThat(JobJson.read("{broken")).isNull();
    }

    @Test
    void errorCodesAreShortAndNeverFreeText() {
        assertThat(new RetryableJobException("storage_busy.1").code()).isEqualTo("storage_busy.1");
        assertThat(new PermanentJobException("source_gone").code()).isEqualTo("source_gone");
        for (String bad : new String[] {null, "", "has space", "почему", "x".repeat(65)}) {
            assertThatThrownBy(() -> new RetryableJobException(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PermanentJobException(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void onlyTheThreeEndedStatesAreTerminal() {
        assertThat(JobState.values()).filteredOn(JobState::terminal).containsExactly(JobState.SUCCEEDED, JobState.FAILED, JobState.CANCELLED);
    }
}
