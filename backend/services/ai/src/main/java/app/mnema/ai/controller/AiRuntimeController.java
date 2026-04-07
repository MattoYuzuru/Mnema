package app.mnema.ai.controller;

import app.mnema.ai.controller.dto.AiRuntimeCapabilitiesResponse;
import app.mnema.ai.service.AiRuntimeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/runtime")
public class AiRuntimeController {

    private final AiRuntimeService runtimeService;

    public AiRuntimeController(AiRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @GetMapping("/capabilities")
    public AiRuntimeCapabilitiesResponse capabilities() {
        return runtimeService.getCapabilities();
    }
}
