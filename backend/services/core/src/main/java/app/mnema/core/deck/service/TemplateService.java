package app.mnema.core.deck.service;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.CardTemplateVersionEntity;
import app.mnema.core.deck.domain.entity.FieldTemplateEntity;
import app.mnema.core.deck.repository.CardTemplateRepository;
import app.mnema.core.deck.repository.CardTemplateVersionRepository;
import app.mnema.core.deck.repository.FieldTemplateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TemplateService {

    private static final int MAX_TEMPLATE_NAME = 50;
    private static final int MAX_TEMPLATE_DESCRIPTION = 200;
    private static final int MAX_FIELD_LABEL = 50;
    private static final int MAX_FIELD_HELP_TEXT = 100;

    private final CardTemplateRepository cardTemplateRepository;
    private final FieldTemplateRepository fieldTemplateRepository;
    private final CardTemplateVersionRepository cardTemplateVersionRepository;

    public TemplateService(CardTemplateRepository cardTemplateRepository,
                           FieldTemplateRepository fieldTemplateRepository,
                           CardTemplateVersionRepository cardTemplateVersionRepository) {
        this.cardTemplateRepository = cardTemplateRepository;
        this.fieldTemplateRepository = fieldTemplateRepository;
        this.cardTemplateVersionRepository = cardTemplateVersionRepository;
    }

    // GET /api/core/templates?page=1&limit=10 - получить все публичные шаблоны постранично
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<CardTemplateDTO> getCardTemplatesByPage(int page, int limit) {

        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        Page<CardTemplateEntity> templatePage = cardTemplateRepository
                .findByIsPublicTrueOrderByCreatedAtDesc(pageable);

        return mapTemplatePage(templatePage);
    }

    // GET /api/core/templates?scope=mine - получить шаблоны пользователя
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<CardTemplateDTO> getUserTemplatesByPage(UUID currentUserId, int page, int limit) {
        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        Page<CardTemplateEntity> templatePage = cardTemplateRepository
                .findByOwnerIdOrderByCreatedAtDesc(currentUserId, pageable);

        return mapTemplatePage(templatePage);
    }

    // GET /api/core/templates?scope=all - публичные + свои шаблоны
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public Page<CardTemplateDTO> getTemplatesForUserAndPublic(UUID currentUserId, int page, int limit) {
        if (page < 1 || limit < 1) {
            throw new IllegalArgumentException("page and limit must be >= 1");
        }

        Pageable pageable = PageRequest.of(page - 1, limit);

        Page<CardTemplateEntity> templatePage = cardTemplateRepository
                .findByIsPublicTrueOrOwnerIdOrderByCreatedAtDesc(currentUserId, pageable);

        return mapTemplatePage(templatePage);
    }

    // POST /api/core/templates - создать шаблон (вместе с полями)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public CardTemplateDTO createNewTemplate(UUID currentUserId,
                                             CardTemplateDTO dto,
                                             List<FieldTemplateDTO> fieldsDto) {

        validateTemplateMeta(dto);

        // 0. Выбираем источник полей - либо отдельный аргумент, либо dto.fields()
        List<FieldTemplateDTO> fieldDtos;
        if (fieldsDto != null && !fieldsDto.isEmpty()) {
            fieldDtos = fieldsDto;
        } else if (dto.fields() != null) {
            fieldDtos = dto.fields();
        } else {
            fieldDtos = List.of();
        }

        validateTemplateFields(fieldDtos);

        // 1. Создаем шаблон без полей
        CardTemplateEntity cardTemplate = new CardTemplateEntity(
                null,
                currentUserId,
                dto.name(),
                dto.description(),
                dto.isPublic(),
                Instant.now(),
                null,
                dto.layout(), // keep latest snapshot for compatibility
                dto.aiProfile(),
                dto.iconUrl(),
                1
        );

        // 2. Сохраняем шаблон в БД
        CardTemplateEntity savedCardTemplate = cardTemplateRepository.save(cardTemplate);

        // 3. Сохраняем поля шаблона в БД (проставляя правильный templateId)
        UUID templateId = savedCardTemplate.getTemplateId();

        // 4. Сохраняем версию шаблона
        CardTemplateVersionEntity version = new CardTemplateVersionEntity(
                templateId,
                1,
                dto.layout(),
                dto.aiProfile(),
                dto.iconUrl(),
                Instant.now(),
                currentUserId
        );
        cardTemplateVersionRepository.save(version);

        List<FieldTemplateEntity> cardFields = fieldDtos.stream()
                .map(fieldDto -> {
                    FieldTemplateDTO normalized = new FieldTemplateDTO(
                            null,                  // fieldId генерится БД
                            templateId,                  // гарантированно тот же шаблон
                            fieldDto.name(),
                            fieldDto.label(),
                            fieldDto.fieldType(),
                            fieldDto.isRequired(),
                            fieldDto.isOnFront(),
                            fieldDto.orderIndex(),
                            fieldDto.defaultValue(),
                            fieldDto.helpText()
                    );
                    return toFieldTemplateEntity(normalized, 1);
                })
                .toList();

        List<FieldTemplateEntity> savedFieldTemplates = cardFields.isEmpty()
                ? List.of()
                : fieldTemplateRepository.saveAll(cardFields);

        // 4. Подготовка полей для возврата
        List<FieldTemplateDTO> fieldTemplateDTOList = savedFieldTemplates.stream()
                .map(this::toFieldTemplateDTO)
                .toList();

        return toCardTemplateDTO(savedCardTemplate, version, fieldTemplateDTOList);
    }

    // GET /api/core/templates/{templateId} - получить шаблон по айди (version optional)
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public CardTemplateDTO getCardTemplateById(UUID templateId, Integer version) {
        CardTemplateEntity entity = cardTemplateRepository.findById(templateId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));

        int resolvedVersion = version != null ? version : entity.getLatestVersion();
        CardTemplateVersionEntity templateVersion = cardTemplateVersionRepository
                .findByTemplateIdAndVersion(templateId, resolvedVersion)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template version not found: templateId=" + templateId + ", version=" + resolvedVersion
                ));

        List<FieldTemplateDTO> fields = fieldTemplateRepository
                .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, resolvedVersion)
                .stream()
                .map(this::toFieldTemplateDTO)
                .toList();

        return toCardTemplateDTO(entity, templateVersion, fields);
    }

    // GET /api/core/templates/{templateId}/versions - список версий
    @PreAuthorize("hasAuthority('SCOPE_user.read')")
    public List<Integer> getTemplateVersions(UUID templateId) {
        cardTemplateRepository.findById(templateId)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + templateId));

        return cardTemplateVersionRepository
                .findByTemplateIdOrderByVersionDesc(templateId)
                .stream()
                .map(CardTemplateVersionEntity::getVersion)
                .toList();
    }

    // PATCH /api/core/templates/{templateId} - частично изменить шаблон (сам шаблон)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public CardTemplateDTO partiallyChangeCardTemplate(UUID currentUserId,
                                                       UUID templateId,
                                                       CardTemplateDTO dto) {

        CardTemplateEntity entity = cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template not found or access denied: " + templateId
                ));

        // Base meta updates
        if (dto.name() != null) {
            validateLength(dto.name(), MAX_TEMPLATE_NAME, "Template name");
            entity.setName(dto.name());
        }
        if (dto.description() != null) {
            validateLength(dto.description(), MAX_TEMPLATE_DESCRIPTION, "Template description");
            entity.setDescription(dto.description());
        }
        entity.setPublic(dto.isPublic());

        CardTemplateVersionEntity latestVersion = resolveLatestVersion(entity);

        boolean versionChange = dto.layout() != null || dto.aiProfile() != null || dto.iconUrl() != null;
        if (!versionChange) {
            entity.setUpdatedAt(Instant.now());
            CardTemplateEntity saved = cardTemplateRepository.save(entity);

            List<FieldTemplateDTO> fields = fieldTemplateRepository
                    .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, latestVersion.getVersion())
                    .stream()
                    .map(this::toFieldTemplateDTO)
                    .toList();

            return toCardTemplateDTO(saved, latestVersion, fields);
        }

        CardTemplateVersionEntity newVersion = createNewVersion(
                entity,
                latestVersion,
                dto.layout() != null ? dto.layout() : latestVersion.getLayout(),
                dto.aiProfile() != null ? dto.aiProfile() : latestVersion.getAiProfile(),
                dto.iconUrl() != null ? dto.iconUrl() : latestVersion.getIconUrl(),
                currentUserId,
                null
        );

        List<FieldTemplateDTO> fields = fieldTemplateRepository
                .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, newVersion.getVersion())
                .stream()
                .map(this::toFieldTemplateDTO)
                .toList();

        return toCardTemplateDTO(entity, newVersion, fields);
    }

    // DELETE /api/core/templates/{templateId} - удалить шаблон (со всеми полями)
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteTemplate(UUID currentUserId, UUID templateId) {

        // 1. Проверяем наличие шаблона и права
        CardTemplateEntity cardTemplate = cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template with ID: " + templateId +
                                " and owner ID: " + currentUserId + " not found."
                ));

        // 2. Удаляем поля (в БД уже есть ON DELETE CASCADE, но так явнее)
        List<FieldTemplateEntity> cardFields = fieldTemplateRepository.findByTemplateId(templateId);
        if (!cardFields.isEmpty()) {
            fieldTemplateRepository.deleteAll(cardFields);
        }

        // 3. Удаляем шаблон
        cardTemplateRepository.delete(cardTemplate);
    }

    // POST /api/core/templates/{templateId}/fields - добавить поле в шаблон
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public FieldTemplateDTO addFieldToTemplate(UUID currentUserId,
                                               UUID templateId,
                                               FieldTemplateDTO dto) {

        // Проверяем, что шаблон принадлежит текущему пользователю
        CardTemplateEntity template = cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template not found or access denied: " + templateId
                ));

        CardTemplateVersionEntity latestVersion = resolveLatestVersion(template);

        FieldTemplateDTO normalized = new FieldTemplateDTO(
                null,
                templateId,
                dto.name(),
                dto.label(),
                dto.fieldType(),
                dto.isRequired(),
                dto.isOnFront(),
                dto.orderIndex(),
                dto.defaultValue(),
                dto.helpText()
        );

        List<FieldTemplateDTO> existingFields = fieldTemplateRepository
                .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, latestVersion.getVersion())
                .stream()
                .map(this::toFieldTemplateDTO)
                .toList();
        validateTemplateFields(mergeFields(existingFields, normalized));

        List<FieldTemplateDTO> merged = mergeFields(existingFields, normalized);

        CardTemplateVersionEntity newVersion = createNewVersion(
                template,
                latestVersion,
                latestVersion.getLayout(),
                latestVersion.getAiProfile(),
                latestVersion.getIconUrl(),
                currentUserId,
                merged
        );

        return fieldTemplateRepository
                .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, newVersion.getVersion())
                .stream()
                .map(this::toFieldTemplateDTO)
                .filter(field -> field.name().equals(normalized.name()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("New field not found after version creation"));
    }

    // PATCH /api/core/templates/{templateId}/fields/{fieldId} - частично изменить поле в шаблоне
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public FieldTemplateDTO partiallyChangeFieldTemplate(UUID currentUserId,
                                                         UUID templateId,
                                                         UUID fieldId,
                                                         FieldTemplateDTO dto) {

        // Проверяем права на шаблон
        CardTemplateEntity template = cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template not found or access denied: " + templateId
                ));

        CardTemplateVersionEntity latestVersion = resolveLatestVersion(template);

        FieldTemplateEntity field = fieldTemplateRepository
                .findByFieldIdAndTemplateIdAndTemplateVersion(fieldId, templateId, latestVersion.getVersion())
                .orElseThrow(() -> new NoSuchElementException(
                        "Field not found in template: " + fieldId
                ));

        String newName = dto.name() != null ? dto.name() : field.getName();
        String newLabel = dto.label() != null ? dto.label() : field.getLabel();
        var newFieldType = dto.fieldType() != null ? dto.fieldType() : field.getFieldType();
        boolean newRequired = dto.isRequired();
        boolean newOnFront = dto.isOnFront();
        Integer newOrderIndex = dto.orderIndex() != null ? dto.orderIndex() : field.getOrderIndex();
        String newDefaultValue = dto.defaultValue() != null ? dto.defaultValue() : field.getDefaultValue();
        String newHelpText = dto.helpText() != null ? dto.helpText() : field.getHelpText();

        List<FieldTemplateDTO> updatedFields = fieldTemplateRepository
                .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, latestVersion.getVersion())
                .stream()
                .map(this::toFieldTemplateDTO)
                .map(existing -> existing.fieldId().equals(fieldId)
                        ? new FieldTemplateDTO(
                        fieldId,
                        templateId,
                        newName,
                        newLabel,
                        newFieldType,
                        newRequired,
                        newOnFront,
                        newOrderIndex,
                        newDefaultValue,
                        newHelpText
                )
                        : existing)
                .toList();

        validateTemplateFields(updatedFields);

        CardTemplateVersionEntity newVersion = createNewVersion(
                template,
                latestVersion,
                latestVersion.getLayout(),
                latestVersion.getAiProfile(),
                latestVersion.getIconUrl(),
                currentUserId,
                updatedFields
        );

        return fieldTemplateRepository
                .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, newVersion.getVersion())
                .stream()
                .map(this::toFieldTemplateDTO)
                .filter(updated -> updated.name().equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Updated field not found after version creation"));
    }

    // DELETE /api/core/templates/{templateId}/fields/{fieldId} - удалить поле из шаблона
    @Transactional
    @PreAuthorize("hasAuthority('SCOPE_user.write')")
    public void deleteFieldFromTemplate(UUID currentUserId,
                                        UUID templateId,
                                        UUID fieldId) {

        // Проверяем права на шаблон
        CardTemplateEntity template = cardTemplateRepository
                .findByOwnerIdAndTemplateId(currentUserId, templateId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Template not found or access denied: " + templateId
                ));

        CardTemplateVersionEntity latestVersion = resolveLatestVersion(template);

        FieldTemplateEntity field = fieldTemplateRepository
                .findByFieldIdAndTemplateIdAndTemplateVersion(fieldId, templateId, latestVersion.getVersion())
                .orElseThrow(() -> new NoSuchElementException(
                        "Field not found in template: " + fieldId
                ));

        List<FieldTemplateDTO> remainingFields = fieldTemplateRepository
                .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(templateId, latestVersion.getVersion())
                .stream()
                .filter(existing -> !existing.getFieldId().equals(fieldId))
                .map(this::toFieldTemplateDTO)
                .toList();

        validateTemplateFields(remainingFields);

        createNewVersion(
                template,
                latestVersion,
                latestVersion.getLayout(),
                latestVersion.getAiProfile(),
                latestVersion.getIconUrl(),
                currentUserId,
                remainingFields
        );
    }

    // UTILS

    private FieldTemplateEntity toFieldTemplateEntity(FieldTemplateDTO dto, int templateVersion) {
        Integer effectiveOrderIndex = dto.orderIndex() != null ? dto.orderIndex() : 0;

        return new FieldTemplateEntity(
                dto.fieldId(),
                dto.templateId(),
                templateVersion,
                dto.name(),
                dto.label(),
                dto.fieldType(),
                dto.isRequired(),
                dto.isOnFront(),
                effectiveOrderIndex,
                dto.defaultValue(),
                dto.helpText()
        );
    }

    private FieldTemplateDTO toFieldTemplateDTO(FieldTemplateEntity entity) {
        return new FieldTemplateDTO(
                entity.getFieldId(),
                entity.getTemplateId(),
                entity.getName(),
                entity.getLabel(),
                entity.getFieldType(),
                entity.isRequired(),
                entity.isOnFront(),
                entity.getOrderIndex(),
                entity.getDefaultValue(),
                entity.getHelpText()
        );
    }

    private CardTemplateVersionEntity resolveLatestVersion(CardTemplateEntity template) {
        Integer latestVersion = template.getLatestVersion();
        if (latestVersion != null) {
            return cardTemplateVersionRepository
                    .findByTemplateIdAndVersion(template.getTemplateId(), latestVersion)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Template version not found: templateId=" + template.getTemplateId() + ", version=" + latestVersion
                    ));
        }
        return cardTemplateVersionRepository.findTopByTemplateIdOrderByVersionDesc(template.getTemplateId())
                .orElseThrow(() -> new NoSuchElementException(
                        "Template version not found: templateId=" + template.getTemplateId()
                ));
    }

    private CardTemplateVersionEntity createNewVersion(CardTemplateEntity template,
                                                       CardTemplateVersionEntity latestVersion,
                                                       com.fasterxml.jackson.databind.JsonNode layout,
                                                       com.fasterxml.jackson.databind.JsonNode aiProfile,
                                                       String iconUrl,
                                                       UUID createdBy,
                                                       List<FieldTemplateDTO> overrideFields) {
        int newVersionNumber = latestVersion.getVersion() + 1;
        Instant now = Instant.now();

        CardTemplateVersionEntity newVersion = new CardTemplateVersionEntity(
                template.getTemplateId(),
                newVersionNumber,
                layout,
                aiProfile,
                iconUrl,
                now,
                createdBy
        );
        cardTemplateVersionRepository.save(newVersion);

        List<FieldTemplateDTO> fieldsForVersion;
        if (overrideFields != null) {
            fieldsForVersion = overrideFields;
        } else {
            fieldsForVersion = fieldTemplateRepository
                    .findByTemplateIdAndTemplateVersionOrderByOrderIndexAsc(
                            template.getTemplateId(),
                            latestVersion.getVersion()
                    )
                    .stream()
                    .map(this::toFieldTemplateDTO)
                    .toList();
        }

        List<FieldTemplateEntity> clonedFields = fieldsForVersion.stream()
                .map(field -> new FieldTemplateEntity(
                        null,
                        template.getTemplateId(),
                        newVersionNumber,
                        field.name(),
                        field.label(),
                        field.fieldType(),
                        field.isRequired(),
                        field.isOnFront(),
                        field.orderIndex() != null ? field.orderIndex() : 0,
                        field.defaultValue(),
                        field.helpText()
                ))
                .toList();

        if (!clonedFields.isEmpty()) {
            fieldTemplateRepository.saveAll(clonedFields);
        }

        template.setLatestVersion(newVersionNumber);
        template.setUpdatedAt(now);
        template.setLayout(layout);
        template.setAiProfile(aiProfile);
        template.setIconUrl(iconUrl);
        cardTemplateRepository.save(template);

        return newVersion;
    }

    private CardTemplateDTO toCardTemplateDTO(CardTemplateEntity entity,
                                              CardTemplateVersionEntity version,
                                              List<FieldTemplateDTO> fields) {
        Integer effectiveVersion = version != null ? version.getVersion() : entity.getLatestVersion();
        return new CardTemplateDTO(
                entity.getTemplateId(),
                effectiveVersion,
                entity.getLatestVersion(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getDescription(),
                entity.isPublic(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                version != null ? version.getLayout() : entity.getLayout(),
                version != null ? version.getAiProfile() : entity.getAiProfile(),
                version != null ? version.getIconUrl() : entity.getIconUrl(),
                fields
        );
    }

    private void validateTemplateFields(List<FieldTemplateDTO> fields) {
        if (fields == null || fields.size() < 2) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Template must have at least 2 fields."
            );
        }

        boolean hasFront = fields.stream().anyMatch(FieldTemplateDTO::isOnFront);
        boolean hasBack = fields.stream().anyMatch(field -> !field.isOnFront());

        if (!hasFront || !hasBack) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Template must have at least one field on each side."
            );
        }

        boolean requiredFront = fields.stream().anyMatch(field -> field.isOnFront() && field.isRequired());
        boolean requiredBack = fields.stream().anyMatch(field -> !field.isOnFront() && field.isRequired());

        if (!requiredFront || !requiredBack) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Template must have at least one required field on each side."
            );
        }

        for (FieldTemplateDTO field : fields) {
            validateLength(field.label(), MAX_FIELD_LABEL, "Field label");
            validateLength(field.helpText(), MAX_FIELD_HELP_TEXT, "Field help text");
        }
    }

    private List<FieldTemplateDTO> mergeFields(List<FieldTemplateDTO> fields, FieldTemplateDTO added) {
        if (fields == null || fields.isEmpty()) {
            return List.of(added);
        }
        List<FieldTemplateDTO> merged = new java.util.ArrayList<>(fields);
        merged.add(added);
        return merged;
    }

    private void validateTemplateMeta(CardTemplateDTO dto) {
        validateLength(dto.name(), MAX_TEMPLATE_NAME, "Template name");
        validateLength(dto.description(), MAX_TEMPLATE_DESCRIPTION, "Template description");
    }

    private void validateLength(String value, int maxLength, String label) {
        if (value == null) {
            return;
        }
        if (value.length() > maxLength) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    label + " must be at most " + maxLength + " characters"
            );
        }
    }

    private Page<CardTemplateDTO> mapTemplatePage(Page<CardTemplateEntity> templatePage) {
        List<CardTemplateEntity> templates = templatePage.getContent();
        if (templates.isEmpty()) {
            return new PageImpl<>(List.of(), templatePage.getPageable(), templatePage.getTotalElements());
        }

        List<UUID> templateIds = templates.stream()
                .map(CardTemplateEntity::getTemplateId)
                .toList();

        List<CardTemplateVersionEntity> versions = cardTemplateVersionRepository.findByTemplateIdIn(templateIds);
        Map<UUID, CardTemplateVersionEntity> latestByTemplate = new java.util.HashMap<>();
        for (CardTemplateVersionEntity version : versions) {
            if (version == null) {
                continue;
            }
            CardTemplateVersionEntity existing = latestByTemplate.get(version.getTemplateId());
            if (existing == null || version.getVersion() > existing.getVersion()) {
                latestByTemplate.put(version.getTemplateId(), version);
            }
        }

        List<FieldTemplateEntity> fieldEntities = fieldTemplateRepository.findByTemplateIdIn(templateIds);

        Map<UUID, List<FieldTemplateDTO>> fieldsByTemplateId = fieldEntities.stream()
                .filter(field -> {
                    CardTemplateVersionEntity latest = latestByTemplate.get(field.getTemplateId());
                    return latest != null && field.getTemplateVersion() != null
                            && field.getTemplateVersion().equals(latest.getVersion());
                })
                .collect(Collectors.groupingBy(
                        FieldTemplateEntity::getTemplateId,
                        Collectors.mapping(this::toFieldTemplateDTO, Collectors.toList())
                ));

        List<CardTemplateDTO> dtoList = templates.stream()
                .map(entity -> toCardTemplateDTO(
                        entity,
                        latestByTemplate.get(entity.getTemplateId()),
                        fieldsByTemplateId.getOrDefault(entity.getTemplateId(), List.of())
                ))
                .toList();

        return new PageImpl<>(dtoList, templatePage.getPageable(), templatePage.getTotalElements());
    }
}
