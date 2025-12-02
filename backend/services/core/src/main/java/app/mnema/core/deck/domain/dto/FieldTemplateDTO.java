package app.mnema.core.deck.domain.dto;

import app.mnema.core.deck.domain.type.CardFieldType;

import java.util.UUID;

public record FieldTemplateDTO(
    UUID fieldId,
    UUID templateId,
    String name,
    String label,
    CardFieldType fieldType,
    boolean isRequired,
    boolean isOnFront,
    Integer orderIndex,
    String defaultValue,
    String helpText
) { }