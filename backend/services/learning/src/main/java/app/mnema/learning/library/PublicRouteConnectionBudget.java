package app.mnema.learning.library;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Fails the start when the public routes could take the connections the rest of the service needs. Each public read holds at most one pooled connection,
 * so {@code max-concurrent} reads can hold that many; the background job executor holds one per running slice ({@code learning.jobs.max-concurrency}, only in
 * the {@code worker} and {@code all} roles and only when jobs are enabled). The pool must keep at least {@value #RESERVED} connections beyond both for the
 * private API and the other workers, otherwise the public bulkhead would not protect anything. Checked only when the public routes are enabled; the
 * pool size is the effective {@code spring.datasource.hikari.maximum-pool-size} of the running pool.
 */
@Component
final class PublicRouteConnectionBudget {
    static final int RESERVED = 3;

    PublicRouteConnectionBudget(PublicRouteSettings settings, DataSource dataSource,
                                @Value("${learning.jobs.max-concurrency:4}") int jobConcurrency,
                                @Value("${learning.jobs.enabled:true}") boolean jobsEnabled,
                                @Value("${learning.runtime.roles:all}") String roles) {
        if (!settings.enabled) return;
        boolean worker = !"api".equals(roles == null ? "" : roles.strip().toLowerCase(Locale.ROOT));
        check(poolSize(dataSource), settings.maxConcurrent, worker && jobsEnabled ? jobConcurrency : 0);
    }

    static void check(int pool, int publicReads, int jobSlices) {
        if (pool - publicReads - jobSlices < RESERVED) {
            throw new IllegalStateException("learning.community.public-routes.max-concurrent (" + publicReads + ") + learning.jobs.max-concurrency ("
                    + jobSlices + ") leaves " + (pool - publicReads - jobSlices) + " of the " + pool + " pooled database connections; at least " + RESERVED
                    + " must stay free for the private API: lower them or raise spring.datasource.hikari.maximum-pool-size");
        }
    }

    private static int poolSize(DataSource dataSource) {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        } catch (SQLException failure) {
            // not a Hikari pool: fall through to the Spring Boot default
        }
        return 10;
    }
}
