package app.mnema.core.moderation;

import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.PublicCardRepository;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.security.ContentAdminAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModerationReportServiceTest {

    @Mock
    ModerationReportRepository moderationReportRepository;

    @Mock
    PublicDeckRepository publicDeckRepository;

    @Mock
    PublicCardRepository publicCardRepository;

    @Mock
    CardTemplateRepository cardTemplateRepository;

    @Mock
    ContentAdminAccessService contentAdminAccessService;

    @InjectMocks
    ModerationReportService moderationReportService;

    @Test
    void createReport_persistsOpenDeckReport() {
        UUID reporterId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        when(publicDeckRepository.findLatestByDeckId(deckId))
                .thenReturn(Optional.of(publicDeck(deckId, authorId, "Shared English")));
        when(moderationReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
                reporterId,
                ModerationReportTargetType.DECK,
                deckId,
                ModerationReportStatus.OPEN
        )).thenReturn(false);
        when(moderationReportRepository.save(any(ModerationReportEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModerationReportService.ModerationReportDto result = moderationReportService.createReport(
                reporterId,
                "reporter",
                ModerationReportTargetType.DECK,
                deckId,
                ModerationReportReason.FACTUAL_ERROR,
                "Wrong translation on the first cards"
        );

        ArgumentCaptor<ModerationReportEntity> captor = ArgumentCaptor.forClass(ModerationReportEntity.class);
        verify(moderationReportRepository).save(captor.capture());
        assertThat(captor.getValue().getTargetTitle()).isEqualTo("Shared English");
        assertThat(captor.getValue().getReason()).isEqualTo(ModerationReportReason.FACTUAL_ERROR);
        assertThat(result.status()).isEqualTo(ModerationReportStatus.OPEN);
        assertThat(result.reporterUsername()).isEqualTo("reporter");
    }

    @Test
    void createReport_rejectsDuplicateOpenReport() {
        UUID reporterId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();

        when(cardTemplateRepository.findById(templateId))
                .thenReturn(Optional.of(new CardTemplateEntity(
                        templateId,
                        UUID.randomUUID(),
                        "Template",
                        "",
                        true,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        1
                )));
        when(moderationReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(
                reporterId,
                ModerationReportTargetType.TEMPLATE,
                templateId,
                ModerationReportStatus.OPEN
        )).thenReturn(true);

        assertThatThrownBy(() -> moderationReportService.createReport(
                reporterId,
                "reporter",
                ModerationReportTargetType.TEMPLATE,
                templateId,
                ModerationReportReason.SPAM,
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void closeReport_marksReportClosedByAdmin() {
        UUID adminId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ModerationReportEntity report = new ModerationReportEntity(
                reportId,
                ModerationReportTargetType.CARD,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Card from Shared English",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "reporter",
                ModerationReportReason.BROKEN_FORMATTING,
                "Audio is missing"
        );

        when(moderationReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        ModerationReportService.ModerationReportDto result = moderationReportService.closeReport(
                adminId,
                "root-admin",
                reportId,
                "Removed broken media references"
        );

        verify(contentAdminAccessService).requireActiveAdmin(adminId);
        assertThat(result.status()).isEqualTo(ModerationReportStatus.CLOSED);
        assertThat(result.closedByUserId()).isEqualTo(adminId);
        assertThat(result.closedByUsername()).isEqualTo("root-admin");
        assertThat(result.resolutionNote()).isEqualTo("Removed broken media references");
    }

    @Test
    void createReport_buildsCardTargetTitleFromPreview() {
        UUID cardId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Bonjour");
        PublicDeckEntity deck = publicDeck(deckId, authorId, "French basics");
        PublicCardEntity card = new PublicCardEntity(
                deckId,
                3,
                deck,
                cardId,
                content,
                1,
                new String[0],
                Instant.now(),
                Instant.now(),
                true,
                "checksum"
        );

        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(cardId)).thenReturn(Optional.of(card));
        when(publicDeckRepository.findByDeckIdAndVersion(deckId, 3)).thenReturn(Optional.of(deck));
        when(moderationReportRepository.existsByReporterIdAndTargetTypeAndTargetIdAndStatus(any(), any(), any(), any()))
                .thenReturn(false);
        when(moderationReportRepository.save(any(ModerationReportEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ModerationReportService.ModerationReportDto result = moderationReportService.createReport(
                UUID.randomUUID(),
                "reporter",
                ModerationReportTargetType.CARD,
                cardId,
                ModerationReportReason.FACTUAL_ERROR,
                null
        );

        assertThat(result.targetTitle()).contains("French basics");
        assertThat(result.targetTitle()).contains("Bonjour");
    }

    @Test
    void createReport_validatesReporterOtherReasonAndDetailsLength() {
        UUID deckId = UUID.randomUUID();

        assertThatThrownBy(() -> moderationReportService.createReport(
                UUID.randomUUID(),
                "   ",
                ModerationReportTargetType.DECK,
                deckId,
                ModerationReportReason.SPAM,
                null
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> moderationReportService.createReport(
                UUID.randomUUID(),
                "reporter",
                ModerationReportTargetType.DECK,
                deckId,
                ModerationReportReason.OTHER,
                "   "
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> moderationReportService.createReport(
                UUID.randomUUID(),
                "reporter",
                ModerationReportTargetType.DECK,
                deckId,
                ModerationReportReason.SPAM,
                "x".repeat(501)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getOpenReportsAndClosedReportsRequireAdminAndCapPageSize() {
        UUID adminId = UUID.randomUUID();
        ModerationReportEntity open = openReport("Open");
        ModerationReportEntity closed = openReport("Closed");
        closed.close(adminId, "root", "resolved");
        when(moderationReportRepository.findByStatusOrderByCreatedAtAsc(
                ModerationReportStatus.OPEN,
                PageRequest.of(1, 50)
        )).thenReturn(new PageImpl<>(List.of(open)));
        when(moderationReportRepository.findByStatusOrderByClosedAtDescCreatedAtDesc(
                ModerationReportStatus.CLOSED,
                PageRequest.of(0, 5)
        )).thenReturn(new PageImpl<>(List.of(closed)));

        assertThat(moderationReportService.getOpenReports(adminId, 2, 500).getContent()).hasSize(1);
        assertThat(moderationReportService.getClosedReports(adminId, 1, 5).getContent()).hasSize(1);
        verify(contentAdminAccessService, times(2)).requireActiveAdmin(adminId);
    }

    @Test
    void closeReport_rejectsMissingAlreadyClosedAndBlankAdminUsername() {
        UUID adminId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        ModerationReportEntity closed = openReport("Closed");
        closed.close(adminId, "root", "done");
        when(moderationReportRepository.findById(reportId)).thenReturn(Optional.empty());
        when(moderationReportRepository.findById(closed.getReportId())).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> moderationReportService.closeReport(adminId, "root", reportId, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> moderationReportService.closeReport(adminId, "root", closed.getReportId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        ModerationReportEntity open = openReport("Open");
        when(moderationReportRepository.findById(open.getReportId())).thenReturn(Optional.of(open));
        assertThatThrownBy(() -> moderationReportService.closeReport(adminId, "   ", open.getReportId(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void getStats_aggregatesStatusTargetReasonAndResolvers() {
        UUID adminId = UUID.randomUUID();
        UUID resolverId = UUID.randomUUID();
        when(moderationReportRepository.countByStatus()).thenReturn(List.of(
                statusCount(ModerationReportStatus.OPEN, 3),
                statusCount(ModerationReportStatus.CLOSED, 5)
        ));
        when(moderationReportRepository.countByTargetType()).thenReturn(List.of(
                targetCount(ModerationReportTargetType.DECK, 2),
                targetCount(ModerationReportTargetType.CARD, 4)
        ));
        when(moderationReportRepository.countByReason()).thenReturn(List.of(
                reasonCount(ModerationReportReason.SPAM, 6),
                reasonCount(ModerationReportReason.OTHER, 1)
        ));
        when(moderationReportRepository.countClosedByResolver()).thenReturn(List.of(
                resolverCount(resolverId, "root", 5)
        ));

        ModerationReportService.ModerationReportStatsDto stats = moderationReportService.getStats(adminId);

        assertThat(stats.totalOpen()).isEqualTo(3);
        assertThat(stats.totalClosed()).isEqualTo(5);
        assertThat(stats.targetBreakdown()).contains(new ModerationReportService.CountSliceDto("DECK", 2));
        assertThat(stats.targetBreakdown()).contains(new ModerationReportService.CountSliceDto("TEMPLATE", 0));
        assertThat(stats.reasonBreakdown()).contains(new ModerationReportService.CountSliceDto("SPAM", 6));
        assertThat(stats.resolverBreakdown()).containsExactly(new ModerationReportService.ResolverStatsDto(resolverId, "root", 5));
    }

    @Test
    void createReport_rejectsNonPublicTargets() {
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        PublicDeckEntity privateDeck = publicDeck(deckId, UUID.randomUUID(), "Private");
        privateDeck.setPublicFlag(false);
        PublicCardEntity card = new PublicCardEntity(
                deckId,
                1,
                privateDeck,
                cardId,
                new ObjectMapper().createObjectNode(),
                1,
                new String[0],
                Instant.now(),
                Instant.now(),
                true,
                "checksum"
        );
        CardTemplateEntity privateTemplate = new CardTemplateEntity(
                templateId,
                UUID.randomUUID(),
                "Template",
                "",
                false,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                1
        );
        when(publicDeckRepository.findLatestByDeckId(deckId)).thenReturn(Optional.of(privateDeck));
        when(publicCardRepository.findFirstByCardIdOrderByDeckVersionDesc(cardId)).thenReturn(Optional.of(card));
        when(publicDeckRepository.findByDeckIdAndVersion(deckId, 1)).thenReturn(Optional.of(privateDeck));
        when(cardTemplateRepository.findById(templateId)).thenReturn(Optional.of(privateTemplate));

        assertThatThrownBy(() -> moderationReportService.createReport(
                UUID.randomUUID(), "reporter", ModerationReportTargetType.DECK, deckId, ModerationReportReason.SPAM, null
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> moderationReportService.createReport(
                UUID.randomUUID(), "reporter", ModerationReportTargetType.CARD, cardId, ModerationReportReason.SPAM, null
        )).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> moderationReportService.createReport(
                UUID.randomUUID(), "reporter", ModerationReportTargetType.TEMPLATE, templateId, ModerationReportReason.SPAM, null
        )).isInstanceOf(ResponseStatusException.class);
    }

    private PublicDeckEntity publicDeck(UUID deckId, UUID authorId, String name) {
        return new PublicDeckEntity(
                deckId,
                1,
                authorId,
                name,
                "Description",
                null,
                UUID.randomUUID(),
                1,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null
        );
    }

    private ModerationReportEntity openReport(String title) {
        return new ModerationReportEntity(
                UUID.randomUUID(),
                ModerationReportTargetType.DECK,
                UUID.randomUUID(),
                null,
                title,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "reporter",
                ModerationReportReason.SPAM,
                "details"
        );
    }

    private ModerationReportRepository.StatusCountProjection statusCount(ModerationReportStatus status, long count) {
        return new ModerationReportRepository.StatusCountProjection() {
            @Override
            public ModerationReportStatus getStatus() {
                return status;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private ModerationReportRepository.TargetTypeCountProjection targetCount(ModerationReportTargetType type, long count) {
        return new ModerationReportRepository.TargetTypeCountProjection() {
            @Override
            public ModerationReportTargetType getTargetType() {
                return type;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private ModerationReportRepository.ReasonCountProjection reasonCount(ModerationReportReason reason, long count) {
        return new ModerationReportRepository.ReasonCountProjection() {
            @Override
            public ModerationReportReason getReason() {
                return reason;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private ModerationReportRepository.ResolverCountProjection resolverCount(UUID adminId, String username, long count) {
        return new ModerationReportRepository.ResolverCountProjection() {
            @Override
            public UUID getClosedByUserId() {
                return adminId;
            }

            @Override
            public String getClosedByUsername() {
                return username;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }
}
