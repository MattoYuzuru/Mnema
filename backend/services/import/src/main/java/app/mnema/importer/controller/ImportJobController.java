package app.mnema.importer.controller;

import app.mnema.importer.controller.dto.CreateExportJobRequest;
import app.mnema.importer.controller.dto.CreateImportJobRequest;
import app.mnema.importer.controller.dto.ImportJobResponse;
import app.mnema.importer.controller.dto.ImportPreviewRequest;
import app.mnema.importer.controller.dto.ImportPreviewResponse;
import app.mnema.importer.controller.dto.UploadImportSourceResponse;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.service.ImportJobService;
import app.mnema.importer.service.ImportPreviewService;
import app.mnema.importer.service.ImportSourceService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
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
    public ImportPreviewResponse preview(@RequestHeader("Authorization") String authorization,
                                         @Valid @RequestBody ImportPreviewRequest request) {
        return previewService.preview(extractToken(authorization), request);
    }

    @PostMapping("/jobs/import")
    public ImportJobResponse createImportJob(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader("Authorization") String authorization,
                                             @Valid @RequestBody CreateImportJobRequest request) {
        return jobService.createImportJob(jwt, extractToken(authorization), request);
    }

    @PostMapping("/jobs/export")
    public ImportJobResponse createExportJob(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader("Authorization") String authorization,
                                             @Valid @RequestBody CreateExportJobRequest request) {
        return jobService.createExportJob(jwt, extractToken(authorization), request);
    }

    @GetMapping("/jobs/{jobId}")
    public ImportJobResponse getJob(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable UUID jobId) {
        return jobService.getJob(jwt, jobId);
    }

    private String extractToken(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.trim();
        if (value.toLowerCase().startsWith("bearer ")) {
            return value.substring(7).trim();
        }
        return value;
    }
}
