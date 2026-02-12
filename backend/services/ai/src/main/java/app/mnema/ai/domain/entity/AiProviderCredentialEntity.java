package app.mnema.ai.domain.entity;

import app.mnema.ai.domain.type.AiProviderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_provider_credentials", schema = "app_ai")
public class AiProviderCredentialEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "alias")
    private String alias;

    @Column(name = "encrypted_secret", nullable = false)
    private byte[] encryptedSecret;

    @Column(name = "encrypted_data_key")
    private byte[] encryptedDataKey;

    @Column(name = "key_id")
    private String keyId;

    @Column(name = "nonce")
    private byte[] nonce;

    @Column(name = "aad")
    private byte[] aad;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", columnDefinition = "ai_provider_status", nullable = false)
    private AiProviderStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public AiProviderCredentialEntity() {
    }

    public AiProviderCredentialEntity(UUID id,
                                      UUID userId,
                                      String provider,
                                      String alias,
                                      byte[] encryptedSecret,
                                      byte[] encryptedDataKey,
                                      String keyId,
                                      byte[] nonce,
                                      byte[] aad,
                                      AiProviderStatus status,
                                      Instant createdAt,
                                      Instant lastUsedAt,
                                      Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.alias = alias;
        this.encryptedSecret = encryptedSecret;
        this.encryptedDataKey = encryptedDataKey;
        this.keyId = keyId;
        this.nonce = nonce;
        this.aad = aad;
        this.status = status;
        this.createdAt = createdAt;
        this.lastUsedAt = lastUsedAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public byte[] getEncryptedSecret() {
        return encryptedSecret;
    }

    public void setEncryptedSecret(byte[] encryptedSecret) {
        this.encryptedSecret = encryptedSecret;
    }

    public byte[] getEncryptedDataKey() {
        return encryptedDataKey;
    }

    public void setEncryptedDataKey(byte[] encryptedDataKey) {
        this.encryptedDataKey = encryptedDataKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public void setNonce(byte[] nonce) {
        this.nonce = nonce;
    }

    public byte[] getAad() {
        return aad;
    }

    public void setAad(byte[] aad) {
        this.aad = aad;
    }

    public AiProviderStatus getStatus() {
        return status;
    }

    public void setStatus(AiProviderStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
