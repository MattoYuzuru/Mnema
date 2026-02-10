package app.mnema.importer.controller;

import app.mnema.importer.controller.dto.*;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.service.ImportJobService;
import app.mnema.importer.service.ImportPreviewService;
import app.mnema.importer.service.ImportSourceService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping
public class ImportJobController {

    private final ImportJobService jobService;
    private final ImportSourceService sourceService;
    private final ImportPreviewService previewService;

    public ImportJobController(ImportJobService jobService,
                               ImportSourceService sourceService,
                               ImportPreviewService previewService) {
        this.jobService = jobService;
        this.sourceService = sourceService;
        this.previewService = previewService;
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadImportSourceResponse upload(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(required = false) ImportSourceType sourceType,
                                             @RequestPart("file") MultipartFile file) {
        return sourceService.uploadSource(jwt, file, sourceType);
    }

    @PostMapping("/previews")
    public ImportPreviewResponse preview(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody ImportPreviewRequest request) {
        String accessToken = jwt == null ? null : jwt.getTokenValue();
        return previewService.preview(accessToken, request);
    }

    @PostMapping("/jobs/import")
    public ImportJobResponse createImportJob(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody CreateImportJobRequest request) {
        String accessToken = jwt == null ? null : jwt.getTokenValue();
        return jobService.createImportJob(jwt, accessToken, request);
    }

    @PostMapping("/jobs/export")
    public ImportJobResponse createExportJob(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody CreateExportJobRequest request) {
        String accessToken = jwt == null ? null : jwt.getTokenValue();
        return jobService.createExportJob(jwt, accessToken, request);
    }

    @GetMapping("/jobs/{jobId}")
    public ImportJobResponse getJob(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable UUID jobId) {
        return jobService.getJob(jwt, jobId);
    }

}
