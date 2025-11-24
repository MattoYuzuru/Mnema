package app.mnema.core.deck.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_decks", schema = "app_core")
public class UserDeckEntity {

    @Id
    @Column(name = "user_deck_id", nullable = false)
    private UUID userDeckId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "public_deck_id")
    private UUID publicDeckId; // NULL для локальных колод

    @Column(name = "subscribed_version")
    private Integer subscribedVersion;

    @Column(name = "current_version")
    private Integer currentVersion;

    @Column(name = "auto_update", nullable = false)
    private boolean autoUpdate;

    @Column(name = "algorithm_id")
    private String algorithmId; // FK -> sr_algorithms.algorithm_id

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "algorithm_params", columnDefinition = "jsonb")
    private JsonNode algorithmParams;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "display_description")
    private String displayDescription;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    public UserDeckEntity() {
    }

    public UserDeckEntity(UUID userDeckId, UUID userId, UUID publicDeckId, Integer subscribedVersion, Integer currentVersion, boolean autoUpdate, String algorithmId, JsonNode algorithmParams, String displayName, String displayDescription, Instant createdAt, Instant lastSyncedAt, boolean archived) {
        this.userDeckId = userDeckId;
        this.userId = userId;
        this.publicDeckId = publicDeckId;
        this.subscribedVersion = subscribedVersion;
        this.currentVersion = currentVersion;
        this.autoUpdate = autoUpdate;
        this.algorithmId = algorithmId;
        this.algorithmParams = algorithmParams;
        this.displayName = displayName;
        this.displayDescription = displayDescription;
        this.createdAt = createdAt;
        this.lastSyncedAt = lastSyncedAt;
        this.archived = archived;
    }

    public UUID getUserDeckId() {
        return userDeckId;
    }

    public void setUserDeckId(UUID userDeckId) {
        this.userDeckId = userDeckId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getPublicDeckId() {
        return publicDeckId;
    }

    public void setPublicDeckId(UUID publicDeckId) {
        this.publicDeckId = publicDeckId;
    }

    public Integer getSubscribedVersion() {
        return subscribedVersion;
    }

    public void setSubscribedVersion(Integer subscribedVersion) {
        this.subscribedVersion = subscribedVersion;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
    }

    public boolean isAutoUpdate() {
        return autoUpdate;
    }

    public void setAutoUpdate(boolean autoUpdate) {
        this.autoUpdate = autoUpdate;
    }

    public String getAlgorithmId() {
        return algorithmId;
    }

    public void setAlgorithmId(String algorithmId) {
        this.algorithmId = algorithmId;
    }

    public JsonNode getAlgorithmParams() {
        return algorithmParams;
    }

    public void setAlgorithmParams(JsonNode algorithmParams) {
        this.algorithmParams = algorithmParams;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayDescription() {
        return displayDescription;
    }

    public void setDisplayDescription(String displayDescription) {
        this.displayDescription = displayDescription;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
