package app.mnema.importer.controller;

import app.mnema.importer.controller.dto.CreateImportJobRequest;
import app.mnema.importer.controller.dto.ImportJobResponse;
import app.mnema.importer.service.ImportJobService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class ImportJobController {

    private final ImportJobService jobService;

    public ImportJobController(ImportJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ImportJobResponse createJob(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody CreateImportJobRequest request) {
        return jobService.createJob(jwt, request);
    }

    @GetMapping("/{jobId}")
    public ImportJobResponse getJob(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable UUID jobId) {
        return jobService.getJob(jwt, jobId);
    }
}
