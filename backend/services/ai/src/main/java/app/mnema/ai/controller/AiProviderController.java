package app.mnema.ai.controller;

import app.mnema.ai.controller.dto.AiProviderResponse;
import app.mnema.ai.controller.dto.CreateAiProviderRequest;
import app.mnema.ai.controller.dto.UpdateAiProviderStatusRequest;
import app.mnema.ai.service.AiProviderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/providers")
public class AiProviderController {

    private final AiProviderService providerService;

    public AiProviderController(AiProviderService providerService) {
        this.providerService = providerService;
    }

    @PostMapping
    public AiProviderResponse create(@AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody CreateAiProviderRequest request) {
        return providerService.createCredential(jwt, request);
    }

    @GetMapping
    public List<AiProviderResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return providerService.listActiveCredentials(jwt);
    }

    @PatchMapping("/{id}")
    public AiProviderResponse updateStatus(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody UpdateAiProviderStatusRequest request) {
        return providerService.updateStatus(jwt, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt,
                       @PathVariable UUID id) {
        providerService.deleteCredential(jwt, id);
    }
}
