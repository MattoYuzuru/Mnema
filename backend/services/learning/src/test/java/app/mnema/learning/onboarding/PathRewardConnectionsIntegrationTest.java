package app.mnema.learning.onboarding;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The issuing of the path reward never nests in a read (OB-402): with a pool smaller than the number of readers, every read of a qualifying account is held at the issuing point
 * together (a barrier inside the issuer) and holds no connection and no transaction there, so the pool is never taken by readers that wait for each other, and every GET answers 200 with
 * its reward. Were the issuing transaction nested in the read's, each reader would hold one connection at the barrier, the pool (three) would be taken by three of the eight, and the
 * barrier would never fill.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "learning.onboarding.reward.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=3", "spring.datasource.hikari.minimum-idle=1"})
class PathRewardConnectionsIntegrationTest extends PathRewardSupport {
    private static final int READERS = 8;

    @MockitoSpyBean private RewardIssuer issuer;
    @Autowired private OnboardingService onboarding;

    private <T> List<T> together(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<T> results = new ArrayList<>();
        try {
            for (Future<T> future : futures) results.add(future.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    @Test
    void manyReadsOfQualifyingAccountsHeldTogetherAtTheIssuingPointHoldNoConnectionAndEveryGetAnswers200() throws Exception {
        List<UUID> accounts = new ArrayList<>();
        for (int index = 0; index < READERS; index++) {
            UUID account = trusted();
            complete(account);
            accounts.add(account);
        }
        discountCampaign(10);
        CyclicBarrier barrier = new CyclicBarrier(READERS);
        List<Integer> connections = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> transactions = Collections.synchronizedList(new ArrayList<>());
        doAnswer(call -> {
            connections.add(StatementCounting.held());
            transactions.add(TransactionSynchronizationManager.isActualTransactionActive());
            barrier.await(30, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(issuer).issue(any());
        double failed = outcome("failed");

        List<HttpResponse<String>> responses = together(READERS, new Callable<HttpResponse<String>>() {
            private int next;

            @Override
            public HttpResponse<String> call() {
                UUID account;
                synchronized (this) {
                    account = accounts.get(next++);
                }
                return get(account);
            }
        });

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(200);
        assertThat(responses).extracting(response -> json(response.body()).path("reward").path("state").stringValue(null)).containsOnly("ACTIVE");
        assertThat(connections).as("every reader reached the issuing point, holding no connection").hasSize(READERS).containsOnly(0);
        assertThat(transactions).as("and no transaction").hasSize(READERS).containsOnly(false);
        for (UUID account : accounts) {
            assertThat(rewards(account)).isOne();
            assertThat(announcements(account)).isOne();
        }
        assertThat(outcome("failed") - failed).isZero();
    }

    @Test
    void theServiceReadHoldsNoConnectionOutsideItsOwnTransactionAndIssuesBeforeItOpens() {
        UUID account = trusted();
        complete(account);
        discountCampaign(10);
        List<Integer> connections = new ArrayList<>();
        doAnswer(call -> {
            connections.add(StatementCounting.held());
            return call.callRealMethod();
        }).when(issuer).issue(any());

        JsonNode view = onboarding.read(account, false, null, Boolean.TRUE);

        assertThat(view.path("reward").path("state").stringValue(null)).isEqualTo("ACTIVE");
        assertThat(connections).containsExactly(0);
        assertThat(StatementCounting.held()).as("the read left nothing borrowed").isZero();
    }
}
