package app.mnema.learning.billing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The timer of the receipt worker: a failed pass never kills it, and only the worker roles have one. */
class NpdReceiptScheduleTest {
    @Test
    void aPassRunsTheWorkerAndAFailedPassIsSwallowed() {
        NpdReceiptWorker worker = mock(NpdReceiptWorker.class);
        NpdReceiptSchedule schedule = new NpdReceiptSchedule(worker);

        schedule.run();
        verify(worker).runOnce();

        doThrow(new IllegalStateException("boom")).when(worker).runOnce();
        assertThatCode(schedule::run).doesNotThrowAnyException();
    }

    @Test
    void onlyTheWorkerAndAllRolesHaveATimer() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withBean(NpdReceiptWorker.class, () -> mock(NpdReceiptWorker.class))
                .withUserConfiguration(NpdReceiptSchedule.class);

        runner.withPropertyValues("learning.runtime.roles=api").run(context -> assertThat(context).doesNotHaveBean(NpdReceiptSchedule.class));
        runner.withPropertyValues("learning.runtime.roles=worker").run(context -> assertThat(context).hasSingleBean(NpdReceiptSchedule.class));
        runner.withPropertyValues("learning.runtime.roles= ALL ").run(context -> assertThat(context).hasSingleBean(NpdReceiptSchedule.class));
        runner.run(context -> assertThat(context).hasSingleBean(NpdReceiptSchedule.class));
    }
}
