package app.mnema.core.deck.domain.entity;

import app.mnema.core.deck.domain.composite.PublicDeckId;
import app.mnema.core.deck.domain.type.LanguageTag;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "is_public", nullable = false)
    private boolean publicFlag;

    @Column(name = "is_listed", nullable = false)
    private boolean listed;

    @Enumerated(EnumType.STRING)
    @Column(name = "language_code", nullable = false)
    private LanguageTag languageCode;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags;

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

    protected PublicDeckEntity() {
    }

    public PublicDeckEntity(
            UUID deckId,
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
            UUID forkedFromDeck
    ) {
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
    }

    // геттеры

    public UUID getDeckId() {
        return deckId;
    }

    public Integer getVersion() {
        return version;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public boolean isPublicFlag() {
        return publicFlag;
    }

    public boolean isListed() {
        return listed;
    }

    public LanguageTag getLanguageCode() {
        return languageCode;
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

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getForkedFromDeck() {
        return forkedFromDeck;
    }

    public List<PublicCardEntity> getCards() {
        return cards;
    }
}
