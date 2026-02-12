package app.mnema.ai.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AiQuotaService {

    private final JdbcTemplate jdbcTemplate;
    private final Integer defaultTokensLimit;

    public AiQuotaService(JdbcTemplate jdbcTemplate,
                          @Value("${app.ai.quota.default-tokens-limit:}") String defaultTokensLimit) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaultTokensLimit = parseLimit(defaultTokensLimit);
    }

    @Transactional
    public void consumeTokens(UUID userId, int tokens) {
        if (tokens <= 0) {
            return;
        }
        BillingPeriod period = resolveBillingPeriod(userId);
        LocalDate periodStart = period.start();
        ensureQuotaRow(userId, periodStart, period.end());
        int updated = jdbcTemplate.update(
                """
                update app_ai.ai_quota
                set tokens_used = tokens_used + ?,
                    updated_at = now()
                where user_id = ?
                  and period_start = ?
                  and (tokens_limit is null or tokens_used + ? <= tokens_limit)
                """,
                tokens,
                userId,
                periodStart,
                tokens
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Quota exceeded");
        }
    }

    public LocalDate currentPeriodStart(UUID userId) {
        return resolveBillingPeriod(userId).start();
    }

    public LocalDate currentPeriodEnd(UUID userId) {
        return resolveBillingPeriod(userId).end();
    }

    @Transactional
    public void ensureQuotaRow(UUID userId, LocalDate periodStart, LocalDate periodEnd) {
        jdbcTemplate.update(
                """
                insert into app_ai.ai_quota (user_id, period_start, period_end, tokens_limit, tokens_used, updated_at)
                values (?, ?, ?, ?, 0, now())
                on conflict (user_id, period_start) do nothing
                """,
                userId,
                periodStart,
                periodEnd,
                defaultTokensLimit
        );
    }

    private BillingPeriod resolveBillingPeriod(UUID userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int anchorDay = resolveBillingAnchor(userId);
        YearMonth currentMonth = YearMonth.from(today);
        LocalDate currentAnchor = anchoredDate(currentMonth, anchorDay);
        LocalDate start = today.isBefore(currentAnchor)
                ? anchoredDate(currentMonth.minusMonths(1), anchorDay)
                : currentAnchor;
        LocalDate end = anchoredDate(YearMonth.from(start).plusMonths(1), anchorDay);
        return new BillingPeriod(start, end);
    }

    private int resolveBillingAnchor(UUID userId) {
        Integer anchor = jdbcTemplate.query(
                "select billing_anchor from app_ai.subscriptions where user_id = ?",
                rs -> rs.next() ? rs.getInt("billing_anchor") : null,
                userId
        );
        if (anchor == null || anchor < 1) {
            return 1;
        }
        return Math.min(anchor, 31);
    }

    private LocalDate anchoredDate(YearMonth month, int anchorDay) {
        int day = Math.min(anchorDay, month.lengthOfMonth());
        return month.atDay(day);
    }

    private Integer parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid app.ai.quota.default-tokens-limit value");
        }
    }

    private record BillingPeriod(LocalDate start, LocalDate end) {
    }
}
