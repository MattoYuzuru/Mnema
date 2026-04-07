package app.mnema.core.deck.domain.entity;

import app.mnema.core.deck.domain.type.CardFieldType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FieldTemplateEntityTest {

    @Test
    void constructorAndSettersExposeFieldMetadata() {
        UUID fieldId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        FieldTemplateEntity entity = new FieldTemplateEntity(
                fieldId,
                templateId,
                2,
                "front",
                "Front",
                CardFieldType.text,
                true,
                true,
                1,
                "default",
                "help"
        );

        entity.setName("back");
        entity.setLabel("Back");
        entity.setFieldType(CardFieldType.markdown);
        entity.setRequired(false);
        entity.setOnFront(false);
        entity.setOrderIndex(2);
        entity.setDefaultValue("value");
        entity.setHelpText("updated");

        assertThat(entity.getFieldId()).isEqualTo(fieldId);
        assertThat(entity.getTemplateId()).isEqualTo(templateId);
        assertThat(entity.getTemplateVersion()).isEqualTo(2);
        assertThat(entity.getName()).isEqualTo("back");
        assertThat(entity.getLabel()).isEqualTo("Back");
        assertThat(entity.getFieldType()).isEqualTo(CardFieldType.markdown);
        assertThat(entity.isRequired()).isFalse();
        assertThat(entity.isOnFront()).isFalse();
        assertThat(entity.getOrderIndex()).isEqualTo(2);
        assertThat(entity.getDefaultValue()).isEqualTo("value");
        assertThat(entity.getHelpText()).isEqualTo("updated");
    }
}
