package app.mnema.core.deck.domain.composite;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class CardTemplateVersionId implements Serializable {
    private UUID templateId;
    private Integer version;

    public CardTemplateVersionId() {
    }

    public CardTemplateVersionId(UUID templateId, Integer version) {
        this.templateId = templateId;
        this.version = version;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        CardTemplateVersionId that = (CardTemplateVersionId) o;
        return Objects.equals(templateId, that.templateId) && Objects.equals(version, that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(templateId, version);
    }
}
