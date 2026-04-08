package app.mnema.core.moderation;

import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.security.ContentAdminAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ModerationReportService {

    private static final int MAX_DETAILS_LENGTH = 500;
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_PAGE_SIZE = 50;

    private final ModerationReportRepository moderationReportRepository;
    private final PublicDeckRepository publicDeckRepository;
    private final PublicCardRepository publicCardRepository;
    private final CardTemplateRepository cardTemplateRepository;
    private final ContentAdminAccessService contentAdminAccessService;

    public ModerationReportService(ModerationReportRepository moderationReportRepository,
                                   PublicDeckRepository publicDeckRepository,
                                   PublicCardRepository publicCardRepository,
                                   CardTemplateRepository cardTemplateRepository,
                                   ContentAdminAccessService contentAdminAccessService) {
        this.moderationReportRepository = moderationReportRepository;
        this.publicDeckRepository = publicDeckRepository;
        this.publicCardRepository = publicCardRepository;
        this.cardTemplateRepository = cardTemplateRepository;
        this.contentAdminAccessService = contentAdminAccessService;
    }

    @Transactional
    public ModerationReportDto createReport(UUID reporterId,
                                            String reporterUsername,
                                            ModerationReportTargetType targetType,
                                            UUID targetId,
                                            ModerationReportReason reason,
                                            String details) {
        String normalizedReporter = normalizeUsername(reporterUsername);
        if (normalizedReporter == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reporter username is required");
        }

        String normalizedDetails = normalizeDetails(details);
        if (reason == ModerationReportReason.OTHER && normalizedDetails == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Details are required for 'other'");
        }

        ReportTarget resolvedTarget = resolveTarget(targetType, targetId);
        if (moderationReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
                reporterId,
                targetType,
                targetId,
                ModerationReportStatus.OPEN
        )) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An open report already exists for this content");
        }

        ModerationReportEntity saved = moderationReportRepository.save(
                new ModerationReportEntity(
                        UUID.randomUUID(),
                        targetType,
                        targetId,
                        resolvedTarget.parentId(),
                        truncate(resolvedTarget.title(), MAX_TITLE_LENGTH),
                        resolvedTarget.ownerId(),
                        reporterId,
                        normalizedReporter,
                        reason,
                        normalizedDetails
                )
        );
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public Page<ModerationReportDto> getOpenReports(UUID adminId, int page, int limit) {
        contentAdminAccessService.requireActiveAdmin(adminId);
        return moderationReportRepository.findByStatusOrderByCreatedAtAsc(
                        ModerationReportStatus.OPEN,
                        pageRequest(page, limit)
                )
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public Page<ModerationReportDto> getClosedReports(UUID adminId, int page, int limit) {
        contentAdminAccessService.requireActiveAdmin(adminId);
        return moderationReportRepository.findByStatusOrderByClosedAtDescCreatedAtDesc(
                        ModerationReportStatus.CLOSED,
                        pageRequest(page, limit)
                )
                .map(this::toDto);
    }

    @Transactional
    public ModerationReportDto closeReport(UUID adminId,
                                           String adminUsername,
                                           UUID reportId,
                                           String resolutionNote) {
        contentAdminAccessService.requireActiveAdmin(adminId);

        ModerationReportEntity report = moderationReportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        if (report.getStatus() == ModerationReportStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report is already closed");
        }

        String normalizedAdmin = normalizeUsername(adminUsername);
        if (normalizedAdmin == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin username is required");
        }

        report.close(adminId, normalizedAdmin, normalizeDetails(resolutionNote));
        return toDto(report);
    }

    @Transactional(readOnly = true)
    public ModerationReportStatsDto getStats(UUID adminId) {
        contentAdminAccessService.requireActiveAdmin(adminId);

        long totalOpen = 0;
        long totalClosed = 0;
        for (ModerationReportRepository.StatusCountProjection row : moderationReportRepository.countByStatus()) {
            if (row.getStatus() == ModerationReportStatus.OPEN) {
                totalOpen = row.getCount();
            } else if (row.getStatus() == ModerationReportStatus.CLOSED) {
                totalClosed = row.getCount();
            }
        }

        List<CountSliceDto> targetSlices = Arrays.stream(ModerationReportTargetType.values())
                .map(type -> new CountSliceDto(type.name(), findTargetCount(type)))
                .toList();
        List<CountSliceDto> reasonSlices = Arrays.stream(ModerationReportReason.values())
                .map(reason -> new CountSliceDto(reason.name(), findReasonCount(reason)))
                .toList();
        List<ResolverStatsDto> resolverStats = moderationReportRepository.countClosedByResolver()
                .stream()
                .map(row -> new ResolverStatsDto(row.getClosedByUserId(), row.getClosedByUsername(), row.getCount()))
                .toList();

        return new ModerationReportStatsDto(totalOpen, totalClosed, targetSlices, reasonSlices, resolverStats);
    }

    private long findTargetCount(ModerationReportTargetType type) {
        return moderationReportRepository.countByTargetType()
                .stream()
                .filter(row -> row.getTargetType() == type)
                .mapToLong(ModerationReportRepository.TargetTypeCountProjection::getCount)
                .findFirst()
                .orElse(0L);
    }

    private long findReasonCount(ModerationReportReason reason) {
        return moderationReportRepository.countByReason()
                .stream()
                .filter(row -> row.getReason() == reason)
                .mapToLong(ModerationReportRepository.ReasonCountProjection::getCount)
                .findFirst()
                .orElse(0L);
    }

    private ReportTarget resolveTarget(ModerationReportTargetType targetType, UUID targetId) {
        return switch (targetType) {
            case DECK -> resolveDeckTarget(targetId);
            case CARD -> resolveCardTarget(targetId);
            case TEMPLATE -> resolveTemplateTarget(targetId);
        };
    }

    private ReportTarget resolveDeckTarget(UUID deckId) {
        PublicDeckEntity deck = publicDeckRepository.findLatestByDeckId(deckId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Public deck not found"));
        if (!deck.isPublicFlag()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Public deck not found");
        }
        return new ReportTarget(deck.getAuthorId(), null, deck.getName());
    }

    private ReportTarget resolveCardTarget(UUID cardId) {
        PublicCardEntity card = publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(cardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Public card not found"));
        PublicDeckEntity deck = publicDeckRepository.findByDeckIdAndVersion(card.getDeckId(), card.getDeckVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Public deck not found"));
        if (!deck.isPublicFlag()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Public card not found");
        }
        String preview = extractCardPreview(card.getContent());
        String title = preview == null
                ? "Card from " + deck.getName()
                : "Card from " + deck.getName() + ": " + preview;
        return new ReportTarget(deck.getAuthorId(), deck.getDeckId(), title);
    }

    private ReportTarget resolveTemplateTarget(UUID templateId) {
        CardTemplateEntity template = cardTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));
        if (!template.isPublic()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
        }
        return new ReportTarget(template.getOwnerId(), null, template.getName());
    }

    private PageRequest pageRequest(int page, int limit) {
        if (page < 1 || limit < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page and limit must be >= 1");
        }
        return PageRequest.of(page - 1, Math.min(limit, MAX_PAGE_SIZE));
    }

    private String normalizeDetails(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_DETAILS_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Details are too long");
        }
        return normalized;
    }

    private String normalizeUsername(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : truncate(normalized, 80);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String extractCardPreview(JsonNode content) {
        if (content == null || content.isNull() || !content.isObject()) {
            return null;
        }
        for (JsonNode value : content) {
            if (value != null && value.isTextual()) {
                String text = value.asText().trim().replaceAll("\\s+", " ");
                if (!text.isEmpty()) {
                    return truncate(text, 96);
                }
            }
        }
        return null;
    }

    private ModerationReportDto toDto(ModerationReportEntity entity) {
        return new ModerationReportDto(
                entity.getReportId(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getTargetParentId(),
                entity.getTargetTitle(),
                entity.getContentOwnerId(),
                entity.getReporterId(),
                entity.getReporterUsername(),
                entity.getReason(),
                entity.getDetails(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getClosedAt(),
                entity.getClosedByUserId(),
                entity.getClosedByUsername(),
                entity.getResolutionNote()
        );
    }

    public record ModerationReportDto(
            UUID reportId,
            ModerationReportTargetType targetType,
            UUID targetId,
            UUID targetParentId,
            String targetTitle,
            UUID contentOwnerId,
            UUID reporterId,
            String reporterUsername,
            ModerationReportReason reason,
            String details,
            ModerationReportStatus status,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            java.time.Instant closedAt,
            UUID closedByUserId,
            String closedByUsername,
            String resolutionNote
    ) {
    }

    public record CountSliceDto(String key, long count) {
    }

    public record ResolverStatsDto(UUID adminId, String username, long resolvedCount) {
    }

    public record ModerationReportStatsDto(
            long totalOpen,
            long totalClosed,
            List<CountSliceDto> targetBreakdown,
            List<CountSliceDto> reasonBreakdown,
            List<ResolverStatsDto> resolverBreakdown
    ) {
    }

    private record ReportTarget(UUID ownerId, UUID parentId, String title) {
    }
}
