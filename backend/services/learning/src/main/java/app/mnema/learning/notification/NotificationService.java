package app.mnema.learning.notification;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Owner-scoped reads and commands of the notification center. */
@Service
class NotificationService {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final NotificationRepository repository;
    private final ActiveWorkCounter activeWork;

    NotificationService(NotificationRepository repository, ActiveWorkCounter activeWork) {
        this.repository = repository;
        this.activeWork = activeWork;
    }

    /**
     * One snapshot for the validator, the unread count and the page, so they cannot disagree. The validator is computed
     * first and the page is only read when the client's copy is stale.
     *
     * @param after catch-up position ({@code seq} greater than it, ascending) or null for the newest-first listing
     * @param ifNoneMatch the request header, or null
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Listing list(UUID owner, int limit, Long after, NotificationCursor cursor, String ifNoneMatch) {
        if (after != null && cursor != null) throw new InvalidRequestException();
        var stats = repository.stats(owner);
        int work = activeWork.count(owner);
        String etag = etag(stats, work, limit, after, cursor);
        if (matches(ifNoneMatch, etag)) return new Listing(etag, null);
        boolean ascending = after != null || (cursor != null && cursor.ascending());
        List<NotificationRepository.Row> rows = ascending
                ? repository.oldestAfter(owner, after != null ? after : cursor.seq(), limit + 1)
                : repository.newestBefore(owner, cursor != null ? cursor.seq() : Long.MAX_VALUE, limit + 1);
        boolean more = rows.size() > limit;
        List<NotificationRepository.Row> page = more ? rows.subList(0, limit) : rows;
        String next = more ? new NotificationCursor(ascending, page.getLast().seq()).encode() : null;
        return new Listing(etag, new Page(page.stream().map(NotificationService::view).toList(), stats.unread(),
                Long.toString(stats.readUpto()), work, next));
    }

    /**
     * Moves the read watermark to the maximum of the stored value and {@code readUpto}. A value above the latest
     * {@code seq} could mark notifications that do not exist yet as read, so it is rejected.
     */
    @Transactional
    ReadCursor advanceReadCursor(UUID owner, long readUpto) {
        long latest = repository.lockedCursor(owner).map(NotificationRepository.CursorRow::lastSeq).orElse(0L);
        if (readUpto > latest) throw new InvalidRequestException();
        if (latest > 0) repository.raiseReadUpto(owner, readUpto);
        var stats = repository.stats(owner);
        return new ReadCursor(Long.toString(stats.readUpto()), stats.unread());
    }

    /** Idempotent; a foreign, expired or absent notification is the opaque not-found. */
    @Transactional
    void dismiss(UUID owner, UUID notification) {
        if (!repository.dismiss(owner, notification)) throw new ResourceNotFoundException();
    }

    private static View view(NotificationRepository.Row row) {
        return new View(row.id(), Long.toString(row.seq()), row.kind(), row.severity(), JSON.readTree(row.params()), row.route(),
                row.createdAt().toString(), row.expiresAt().toString());
    }

    /**
     * Strong and opaque. It covers everything the response depends on: the newest seq, the read watermark, the number
     * of visible notifications (a dismissal, expiry or eviction lowers it; a publication raises the seq), the active
     * work, the earliest expiry boundary and the query that selects the page.
     */
    static String etag(NotificationRepository.Stats stats, int activeWork, int limit, Long after,
                       NotificationCursor cursor) {
        long expiry = stats.earliestExpiry() == null ? 0 : stats.earliestExpiry().getEpochSecond();
        String query = limit + "|" + after + "|" + (cursor == null ? "" : cursor.encode());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8));
            return "\"n-" + stats.latestSeq() + "-" + stats.readUpto() + "-" + stats.visible() + "-" + activeWork + "-"
                    + expiry + "-" + HexFormat.of().formatHex(digest, 0, 6) + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** RFC 9110 weak comparison of a validator list; {@code *} matches any current representation. */
    static boolean matches(String header, String etag) {
        if (header == null) return false;
        for (String candidate : header.split(",")) {
            String value = candidate.strip();
            if (value.startsWith("W/")) value = value.substring(2);
            if (value.equals("*") || value.equals(etag)) return true;
        }
        return false;
    }

    /** {@code page} is null when the client's validator is current: a 304 without a body. */
    record Listing(String etag, Page page) { }

    record Page(List<View> items, long unreadCount, String readUpto, int activeWork, String nextCursor) { }

    /** Wire shape of one notification; {@code params} is the stored document of identifiers, counts and enums. */
    record View(UUID notificationId, String seq, String kind, String severity, JsonNode params,
                String route, String createdAt, String expiresAt) { }

    record ReadCursor(String readUpto, long unreadCount) { }
}
