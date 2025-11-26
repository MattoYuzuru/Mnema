package app.mnema.core.deck.domain.entity;

import app.mnema.core.deck.domain.composite.PublicCardId;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "public_cards", schema = "app_core")
@IdClass(PublicCardId.class)
public class PublicCardEntity {

    @Id
    @Column(name = "deck_id", nullable = false)
    private UUID deckId;

    @Id
    @Column(name = "deck_version", nullable = false)
    private Integer deckVersion;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(
                    name = "deck_id",
                    referencedColumnName = "deck_id",
                    insertable = false,
                    updatable = false
            ),
            @JoinColumn(
                    name = "deck_version",
                    referencedColumnName = "version",
                    insertable = false,
                    updatable = false
            )
    })
    private PublicDeckEntity deck;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb", nullable = false)
    private JsonNode content;

    @Column(name = "order_index")
    private Integer orderIndex;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "checksum")
    private String checksum;

    // --- JPA-конструктор ---
    protected PublicCardEntity() {
    }

    // --- Конструктор под создание карт ---
    public PublicCardEntity(
            UUID deckId,
            Integer deckVersion,
            PublicDeckEntity deck,
            JsonNode content,
            Integer orderIndex,
            String[] tags,
            Instant createdAt,
            Instant updatedAt,
            boolean active,
            String checksum
    ) {
        this.deckId = deckId;
        this.deckVersion = deckVersion;
        this.deck = deck;
        this.content = content;
        this.orderIndex = orderIndex;
        this.tags = tags;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.active = active;
        this.checksum = checksum;
    }

    // --- Геттеры ---
    public UUID getDeckId() {
        return deckId;
    }

    public Integer getDeckVersion() {
        return deckVersion;
    }

    public UUID getCardId() {
        return cardId;
    }

    public PublicDeckEntity getDeck() {
        return deck;
    }

    public JsonNode getContent() {
        return content;
    }

    public Integer getOrderIndex() {
        return orderIndex;
    }

    public String[] getTags() {
        return tags;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return active;
    }

    public String getChecksum() {
        return checksum;
    }
}
