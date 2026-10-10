package app.mnema.learning.admin;

import app.mnema.learning.platform.api.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** UTC half-open calendar interval; allowing tomorrow as the exclusive end includes the current day. */
public record AdminReportRange(LocalDate from, LocalDate to) {
    public static AdminReportRange parse(String from, String to, Instant now) {
        try {
            if (from == null || to == null || !from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new InvalidRequestException();
            LocalDate start = LocalDate.parse(from);
            LocalDate end = LocalDate.parse(to);
            long days = ChronoUnit.DAYS.between(start, end);
            if (start.getYear() < 1 || end.getYear() < 1 || !start.toString().equals(from) || !end.toString().equals(to) || days < 1 || days > 90
                    || end.isAfter(now.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1))) throw new InvalidRequestException();
            return new AdminReportRange(start, end);
        } catch (java.time.DateTimeException | NullPointerException failure) { throw new InvalidRequestException(); }
    }

    public Instant start() { return from.atStartOfDay(ZoneOffset.UTC).toInstant(); }
    public Instant end() { return to.atStartOfDay(ZoneOffset.UTC).toInstant(); }

    public static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((key, values) -> {
            if (!allowed.contains(key) || values.length != 1) throw new InvalidRequestException();
        });
    }
}
