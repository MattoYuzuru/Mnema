package app.mnema.core.deck.domain.entity;

import app.mnema.core.deck.domain.composite.PublicDeckId;
import app.mnema.core.deck.domain.type.LanguageTag;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "public_decks", schema = "app_core")
@IdClass(PublicDeckId.class)
public class PublicDeckEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "deck_id", nullable = false)
    private UUID deckId;

    @Id
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;
    // Потом мб заменить на @ManyToOne CardTemplateEntity, когда будет нужно

    @Column(name = "is_public", nullable = false)
    private boolean publicFlag;

    @Column(name = "is_listed", nullable = false)
    private boolean listed;

    @Enumerated(EnumType.STRING)
    @Column(name = "language_code", nullable = false)
    private LanguageTag languageCode;

    @Column(name = "tags")
    private String[] tags; // PG text[] -> Java String[] (Hibernate это умеет)

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "forked_from_deck")
    private UUID forkedFromDeck;

    @OneToMany(mappedBy = "deck", fetch = FetchType.LAZY)
    private List<PublicCardEntity> cards;

    public PublicDeckEntity() {
    }

    public PublicDeckEntity(UUID deckId,
                            Integer version,
                            UUID authorId,
                            String name,
                            String description,
                            UUID templateId,
                            boolean publicFlag,
                            boolean listed,
                            LanguageTag languageCode,
                            String[] tags,
                            Instant createdAt,
                            Instant updatedAt,
                            Instant publishedAt,
                            UUID forkedFromDeck,
                            List<PublicCardEntity> cards) {
        this.deckId = deckId;
        this.version = version;
        this.authorId = authorId;
        this.name = name;
        this.description = description;
        this.templateId = templateId;
        this.publicFlag = publicFlag;
        this.listed = listed;
        this.languageCode = languageCode;
        this.tags = tags;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.publishedAt = publishedAt;
        this.forkedFromDeck = forkedFromDeck;
        this.cards = cards;
    }

    public UUID getDeckId() {
        return deckId;
    }

    public void setDeckId(UUID deckId) {
        this.deckId = deckId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public void setAuthorId(UUID authorId) {
        this.authorId = authorId;
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

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public boolean isPublicFlag() {
        return publicFlag;
    }

    public void setPublicFlag(boolean publicFlag) {
        this.publicFlag = publicFlag;
    }

    public boolean isListed() {
        return listed;
    }

    public void setListed(boolean listed) {
        this.listed = listed;
    }

    public LanguageTag getLanguageCode() {
        return languageCode;
    }

    public void setLanguageCode(LanguageTag languageCode) {
        this.languageCode = languageCode;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public UUID getForkedFromDeck() {
        return forkedFromDeck;
    }

    public void setForkedFromDeck(UUID forkedFromDeck) {
        this.forkedFromDeck = forkedFromDeck;
    }

    public List<PublicCardEntity> getCards() {
        return cards;
    }

    public void setCards(List<PublicCardEntity> cards) {
        this.cards = cards;
    }
}
