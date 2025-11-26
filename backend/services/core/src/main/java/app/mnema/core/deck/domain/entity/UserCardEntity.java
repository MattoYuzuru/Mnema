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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private UserDeckEntity userDeck;

    @Column(name = "public_card_id")
    private UUID publicCardId; // NULL для кастомных карт

    @Column(name = "is_custom", nullable = false)
    private boolean custom;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "personal_note")
    private String personalNote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_override", columnDefinition = "jsonb")
    private JsonNode contentOverride;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "last_review_at")
    private Instant lastReviewAt;

    @Column(name = "next_review_at")
    private Instant nextReviewAt;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "is_suspended", nullable = false)
    private boolean suspended;

    public UserCardEntity() {
    }

    public UserCardEntity(
            UUID userId,
            UserDeckEntity userDeck,
            UUID publicCardId,
            boolean custom,
            boolean deleted,
            String personalNote,
            JsonNode contentOverride,
            Instant createdAt,
            Instant updatedAt,
            Instant lastReviewAt,
            Instant nextReviewAt,
            int reviewCount,
            boolean suspended
    ) {
        this.userId = userId;
        this.userDeck = userDeck;
        this.publicCardId = publicCardId;
        this.custom = custom;
        this.deleted = deleted;
        this.personalNote = personalNote;
        this.contentOverride = contentOverride;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastReviewAt = lastReviewAt;
        this.nextReviewAt = nextReviewAt;
        this.reviewCount = reviewCount;
        this.suspended = suspended;
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

    public UserDeckEntity getUserDeck() {
        return userDeck;
    }

    public void setUserDeck(UserDeckEntity userDeck) {
        this.userDeck = userDeck;
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

    public Instant getLastReviewAt() {
        return lastReviewAt;
    }

    public void setLastReviewAt(Instant lastReviewAt) {
        this.lastReviewAt = lastReviewAt;
    }

    public Instant getNextReviewAt() {
        return nextReviewAt;
    }

    public void setNextReviewAt(Instant nextReviewAt) {
        this.nextReviewAt = nextReviewAt;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(int reviewCount) {
        this.reviewCount = reviewCount;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }
}
