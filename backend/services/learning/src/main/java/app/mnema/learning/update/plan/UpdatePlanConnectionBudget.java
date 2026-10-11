package app.mnema.learning.update.plan;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Fails the start when the update plans could take the connections the rest of the service needs. Each plan holds one pooled connection, so {@code max-concurrent} plans hold that many,
 * next to the public reads ({@code learning.community.public-routes.max-concurrent}, only when the public routes are enabled) and the job executor ({@code learning.jobs.max-concurrency},
 * only in the {@code worker} and {@code all} roles). {@value #FREE} connection must stay free beyond all of them. This is deliberately weaker than the 3 connections the public routes
 * reserve for the private API: at the defaults (pool 10, public reads 3, jobs 4, plans 2) exactly one is left, so the
 * plan does not stop an existing deployment from starting; raise the pool to leave more. Checked when the public routes are enabled (the production shape) or when {@code max-concurrent}
 * was set explicitly; a default on a tiny pool of a test or a one-purpose process is not a configuration anyone chose.
 */
@Component
final class UpdatePlanConnectionBudget {
    static final int FREE = 1;

    UpdatePlanConnectionBudget(UpdatePlanSettings settings, DataSource dataSource, Environment environment,
                               @Value("${learning.community.public-routes.enabled:false}") boolean publicRoutes,
                               @Value("${learning.community.public-routes.max-concurrent:3}") int publicReads,
                               @Value("${learning.jobs.max-concurrency:4}") int jobConcurrency,
                               @Value("${learning.jobs.enabled:true}") boolean jobsEnabled,
                               @Value("${learning.runtime.roles:all}") String roles) {
        if (!publicRoutes && !environment.containsProperty("learning.updates.plan.max-concurrent")) return;
        boolean worker = !"api".equals(roles == null ? "" : roles.strip().toLowerCase(Locale.ROOT));
        check(poolSize(dataSource), settings.maxConcurrent, publicRoutes ? publicReads : 0, worker && jobsEnabled ? jobConcurrency : 0);
    }

    static void check(int pool, int plans, int publicReads, int jobSlices) {
        if (pool - plans - publicReads - jobSlices < FREE) {
            throw new IllegalStateException("learning.updates.plan.max-concurrent (" + plans + ") + learning.community.public-routes.max-concurrent (" + publicReads
                    + ") + learning.jobs.max-concurrency (" + jobSlices + ") leaves " + (pool - plans - publicReads - jobSlices) + " of the " + pool
                    + " pooled database connections; at least " + FREE + " must stay free: lower them or raise spring.datasource.hikari.maximum-pool-size");
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
