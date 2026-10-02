package app.mnema.learning.usage;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The contested admission path, made deterministic: a barrier on the conditional update makes both racers read the same
 * balance version before either writes, so exactly one of them loses the row version and must re-read.
 */
class UsageContentionTest extends UsageIntegrationTest {
    @MockitoSpyBean private UsageRepository spied;

    private UUID openedFreeAccount() {
        UUID owner = owner(Plan.FREE);
        inTx(() -> ledger.release(owner, ledger.reserve(owner, ReservationScope.TURN, null, null, 1).reservationId()));
        return owner;
    }

    /** Both of the first two conditional updates wait for each other; every update's outcome is recorded. */
    private List<Boolean> contendFirstTwoUpdates() {
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        List<Boolean> outcomes = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            if (calls.incrementAndGet() <= 2) bothHaveRead.await();
            Object result = invocation.callRealMethod();
            outcomes.add((Boolean) result);
            return result;
        }).when(spied).tryReserve(any(), anyString(), anyInt(), anyLong(), any());
        return outcomes;
    }

    private Object admit(UUID owner, int credits) {
        try {
            return inTx(() -> ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, credits));
        } catch (UsageLimitReachedException refused) {
            return refused;
        }
    }

    @Test
    void theLoserOfTheRowVersionRereadsAndIsRefusedWhenNothingFitsAnyMore() throws Exception {
        UUID owner = openedFreeAccount();
        List<Boolean> outcomes = contendFirstTwoUpdates();

        List<Object> results = race(owner, 10);

        assertThat(results.stream().filter(Reservation.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(UsageLimitReachedException.class::isInstance)).hasSize(1);
        // One update won, one lost the version and re-read (and then saw 3 credits left, so no third update).
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(balance(owner)).containsExactly(13, 0, 10);
    }

    @Test
    void theLoserOfTheRowVersionRetriesAndSucceedsWhenBothFit() throws Exception {
        UUID owner = owner(Plan.PLUS);
        inTx(() -> ledger.release(owner, ledger.reserve(owner, ReservationScope.TURN, null, null, 1).reservationId()));
        List<Boolean> outcomes = contendFirstTwoUpdates();

        List<Object> results = race(owner, 10);

        assertThat(results).allMatch(Reservation.class::isInstance);
        // True, false (the lost race), true (the retry): the retry happened.
        assertThat(outcomes).containsExactlyInAnyOrder(true, false, true);
        assertThat(balance(owner)).containsExactly(360, 0, 20);
    }

    private List<Object> race(UUID owner, int credits) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Object> first = pool.submit(() -> admit(owner, credits));
            Future<Object> second = pool.submit(() -> admit(owner, credits));
            return List.of(first.get(), second.get());
        }
    }

    @Test
    void anAdmissionThatKeepsLosingTheRowIsARetryable503NotAGenericError() throws Exception {
        UUID owner = openedFreeAccount();
        clearInvocations(spied);
        doReturn(false).when(spied).tryReserve(any(), anyString(), anyInt(), anyLong(), any());

        assertThatThrownBy(() -> inTx(() -> ledger.reserve(owner, ReservationScope.TURN, null, null, 5)))
                .isInstanceOf(UsageContentionException.class);
        verify(spied, times(32)).tryReserve(any(), anyString(), anyInt(), anyLong(), any());
        assertThat(balance(owner)).containsExactly(13, 0, 0);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdmissionProbe(ledger, transactions))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var response = mvc.perform(get("/decks/" + UUID.randomUUID() + "/generation-sessions")
                .header("X-Owner", owner.toString()).header("X-Credits", 5)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        var body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(body.path("code").stringValue(null)).isEqualTo("USAGE_UNAVAILABLE");
        assertThat(body.path("type").stringValue(null)).isEqualTo("urn:mnema:problem:usage-unavailable");
        assertThat(response.getContentAsString()).doesNotContain("Exception", "contended");
    }
}
