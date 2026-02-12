package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.FieldTemplateDTO;
import app.mnema.core.deck.service.TemplateService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final CurrentUserProvider currentUserProvider;

    public TemplateController(TemplateService templateService, CurrentUserProvider currentUserProvider) {
        this.templateService = templateService;
        this.currentUserProvider = currentUserProvider;
    }

    // GET /api/core/templates?page=1&limit=10
    @GetMapping
    public Page<CardTemplateDTO> getTemplatesPaginated(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "public") String scope
    ) {
        if ("public".equalsIgnoreCase(scope)) {
            return templateService.getCardTemplatesByPage(page, limit);
        }

        if (jwt == null) {
            throw new IllegalArgumentException("Authentication required for scope: " + scope);
        }

        var userId = currentUserProvider.getUserId(jwt);

        if ("mine".equalsIgnoreCase(scope)) {
            return templateService.getUserTemplatesByPage(userId, page, limit);
        }

        if ("all".equalsIgnoreCase(scope)) {
            return templateService.getTemplatesForUserAndPublic(userId, page, limit);
        }

        throw new IllegalArgumentException("Unknown template scope: " + scope);
    }

    // POST /api/core/templates - создать шаблон (вместе с полями)
    @PostMapping
    public CardTemplateDTO createNewCardTemplate(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CardTemplateDTO dto,
            @RequestParam(required = false) List<FieldTemplateDTO> fieldsDto
    ) {
        var userId = currentUserProvider.getUserId(jwt);

        return templateService.createNewTemplate(userId, dto, fieldsDto);
    }

    // GET /api/core/templates/{templateId}
    @GetMapping("/{templateId}")
    public CardTemplateDTO getTemplateById(@PathVariable UUID templateId,
                                           @RequestParam(required = false) Integer version) {
        return templateService.getCardTemplateById(templateId, version);
    }

    // GET /api/core/templates/{templateId}/versions
    @GetMapping("/{templateId}/versions")
    public List<Integer> getTemplateVersions(@PathVariable UUID templateId) {
        return templateService.getTemplateVersions(templateId);
    }

    // PATCH /api/core/templates/{templateId}
    @PatchMapping("/{templateId}")
    public CardTemplateDTO patchTemplateById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID templateId,
            @RequestBody CardTemplateDTO dto
    ) {

        var userId = currentUserProvider.getUserId(jwt);
        return templateService.partiallyChangeCardTemplate(userId, templateId, dto);
    }

    // DELETE /api/core/templates/{templateId}
    @DeleteMapping("/{templateId}")
    public void deleteTemplateById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID templateId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        templateService.deleteTemplate(userId, templateId);
    }

    // POST /api/core/templates/{templateId}/fields
    @PostMapping("/{templateId}/fields")
    public FieldTemplateDTO addNewFieldToTemplate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID templateId,
            @RequestBody FieldTemplateDTO dto
    ) {

        var userId = currentUserProvider.getUserId(jwt);
        return templateService.addFieldToTemplate(userId, templateId, dto);
    }

    // PATCH /api/core/templates/{templateId}/fields/{fieldId}
    @PatchMapping("/{templateId}/fields/{fieldId}")
    public FieldTemplateDTO partialFixToField(
            @PathVariable UUID templateId,
            @PathVariable UUID fieldId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody FieldTemplateDTO dto
    ) {
        var userId = currentUserProvider.getUserId(jwt);

        return templateService.partiallyChangeFieldTemplate(userId, templateId, fieldId, dto);
    }

    // DELETE /api/core/templates/{templateId}/fields/{fieldId}
    @DeleteMapping("/{templateId}/fields/{fieldId}")
    public void deleteFieldFromTemplate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID templateId,
            @PathVariable UUID fieldId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        templateService.deleteFieldFromTemplate(userId, templateId, fieldId);
    }
}
