package app.mnema.core.deck.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_cards", schema = "app_core")
public class UserCardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_card_id", nullable = false)
    private UUID userCardId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

//    @ManyToOne(fetch = FetchType.LAZY)
    @Column(name = "subscription_id", nullable = false)
    private UUID userDeckId;

    @Column(name = "public_card_id")
    private UUID publicCardId; // NULL для кастомных карт

    @Column(name = "is_custom", nullable = false)
    private boolean custom;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "personal_note")
    private String personalNote;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_override", columnDefinition = "jsonb")
    private JsonNode contentOverride;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public UserCardEntity() {
    }

    public UserCardEntity(
            UUID userId,
            UUID userDeckId,
            UUID publicCardId,
            boolean custom,
            boolean deleted,
            String personalNote,
            String[] tags,
            JsonNode contentOverride,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.userId = userId;
        this.userDeckId = userDeckId;
        this.publicCardId = publicCardId;
        this.custom = custom;
        this.deleted = deleted;
        this.personalNote = personalNote;
        this.tags = tags;
        this.contentOverride = contentOverride;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getUserCardId() {
        return userCardId;
    }

    public void setUserCardId(UUID userCardId) {
        this.userCardId = userCardId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserDeckId() {
        return userDeckId;
    }

    public void setUserDeck(UUID userDeckId) {
        this.userDeckId = userDeckId;
    }

    public UUID getPublicCardId() {
        return publicCardId;
    }

    public void setPublicCardId(UUID publicCardId) {
        this.publicCardId = publicCardId;
    }

    public boolean isCustom() {
        return custom;
    }

    public void setCustom(boolean custom) {
        this.custom = custom;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public String getPersonalNote() {
        return personalNote;
    }

    public void setPersonalNote(String personalNote) {
        this.personalNote = personalNote;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    public JsonNode getContentOverride() {
        return contentOverride;
    }

    public void setContentOverride(JsonNode contentOverride) {
        this.contentOverride = contentOverride;
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
}
