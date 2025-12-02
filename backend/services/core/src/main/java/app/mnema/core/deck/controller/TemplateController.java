package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.service.TemplateService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return templateService.getCardTemplatesByPage(page, limit);
    }

    // GET /api/core/templates/{templateId}
    @GetMapping("/{templateId}")
    public CardTemplateDTO getTemplateById(@PathVariable UUID templateId) {
        return templateService.getCardTemplateById(templateId);
    }

    @DeleteMapping("/{templateId}")
    public void deleteTemplateById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID templateId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        templateService.deleteTemplate(userId, templateId);
    }
}
