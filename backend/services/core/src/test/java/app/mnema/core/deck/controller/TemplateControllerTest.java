package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.domain.type.CardFieldType;
import app.mnema.core.deck.service.TemplateService;
import app.mnema.core.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateControllerTest {

    @Mock
    TemplateService templateService;

    @Mock
    CurrentUserProvider currentUserProvider;

    @Test
    void getTemplatesPaginatedSupportsPublicMineAndAllScopes() {
        TemplateController controller = new TemplateController(templateService, currentUserProvider);
        UUID userId = UUID.randomUUID();
        Jwt jwt = jwt();
        CardTemplateDTO dto = template();
        Page<CardTemplateDTO> page = new PageImpl<>(List.of(dto));

        when(templateService.getCardTemplatesByPage(1, 10)).thenReturn(page);
        when(currentUserProvider.getUserId(jwt)).thenReturn(userId);
        when(templateService.getUserTemplatesByPage(userId, 1, 10)).thenReturn(page);
        when(templateService.getTemplatesForUserAndPublic(userId, 1, 10)).thenReturn(page);

        assertThat(controller.getTemplatesPaginated(null, 1, 10, "public")).isEqualTo(page);
        assertThat(controller.getTemplatesPaginated(jwt, 1, 10, "mine")).isEqualTo(page);
        assertThat(controller.getTemplatesPaginated(jwt, 1, 10, "all")).isEqualTo(page);
    }

    @Test
    void getTemplatesPaginatedRejectsUnknownOrUnauthorizedScopes() {
        TemplateController controller = new TemplateController(templateService, currentUserProvider);

        assertThatThrownBy(() -> controller.getTemplatesPaginated(null, 1, 10, "mine"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Authentication required");
        assertThatThrownBy(() -> controller.getTemplatesPaginated(jwt(), 1, 10, "unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown template scope");
    }

    @Test
    void mutatingEndpointsDelegateWithResolvedUserId() {
        TemplateController controller = new TemplateController(templateService, currentUserProvider);
        UUID userId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        Jwt jwt = jwt();
        CardTemplateDTO template = template();
        FieldTemplateDTO field = new FieldTemplateDTO(fieldId, templateId, "front", "Front", CardFieldType.text, true, true, 1, null, null);
        when(currentUserProvider.getUserId(jwt)).thenReturn(userId);
        when(templateService.createNewTemplate(userId, template, List.of(field))).thenReturn(template);
        when(templateService.getCardTemplateById(templateId, 2)).thenReturn(template);
        when(templateService.getTemplateVersions(templateId)).thenReturn(List.of(1, 2));
        when(templateService.partiallyChangeCardTemplate(userId, templateId, template)).thenReturn(template);
        when(templateService.addFieldToTemplate(userId, templateId, field)).thenReturn(field);
        when(templateService.partiallyChangeFieldTemplate(userId, templateId, fieldId, field)).thenReturn(field);

        assertThat(controller.createNewCardTemplate(jwt, template, List.of(field))).isEqualTo(template);
        assertThat(controller.getTemplateById(templateId, 2)).isEqualTo(template);
        assertThat(controller.getTemplateVersions(templateId)).containsExactly(1, 2);
        assertThat(controller.patchTemplateById(jwt, templateId, template)).isEqualTo(template);
        assertThat(controller.addNewFieldToTemplate(jwt, templateId, field)).isEqualTo(field);
        assertThat(controller.partialFixToField(templateId, fieldId, jwt, field)).isEqualTo(field);

        controller.deleteTemplateById(jwt, templateId);
        controller.deleteFieldFromTemplate(jwt, templateId, fieldId);

        verify(templateService).deleteTemplate(userId, templateId);
        verify(templateService).deleteFieldFromTemplate(userId, templateId, fieldId);
    }

    private static CardTemplateDTO template() {
        return new CardTemplateDTO(
                UUID.randomUUID(),
                1,
                1,
                UUID.randomUUID(),
                "Basic",
                "Description",
                true,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                List.of()
        );
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "none").claim("user_id", UUID.randomUUID().toString()).build();
    }
}
