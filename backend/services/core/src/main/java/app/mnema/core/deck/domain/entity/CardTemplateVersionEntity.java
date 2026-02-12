package app.mnema.core.deck.domain.entity;

import app.mnema.core.deck.domain.composite.CardTemplateVersionId;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "card_template_versions", schema = "app_core")
@IdClass(CardTemplateVersionId.class)
public class CardTemplateVersionEntity {

    @Id
    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Id
    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout", columnDefinition = "jsonb")
    private JsonNode layout;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_profile", columnDefinition = "jsonb")
    private JsonNode aiProfile;

    @Column(name = "icon_url")
    private String iconUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    protected CardTemplateVersionEntity() {
    }

    public CardTemplateVersionEntity(UUID templateId,
                                     Integer version,
                                     JsonNode layout,
                                     JsonNode aiProfile,
                                     String iconUrl,
                                     Instant createdAt,
                                     UUID createdBy) {
        this.templateId = templateId;
        this.version = version;
        this.layout = layout;
        this.aiProfile = aiProfile;
        this.iconUrl = iconUrl;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public Integer getVersion() {
        return version;
    }

    public JsonNode getLayout() {
        return layout;
    }

    public void setLayout(JsonNode layout) {
        this.layout = layout;
    }

    public JsonNode getAiProfile() {
        return aiProfile;
    }

    public void setAiProfile(JsonNode aiProfile) {
        this.aiProfile = aiProfile;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
