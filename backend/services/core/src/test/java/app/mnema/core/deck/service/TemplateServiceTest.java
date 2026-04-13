package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.CardTemplateVersionEntity;
import app.mnema.core.deck.domain.entity.FieldTemplateEntity;
import app.mnema.core.deck.domain.type.CardFieldType;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.CardTemplateVersionRepository;
import app.mnema.core.deck.repository.FieldTemplateRepository;
import app.mnema.core.security.ContentAdminAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    CardTemplateRepository cardTemplateRepository;

    @Mock
    FieldTemplateRepository fieldTemplateRepository;

    @Mock
    CardTemplateVersionRepository cardTemplateVersionRepository;

    @Mock
    ContentAdminAccessService contentAdminAccessService;

    @InjectMocks
    TemplateService templateService;

    @Test
    void getCardTemplatesByPage_delegatesToRepositoryAndJoinsLatestFields() {
        UUID templateId = UUID.randomUUID();

        CardTemplateEntity template = template(templateId, 1, json("{\"layout\":\"entity\"}"), json("{\"ai\":\"entity\"}"), "entity.png");
        Page<CardTemplateEntity> page = new PageImpl<>(List.of(template), PageRequest.of(0, 10), 1);

        when(cardTemplateRepository.findByIsPublicTrueOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(page);
        when(cardTemplateVersionRepository.findByTemplateIdIn(List.of(templateId)))
                .thenReturn(List.of(
                        version(templateId, 1, json("{\"layout\":\"v1\"}"), json("{\"ai\":\"v1\"}"), "v1.png"),
                        version(templateId, 2, json("{\"layout\":\"v2\"}"), json("{\"ai\":\"v2\"}"), "v2.png")
                ));
        when(fieldTemplateRepository.findByTemplateIdIn(List.of(templateId)))
                .thenReturn(List.of(
                        fieldEntity(UUID.randomUUID(), templateId, 1, "oldFront", "Old front", true, true, 0),
                        fieldEntity(UUID.randomUUID(), templateId, 2, "front", "Front", true, true, 1)
                ));

        Page<CardTemplateDTO> result = templateService.getCardTemplatesByPage(1, 10);

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.templateId()).isEqualTo(templateId);
            assertThat(dto.version()).isEqualTo(2);
            assertThat(dto.layout()).isEqualTo(json("{\"layout\":\"v2\"}"));
            assertThat(dto.aiProfile()).isEqualTo(json("{\"ai\":\"v2\"}"));
            assertThat(dto.iconUrl()).isEqualTo("v2.png");
            assertThat(dto.fields()).singleElement().satisfies(field -> assertThat(field.name()).isEqualTo("front"));
        });
    }

    @Test
    void getCardTemplatesByPage_throwsOnInvalidPageOrLimit() {
        assertThatThrownBy(() -> templateService.getCardTemplatesByPage(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page and limit must be >= 1");
        assertThatThrownBy(() -> templateService.getCardTemplatesByPage(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("page and limit must be >= 1");
    }

    @Test
    void getUserTemplatesByPage_returnsEmptyWhenRepositoryPageEmpty() {
        UUID ownerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 5);

        when(cardTemplateRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId, pageable)).thenReturn(Page.empty(pageable));

        Page<CardTemplateDTO> result = templateService.getUserTemplatesByPage(ownerId, 1, 5);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(cardTemplateVersionRepository, fieldTemplateRepository);
    }

    @Test
    void getTemplatesForUserAndPublic_delegatesToRepository() {
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 5);

        when(cardTemplateRepository.findByIsPublicTrueOrOwnerIdOrderByCreatedAtDesc(ownerId, pageable))
                .thenReturn(new PageImpl<>(List.of(template(templateId, 2, json("{\"layout\":\"fallback\"}"), null, null)), pageable, 1));
        when(cardTemplateVersionRepository.findByTemplateIdIn(List.of(templateId))).thenReturn(List.of());
        when(fieldTemplateRepository.findByTemplateIdIn(List.of(templateId))).thenReturn(List.of());

        Page<CardTemplateDTO> result = templateService.getTemplatesForUserAndPublic(ownerId, 1, 5);

        assertThat(result.getContent()).singleElement().satisfies(dto -> {
            assertThat(dto.templateId()).isEqualTo(templateId);
            assertThat(dto.version()).isEqualTo(2);
            assertThat(dto.layout()).isEqualTo(json("{\"layout\":\"fallback\"}"));
        });
    }

    @Test
    void createNewTemplate_usesDtoFieldsWhenSeparateListMissing() {
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        CardTemplateDTO dto = templateDto(
                null,
                "Name",
                "Desc",
                true,
                json("{\"layout\":\"new\"}"),
                json("{\"ai\":\"new\"}"),
                "new.png",
                List.of(frontFieldDto("front"), backFieldDto("back"))
        );

        when(cardTemplateRepository.save(any(CardTemplateEntity.class)))
                .thenReturn(template(templateId, 1, json("{\"layout\":\"new\"}"), json("{\"ai\":\"new\"}"), "new.png"));
        when(cardTemplateVersionRepository.save(any(CardTemplateVersionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(fieldTemplateRepository.saveAll(anyList())).thenReturn(List.of(
                fieldEntity(UUID.randomUUID(), templateId, 1, "front", "Front", true, true, 0),
                fieldEntity(UUID.randomUUID(), templateId, 1, "back", "Back", true, false, 1)
        ));

        CardTemplateDTO result = templateService.createNewTemplate(ownerId, dto, null);

        assertThat(result.templateId()).isEqualTo(templateId);
        assertThat(result.fields()).hasSize(2);
        verify(fieldTemplateRepository).saveAll(anyList());
    }

    @Test
    void createNewTemplate_rejectsInvalidMetaAndFieldLengths() {
        UUID ownerId = UUID.randomUUID();
        CardTemplateDTO longNameDto = templateDto(null, "x".repeat(51), "Desc", true, null, null, null, List.of(frontFieldDto("front"), backFieldDto("back")));

        assertThatThrownBy(() -> templateService.createNewTemplate(ownerId, longNameDto, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException error = (ResponseStatusException) exception;
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo("Template name must be at most 50 characters");
                });

        FieldTemplateDTO invalidField = new FieldTemplateDTO(
                null,
                null,
                "front",
                "L".repeat(51),
                CardFieldType.text,
                true,
                true,
                0,
                null,
                "h".repeat(101)
        );

        assertThatThrownBy(() -> templateService.createNewTemplate(ownerId, templateDto(null, "Name", "Desc", true, null, null, null, null), List.of(invalidField, backFieldDto("back"))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException error = (ResponseStatusException) exception;
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).contains("Field label");
                });
    }

    @Test
    void getCardTemplateById_usesLatestVersionWhenVersionNotSpecified() {
        UUID templateId = UUID.randomUUID();
        CardTemplateEntity template = template(templateId, 2, json("{\"layout\":\"entity\"}"), json("{\"ai\":\"entity\"}"), "entity.png");
        CardTemplateVersionEntity latestVersion = version(templateId, 2, json("{\"layout\":\"v2\"}"), json("{\"ai\":\"v2\"}"), "v2.png");

        when(cardTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));
        when(cardTemplateVersionRepository.findByTemplateIdAndVersion(templateId, 2)).thenReturn(Optional.of(latestVersion));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 2))
                .thenReturn(List.of(
                        fieldEntity(UUID.randomUUID(), templateId, 2, "front", "Front", true, true, 0),
                        fieldEntity(UUID.randomUUID(), templateId, 2, "back", "Back", true, false, 1)
                ));

        CardTemplateDTO result = templateService.getCardTemplateById(templateId, null);

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.layout()).isEqualTo(json("{\"layout\":\"v2\"}"));
        assertThat(result.fields()).hasSize(2);
    }

    @Test
    void getCardTemplateByIdInternal_reusesTemplateLookup() {
        UUID templateId = UUID.randomUUID();
        CardTemplateEntity template = template(templateId, 3, json("{\"layout\":\"entity\"}"), json("{\"ai\":\"entity\"}"), "entity.png");
        CardTemplateVersionEntity requestedVersion = version(templateId, 2, json("{\"layout\":\"v2\"}"), json("{\"ai\":\"v2\"}"), "v2.png");

        when(cardTemplateRepository.findById(templateId)).thenReturn(Optional.of(template));
        when(cardTemplateVersionRepository.findByTemplateIdAndVersion(templateId, 2)).thenReturn(Optional.of(requestedVersion));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 2))
                .thenReturn(List.of(fieldEntity(UUID.randomUUID(), templateId, 2, "front", "Front", true, true, 0)));

        CardTemplateDTO result = templateService.getCardTemplateByIdInternal(templateId, 2);

        assertThat(result.templateId()).isEqualTo(templateId);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.fields()).singleElement().satisfies(field -> assertThat(field.name()).isEqualTo("front"));
    }

    @Test
    void getCardTemplateById_throwsWhenVersionMissing() {
        UUID templateId = UUID.randomUUID();

        when(cardTemplateRepository.findById(templateId)).thenReturn(Optional.of(template(templateId, 3, null, null, null)));
        when(cardTemplateVersionRepository.findByTemplateIdAndVersion(templateId, 7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.getCardTemplateById(templateId, 7))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Template version not found: templateId=" + templateId + ", version=7");
    }

    @Test
    void getTemplateVersions_returnsVersionNumbersAndThrowsWhenTemplateMissing() {
        UUID templateId = UUID.randomUUID();

        when(cardTemplateRepository.findById(templateId)).thenReturn(Optional.of(template(templateId, 3, null, null, null)));
        when(cardTemplateVersionRepository.findByTemplateIdOrderByVersionDesc(templateId))
                .thenReturn(List.of(version(templateId, 3, null, null, null), version(templateId, 2, null, null, null)));

        assertThat(templateService.getTemplateVersions(templateId)).containsExactly(3, 2);

        UUID missingTemplateId = UUID.randomUUID();
        when(cardTemplateRepository.findById(missingTemplateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.getTemplateVersions(missingTemplateId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Template not found: " + missingTemplateId);
    }

    @Test
    void partiallyChangeCardTemplate_updatesMetaWithoutCreatingNewVersion() {
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        CardTemplateEntity template = template(templateId, 1, json("{\"layout\":\"entity\"}"), json("{\"ai\":\"entity\"}"), "entity.png");
        CardTemplateVersionEntity latestVersion = version(templateId, 1, json("{\"layout\":\"v1\"}"), json("{\"ai\":\"v1\"}"), "v1.png");
        when(cardTemplateRepository.findByTemplateIdForUpdate(templateId)).thenReturn(Optional.of(template));
        when(cardTemplateVersionRepository.findByTemplateIdAndVersion(templateId, 1)).thenReturn(Optional.of(latestVersion));
        when(cardTemplateRepository.save(any(CardTemplateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 1))
                .thenReturn(List.of(
                        fieldEntity(UUID.randomUUID(), templateId, 1, "front", "Front", true, true, 0),
                        fieldEntity(UUID.randomUUID(), templateId, 1, "back", "Back", true, false, 1)
                ));

        CardTemplateDTO result = templateService.partiallyChangeCardTemplate(
                ownerId,
                templateId,
                templateDto(templateId, "Renamed", "Updated", false, null, null, null, null)
        );

        assertThat(result.name()).isEqualTo("Renamed");
        assertThat(result.description()).isEqualTo("Updated");
        assertThat(result.isPublic()).isFalse();
        verify(cardTemplateVersionRepository, never()).save(any(CardTemplateVersionEntity.class));
    }

    @Test
    void partiallyChangeCardTemplate_createsNewVersionWhenVersionedFieldsChange() {
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        CardTemplateEntity template = template(templateId, 1, json("{\"layout\":\"entity\"}"), json("{\"ai\":\"entity\"}"), "entity.png");
        CardTemplateVersionEntity latestVersion = version(templateId, 1, json("{\"layout\":\"v1\"}"), json("{\"ai\":\"v1\"}"), "v1.png");
        List<FieldTemplateEntity> currentFields = List.of(
                fieldEntity(UUID.randomUUID(), templateId, 1, "front", "Front", true, true, 0),
                fieldEntity(UUID.randomUUID(), templateId, 1, "back", "Back", true, false, 1)
        );
        List<FieldTemplateEntity> newFields = List.of(
                fieldEntity(UUID.randomUUID(), templateId, 2, "front", "Front", true, true, 0),
                fieldEntity(UUID.randomUUID(), templateId, 2, "back", "Back", true, false, 1)
        );

        when(cardTemplateRepository.findByTemplateIdForUpdate(templateId)).thenReturn(Optional.of(template));
        when(cardTemplateVersionRepository.findByTemplateIdAndVersion(templateId, 1)).thenReturn(Optional.of(latestVersion));
        when(cardTemplateVersionRepository.save(any(CardTemplateVersionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 1)).thenReturn(currentFields);
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 2)).thenReturn(newFields);
        when(cardTemplateRepository.save(any(CardTemplateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CardTemplateDTO result = templateService.partiallyChangeCardTemplate(
                ownerId,
                templateId,
                templateDto(templateId, null, null, true, json("{\"layout\":\"v2\"}"), json("{\"ai\":\"v2\"}"), "v2.png", null)
        );

        assertThat(result.version()).isEqualTo(2);
        assertThat(result.latestVersion()).isEqualTo(2);
        assertThat(result.layout()).isEqualTo(json("{\"layout\":\"v2\"}"));
        assertThat(result.aiProfile()).isEqualTo(json("{\"ai\":\"v2\"}"));
        assertThat(result.iconUrl()).isEqualTo("v2.png");

        ArgumentCaptor<CardTemplateVersionEntity> versionCaptor = ArgumentCaptor.forClass(CardTemplateVersionEntity.class);
        verify(cardTemplateVersionRepository).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getVersion()).isEqualTo(2);
    }

    @Test
    void deleteTemplate_deletesFieldsAndTemplate() {
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        CardTemplateEntity template = template(templateId, 1, null, null, null);
        List<FieldTemplateEntity> fields = List.of(
                fieldEntity(UUID.randomUUID(), templateId, 1, "front", "Front", true, true, 0),
                fieldEntity(UUID.randomUUID(), templateId, 1, "back", "Back", true, false, 1)
        );

        when(cardTemplateRepository.findByTemplateIdForUpdate(templateId)).thenReturn(Optional.of(template));
        when(fieldTemplateRepository.findByTemplateId(templateId)).thenReturn(fields);

        templateService.deleteTemplate(ownerId, templateId);

        verify(fieldTemplateRepository).deleteAll(fields);
        verify(cardTemplateRepository).delete(template);
    }

    @Test
    void addFieldToTemplate_usesFallbackLatestVersionAndReturnsAddedField() {
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        CardTemplateEntity template = template(templateId, null, json("{\"layout\":\"v3\"}"), json("{\"ai\":\"v3\"}"), "v3.png");
        CardTemplateVersionEntity latestVersion = version(templateId, 3, json("{\"layout\":\"v3\"}"), json("{\"ai\":\"v3\"}"), "v3.png");
        List<FieldTemplateEntity> existingFields = List.of(
                fieldEntity(UUID.randomUUID(), templateId, 3, "front", "Front", true, true, 0),
                fieldEntity(UUID.randomUUID(), templateId, 3, "back", "Back", true, false, 1)
        );
        List<FieldTemplateEntity> versionFourFields = List.of(
                fieldEntity(UUID.randomUUID(), templateId, 4, "front", "Front", true, true, 0),
                fieldEntity(UUID.randomUUID(), templateId, 4, "back", "Back", true, false, 1),
                fieldEntity(UUID.randomUUID(), templateId, 4, "hint", "Hint", false, false, 2)
        );

        when(cardTemplateRepository.findByTemplateIdForUpdate(templateId)).thenReturn(Optional.of(template));
        when(cardTemplateVersionRepository.findTopByTemplateIdOrderByVersionDesc(templateId)).thenReturn(Optional.of(latestVersion));
        when(cardTemplateVersionRepository.save(any(CardTemplateVersionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 3)).thenReturn(existingFields);
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 4)).thenReturn(versionFourFields);
        when(cardTemplateRepository.save(any(CardTemplateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FieldTemplateDTO result = templateService.addFieldToTemplate(ownerId, templateId, new FieldTemplateDTO(
                null,
                templateId,
                "hint",
                "Hint",
                CardFieldType.text,
                false,
                false,
                2,
                null,
                null
        ));

        assertThat(result.name()).isEqualTo("hint");
        assertThat(result.label()).isEqualTo("Hint");
        assertThat(template.getLatestVersion()).isEqualTo(4);
    }

    @Test
    void partiallyChangeFieldTemplate_returnsRenamedFieldFromNewVersion() {
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        CardTemplateEntity template = template(templateId, 1, json("{\"layout\":\"v1\"}"), json("{\"ai\":\"v1\"}"), "v1.png");
        CardTemplateVersionEntity latestVersion = version(templateId, 1, json("{\"layout\":\"v1\"}"), json("{\"ai\":\"v1\"}"), "v1.png");
        FieldTemplateEntity existingFront = fieldEntity(fieldId, templateId, 1, "front", "Front", true, true, 0);
        List<FieldTemplateEntity> currentFields = List.of(
                existingFront,
                fieldEntity(UUID.randomUUID(), templateId, 1, "back", "Back", true, false, 1)
        );
        List<FieldTemplateEntity> versionTwoFields = List.of(
                fieldEntity(UUID.randomUUID(), templateId, 2, "prompt", "Prompt", true, true, 0),
                fieldEntity(UUID.randomUUID(), templateId, 2, "back", "Back", true, false, 1)
        );

        when(cardTemplateRepository.findByTemplateIdForUpdate(templateId)).thenReturn(Optional.of(template));
        when(cardTemplateVersionRepository.findByTemplateIdAndVersion(templateId, 1)).thenReturn(Optional.of(latestVersion));
        when(fieldTemplateRepository.findByFieldIdAndTemplateIdAndTemplateVersion(fieldId, templateId, 1)).thenReturn(Optional.of(existingFront));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 1)).thenReturn(currentFields);
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 2)).thenReturn(versionTwoFields);
        when(cardTemplateVersionRepository.save(any(CardTemplateVersionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardTemplateRepository.save(any(CardTemplateEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FieldTemplateDTO result = templateService.partiallyChangeFieldTemplate(
                ownerId,
                templateId,
                fieldId,
                new FieldTemplateDTO(
                        fieldId,
                        templateId,
                        "prompt",
                        "Prompt",
                        CardFieldType.markdown,
                        true,
                        true,
                        0,
                        "default",
                        "help"
                )
        );

        assertThat(result.name()).isEqualTo("prompt");
        assertThat(result.label()).isEqualTo("Prompt");
    }

    @Test
    void deleteFieldFromTemplate_rejectsInvalidRemainingTemplate() {
        UUID ownerId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        CardTemplateEntity template = template(templateId, 1, null, null, null);
        CardTemplateVersionEntity latestVersion = version(templateId, 1, null, null, null);
        FieldTemplateEntity frontField = fieldEntity(fieldId, templateId, 1, "front", "Front", true, true, 0);
        List<FieldTemplateEntity> fields = List.of(
                frontField,
                fieldEntity(UUID.randomUUID(), templateId, 1, "back", "Back", true, false, 1)
        );

        when(cardTemplateRepository.findByTemplateIdForUpdate(templateId)).thenReturn(Optional.of(template));
        when(cardTemplateVersionRepository.findByTemplateIdAndVersion(templateId, 1)).thenReturn(Optional.of(latestVersion));
        when(fieldTemplateRepository.findByFieldIdAndTemplateIdAndTemplateVersion(fieldId, templateId, 1)).thenReturn(Optional.of(frontField));
        when(fieldTemplateRepository.findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, 1)).thenReturn(fields);

        assertThatThrownBy(() -> templateService.deleteFieldFromTemplate(ownerId, templateId, fieldId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> {
                    ResponseStatusException error = (ResponseStatusException) exception;
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo("Template must have at least 2 fields.");
                });

        verify(cardTemplateVersionRepository, never()).save(any(CardTemplateVersionEntity.class));
    }

    private CardTemplateEntity template(UUID templateId,
                                        Integer latestVersion,
                                        JsonNode layout,
                                        JsonNode aiProfile,
                                        String iconUrl) {
        return new CardTemplateEntity(
                templateId,
                UUID.randomUUID(),
                "Template " + templateId,
                "Description",
                true,
                Instant.parse("2026-04-07T12:00:00Z"),
                null,
                layout,
                aiProfile,
                iconUrl,
                latestVersion
        );
    }

    private CardTemplateDTO templateDto(UUID templateId,
                                        String name,
                                        String description,
                                        boolean isPublic,
                                        JsonNode layout,
                                        JsonNode aiProfile,
                                        String iconUrl,
                                        List<FieldTemplateDTO> fields) {
        return new CardTemplateDTO(
                templateId,
                null,
                null,
                UUID.randomUUID(),
                name,
                description,
                isPublic,
                null,
                null,
                layout,
                aiProfile,
                iconUrl,
                fields
        );
    }

    private CardTemplateVersionEntity version(UUID templateId,
                                              int version,
                                              JsonNode layout,
                                              JsonNode aiProfile,
                                              String iconUrl) {
        return new CardTemplateVersionEntity(
                templateId,
                version,
                layout,
                aiProfile,
                iconUrl,
                Instant.parse("2026-04-07T12:00:00Z"),
                UUID.randomUUID()
        );
    }

    private FieldTemplateEntity fieldEntity(UUID fieldId,
                                            UUID templateId,
                                            int templateVersion,
                                            String name,
                                            String label,
                                            boolean required,
                                            boolean onFront,
                                            int orderIndex) {
        return new FieldTemplateEntity(
                fieldId,
                templateId,
                templateVersion,
                name,
                label,
                CardFieldType.text,
                required,
                onFront,
                orderIndex,
                null,
                null
        );
    }

    private FieldTemplateDTO frontFieldDto(String name) {
        return new FieldTemplateDTO(
                null,
                null,
                name,
                capitalize(name),
                CardFieldType.text,
                true,
                true,
                0,
                null,
                null
        );
    }

    private FieldTemplateDTO backFieldDto(String name) {
        return new FieldTemplateDTO(
                null,
                null,
                name,
                capitalize(name),
                CardFieldType.text,
                true,
                false,
                1,
                null,
                null
        );
    }

    private String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static JsonNode json(String raw) {
        try {
            return OBJECT_MAPPER.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
