package app.mnema.core.deck.domain.composite;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardTemplateVersionIdTest {

    @Test
    void equalsHashCodeAndSettersUseCompositeKeyFields() {
        UUID templateId = UUID.randomUUID();
        CardTemplateVersionId id = new CardTemplateVersionId();
        id.setTemplateId(templateId);
        id.setVersion(3);

        CardTemplateVersionId same = new CardTemplateVersionId(templateId, 3);

        assertThat(id.getTemplateId()).isEqualTo(templateId);
        assertThat(id.getVersion()).isEqualTo(3);
        assertThat(id).isEqualTo(same);
        assertThat(id.hashCode()).isEqualTo(same.hashCode());
    }
}
