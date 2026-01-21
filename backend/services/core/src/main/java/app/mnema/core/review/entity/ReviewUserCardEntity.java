package app.mnema.core.review.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_cards", schema = "app_core")
public class ReviewUserCardEntity {

    @Id
    @Column(name = "user_card_id", nullable = false)
    private UUID userCardId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "subscription_id", nullable = false)
    private UUID userDeckId;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getUserCardId() {
        return userCardId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getUserDeckId() {
        return userDeckId;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
