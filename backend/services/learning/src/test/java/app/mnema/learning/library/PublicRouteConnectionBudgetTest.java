package app.mnema.learning.library;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicRouteConnectionBudgetTest {
    private static HikariDataSource pool(int size) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setMaximumPoolSize(size);
        return dataSource;
    }

    private static PublicRouteSettings settings(boolean enabled, int concurrent) {
        return new PublicRouteSettings(enabled, 120, 600, 600, 600, 50_000, concurrent);
    }

    @Test
    void theDefaultsFitTheDefaultPoolWithTheReservedConnectionsLeft() {
        assertThatCode(() -> new PublicRouteConnectionBudget(settings(true, 3), pool(10), 4, true, "all")).doesNotThrowAnyException();
        assertThatCode(() -> PublicRouteConnectionBudget.check(10, 3, 4)).doesNotThrowAnyException();
    }

    @Test
    void aBudgetThatLeavesTooFewConnectionsStopsTheStartWithAClearMessage() {
        assertThatThrownBy(() -> new PublicRouteConnectionBudget(settings(true, 4), pool(10), 4, true, "all"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("learning.community.public-routes.max-concurrent (4)").hasMessageContaining("learning.jobs.max-concurrency (4)")
                .hasMessageContaining("leaves 2 of the 10").hasMessageContaining("spring.datasource.hikari.maximum-pool-size");
        assertThatThrownBy(() -> new PublicRouteConnectionBudget(settings(true, 3), pool(6), 0 + 4, true, "worker")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void jobsCountOnlyWhereTheExecutorRunsAndPublicReadsOnlyWhenEnabled() {
        // the api role has no job executor, disabled jobs hold nothing, disabled public routes hold nothing
        assertThatCode(() -> new PublicRouteConnectionBudget(settings(true, 3), pool(7), 40, true, "api")).doesNotThrowAnyException();
        assertThatCode(() -> new PublicRouteConnectionBudget(settings(true, 3), pool(7), 40, false, "all")).doesNotThrowAnyException();
        assertThatCode(() -> new PublicRouteConnectionBudget(settings(false, 64), pool(3), 64, true, "all")).doesNotThrowAnyException();
        assertThat(PublicRouteConnectionBudget.RESERVED).isEqualTo(3);
    }
}
