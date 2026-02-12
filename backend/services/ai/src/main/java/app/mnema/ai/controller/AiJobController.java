package app.mnema.ai.controller;

import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.AiJobResultResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.service.AiJobService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class AiJobController {

    private final AiJobService jobService;

    public AiJobController(AiJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public AiJobResponse create(@AuthenticationPrincipal Jwt jwt,
                                @Valid @RequestBody CreateAiJobRequest request) {
        String accessToken = jwt == null ? null : jwt.getTokenValue();
        return jobService.createJob(jwt, accessToken, request);
    }

    @GetMapping
    public List<AiJobResponse> list(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam UUID deckId,
                                    @RequestParam(defaultValue = "20") int limit) {
        return jobService.listJobs(jwt, deckId, limit);
    }

    @GetMapping("/{jobId}")
    public AiJobResponse getJob(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable UUID jobId) {
        return jobService.getJob(jwt, jobId);
    }

    @GetMapping("/{jobId}/results")
    public AiJobResultResponse getResults(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable UUID jobId) {
        return jobService.getJobResult(jwt, jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public AiJobResponse cancel(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable UUID jobId) {
        return jobService.cancelJob(jwt, jobId);
    }
}
