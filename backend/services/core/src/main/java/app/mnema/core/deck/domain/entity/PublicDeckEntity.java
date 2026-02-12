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
    @Column(name = "deck_id", nullable = false, updatable = false)
    private UUID deckId;

    @Id
    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "icon_media_id")
    private UUID iconMediaId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "template_version", nullable = false)
    private Integer templateVersion;

    @Column(name = "is_public", nullable = false)
    private boolean publicFlag;

    @Column(name = "is_listed", nullable = false)
    private boolean listed;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "language_code", columnDefinition = "language_tag", nullable = false)
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
            UUID iconMediaId,
            UUID templateId,
            Integer templateVersion,
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
        this.iconMediaId = iconMediaId;
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.publicFlag = publicFlag;
        this.listed = listed;
        this.languageCode = languageCode;
        this.tags = tags;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
            this.publishedAt = publishedAt;
            this.forkedFromDeck = forkedFromDeck;
        }

    public PublicDeckEntity(
            UUID deckId,
            Integer version,
            UUID authorId,
            String name,
            String description,
            UUID iconMediaId,
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
        this(
                deckId,
                version,
                authorId,
                name,
                description,
                iconMediaId,
                templateId,
                1,
                publicFlag,
                listed,
                languageCode,
                tags,
                createdAt,
                updatedAt,
                publishedAt,
                forkedFromDeck
        );
    }

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

    public UUID getIconMediaId() {
        return iconMediaId;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public Integer getTemplateVersion() {
        return templateVersion;
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

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setIconMediaId(UUID iconMediaId) {
        this.iconMediaId = iconMediaId;
    }

    public void setPublicFlag(boolean publicFlag) {
        this.publicFlag = publicFlag;
    }

    public void setListed(boolean listed) {
        this.listed = listed;
    }

    public void setLanguageCode(LanguageTag languageCode) {
        this.languageCode = languageCode;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    public void setTemplateVersion(Integer templateVersion) {
        this.templateVersion = templateVersion;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
