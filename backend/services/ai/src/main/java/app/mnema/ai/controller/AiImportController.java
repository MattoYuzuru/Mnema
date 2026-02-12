package app.mnema.ai.controller;

import app.mnema.ai.controller.dto.AiImportGenerateRequest;
import app.mnema.ai.controller.dto.AiImportPreviewRequest;
import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.service.AiImportService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/imports")
public class AiImportController {

    private final AiImportService importService;

    public AiImportController(AiImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/preview")
    public AiJobResponse preview(@AuthenticationPrincipal Jwt jwt,
                                 @Valid @RequestBody AiImportPreviewRequest request) {
        String accessToken = jwt == null ? null : jwt.getTokenValue();
        return importService.createPreviewJob(jwt, accessToken, request);
    }

    @PostMapping("/generate")
    public AiJobResponse generate(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody AiImportGenerateRequest request) {
        String accessToken = jwt == null ? null : jwt.getTokenValue();
        return importService.createGenerateJob(jwt, accessToken, request);
    }
}
