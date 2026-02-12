package app.mnema.core.deck.domain.entity;

import app.mnema.core.deck.domain.composite.DeckUpdateSessionId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deck_update_sessions", schema = "app_core")
@IdClass(DeckUpdateSessionId.class)
public class DeckUpdateSessionEntity {

    @Id
    @Column(name = "deck_id", nullable = false, updatable = false)
    private UUID deckId;

    @Id
    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(name = "target_version", nullable = false)
    private Integer targetVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeckUpdateSessionEntity() {
    }

    public DeckUpdateSessionEntity(UUID deckId,
                                   UUID operationId,
                                   UUID authorId,
                                   Integer targetVersion,
                                   Instant createdAt,
                                   Instant updatedAt) {
        this.deckId = deckId;
        this.operationId = operationId;
        this.authorId = authorId;
        this.targetVersion = targetVersion;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getDeckId() {
        return deckId;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public Integer getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(Integer targetVersion) {
        this.targetVersion = targetVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
