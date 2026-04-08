package app.mnema.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void consumeTokensSkipsNonPositiveRequests() {
        AiQuotaService service = new AiQuotaService(jdbcTemplate, "1000");

        service.consumeTokens(UUID.randomUUID(), 0);

        verify(jdbcTemplate, never()).update(any(String.class), any(), any(), any(), any());
    }

    @Test
    void consumeTokensUsesBillingAnchorAndThrowsWhenQuotaExceeded() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.query(eq("select billing_anchor from app_ai.subscriptions where user_id = ?"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq(userId)))
                .thenReturn(31);
        when(jdbcTemplate.update(eq("""
                insert into app_ai.ai_quota (user_id, period_start, period_end, tokens_limit, tokens_used, updated_at)
                values (?, ?, ?, ?, 0, now())
                on conflict (user_id, period_start) do nothing
                """), eq(userId), any(LocalDate.class), any(LocalDate.class), eq(1000)))
                .thenReturn(1);
        when(jdbcTemplate.update(eq("""
                update app_ai.ai_quota
                set tokens_used = tokens_used + ?,
                    updated_at = now()
                where user_id = ?
                  and period_start = ?
                  and (tokens_limit is null or tokens_used + ? <= tokens_limit)
                """), eq(5), eq(userId), any(LocalDate.class), eq(5)))
                .thenReturn(0);

        AiQuotaService service = new AiQuotaService(jdbcTemplate, "1000");

        assertThatThrownBy(() -> service.consumeTokens(userId, 5))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));

        assertThat(service.currentPeriodEnd(userId)).isAfter(service.currentPeriodStart(userId));
    }

    @Test
    void usesDefaultAnchorAndRejectsInvalidConfiguredLimit() {
        UUID userId = UUID.randomUUID();
        when(jdbcTemplate.query(eq("select billing_anchor from app_ai.subscriptions where user_id = ?"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq(userId)))
                .thenReturn(null);

        AiQuotaService service = new AiQuotaService(jdbcTemplate, "");

        assertThat(service.currentPeriodStart(userId).getDayOfMonth()).isEqualTo(1);
        assertThatThrownBy(() -> new AiQuotaService(jdbcTemplate, "oops"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid app.ai.quota.default-tokens-limit value");
    }
}
