package app.mnema.core.review.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "sr_algorithms", schema = "app_core")
public class SrAlgorithmEntity {

    @Id
    @Column(name = "algorithm_id", nullable = false)
    private String algorithmId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "version")
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_schema", columnDefinition = "jsonb")
    private JsonNode configSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_config", columnDefinition = "jsonb")
    private JsonNode defaultConfig;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getAlgorithmId() {
        return algorithmId;
    }

    public void setAlgorithmId(String algorithmId) {
        this.algorithmId = algorithmId;
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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public JsonNode getConfigSchema() {
        return configSchema;
    }

    public void setConfigSchema(JsonNode configSchema) {
        this.configSchema = configSchema;
    }

    public JsonNode getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(JsonNode defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
