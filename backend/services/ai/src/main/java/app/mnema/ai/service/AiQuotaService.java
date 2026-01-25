package app.mnema.ai.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AiQuotaService {

    private final JdbcTemplate jdbcTemplate;

    public AiQuotaService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void consumeTokens(UUID userId, int tokens) {
        if (tokens <= 0) {
            return;
        }
        LocalDate periodStart = currentPeriodStart();
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

    public LocalDate currentPeriodStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    }
}
