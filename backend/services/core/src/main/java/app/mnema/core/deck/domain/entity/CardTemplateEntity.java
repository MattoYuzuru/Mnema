package app.mnema.core.deck.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "card_templates", schema = "app_core")
public class CardTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "latest_version", nullable = false)
    private Integer latestVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout", columnDefinition = "jsonb")
    private JsonNode layout;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_profile")
    private JsonNode aiProfile;

    @Column(name = "icon_url")
    private String iconUrl;

    protected CardTemplateEntity() {
    }

    public CardTemplateEntity(UUID templateId,
                              UUID ownerId,
                              String name,
                              String description,
                              boolean isPublic,
                              Instant createdAt,
                              Instant updatedAt,
                              JsonNode layout,
                              JsonNode aiProfile,
                              String iconUrl,
                              Integer latestVersion) {
        this.templateId = templateId;
        this.ownerId = ownerId;
        this.name = name;
        this.description = description;
        this.isPublic = isPublic;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.layout = layout;
        this.aiProfile = aiProfile;
        this.iconUrl = iconUrl;
        this.latestVersion = latestVersion;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean isPublic) {
        this.isPublic = isPublic;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(Integer latestVersion) {
        this.latestVersion = latestVersion;
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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        CardTemplateEntity that = (CardTemplateEntity) o;
        return Objects.equals(templateId, that.templateId)
                && Objects.equals(ownerId, that.ownerId)
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId, ownerId, name);
    }
}
