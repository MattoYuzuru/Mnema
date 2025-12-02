package app.mnema.core.deck.domain.entity;

import app.mnema.core.deck.domain.type.CardFieldType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "field_templates", schema = "app_core")
public class FieldTemplateEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "field_id", nullable = false)
    private UUID fieldId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "label", nullable = false)
    private String label;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "field_type", columnDefinition = "card_field_type", nullable = false)
    private CardFieldType fieldType;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired;

    @Column(name = "is_on_front", nullable = false)
    private boolean isOnFront;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(name = "default_value")
    private String defaultValue;

    @Column(name = "help_text")
    private String helpText;

    public UUID getFieldId() {
        return fieldId;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public String getName() {
        return name;
    }

    public String getLabel() {
        return label;
    }

    public CardFieldType getFieldType() {
        return fieldType;
    }

    public boolean isRequired() {
        return isRequired;
    }

    public boolean isOnFront() {
        return isOnFront;
    }

    public Integer getOrderIndex() {
        return orderIndex;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public String getHelpText() {
        return helpText;
    }

    protected FieldTemplateEntity() {

    }

    public FieldTemplateEntity(UUID fieldId,
                               UUID templateId,
                               String name,
                               String label,
                               CardFieldType fieldType,
                               boolean isRequired,
                               boolean isOnFront,
                               Integer orderIndex,
                               String defaultValue,
                               String helpText) {
        this.fieldId = fieldId;
        this.templateId = templateId;
        this.name = name;
        this.label = label;
        this.fieldType = fieldType;
        this.isRequired = isRequired;
        this.isOnFront = isOnFront;
        this.orderIndex = orderIndex;
        this.defaultValue = defaultValue;
        this.helpText = helpText;
    }

}
