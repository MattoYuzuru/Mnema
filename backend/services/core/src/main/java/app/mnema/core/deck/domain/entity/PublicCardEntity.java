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
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "deck_id", nullable = false)
    public UUID deckId;

    @Id
    @Column(name = "deck_version", nullable = false)
    private Integer deckVersion;

    @Id
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

    @Column(name = "tags")
    private String[] tags;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "checksum")
    private String checksum;

    public UUID getDeckId() {
        return deckId;
    }

    public void setDeckId(UUID deckId) {
        this.deckId = deckId;
    }

    public Integer getDeckVersion() {
        return deckVersion;
    }

    public void setDeckVersion(Integer deckVersion) {
        this.deckVersion = deckVersion;
    }

    public UUID getCardId() {
        return cardId;
    }

    public void setCardId(UUID cardId) {
        this.cardId = cardId;
    }

    public PublicDeckEntity getDeck() {
        return deck;
    }

    public void setDeck(PublicDeckEntity deck) {
        this.deck = deck;
    }

    public JsonNode getContent() {
        return content;
    }

    public void setContent(JsonNode content) {
        this.content = content;
    }

    public Integer getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(Integer orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public PublicCardEntity() {
    }

    public PublicCardEntity(UUID deckId,
                            Integer deckVersion,
                            UUID cardId,
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
        this.cardId = cardId;
        this.deck = deck;
        this.content = content;
        this.orderIndex = orderIndex;
        this.tags = tags;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.active = active;
        this.checksum = checksum;
    }
}
