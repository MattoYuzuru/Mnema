package app.mnema.importer.service;

import app.mnema.importer.client.core.CoreApiClient;
import app.mnema.importer.client.core.CoreCardTemplateResponse;
import app.mnema.importer.client.core.CoreFieldTemplate;
import app.mnema.importer.client.core.CorePageResponse;
import app.mnema.importer.client.core.CorePublicDeckResponse;
import app.mnema.importer.client.core.CoreUserCardResponse;
import app.mnema.importer.client.core.CoreUserDeckResponse;
import app.mnema.importer.client.media.MediaApiClient;
import app.mnema.importer.domain.ImportSourceType;
import app.mnema.importer.domain.ImportJobEntity;
import app.mnema.importer.repository.ImportJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ExportProcessor {

    private final CoreApiClient coreApiClient;
    private final MediaApiClient mediaApiClient;
    private final ImportJobRepository jobRepository;
    private final int pageSize;

    public ExportProcessor(CoreApiClient coreApiClient,
                           MediaApiClient mediaApiClient,
                           ImportJobRepository jobRepository,
                           @Value("${app.import.export-page-size:200}") int pageSize) {
        this.coreApiClient = coreApiClient;
        this.mediaApiClient = mediaApiClient;
        this.jobRepository = jobRepository;
        this.pageSize = pageSize;
    }

    public void process(ImportJobEntity job) {
        UUID userDeckId = job.getTargetDeckId();
        if (userDeckId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userDeckId is required for export");
        }
        if (job.getSourceType() != null && job.getSourceType() != ImportSourceType.csv) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only csv export is supported right now");
        }

        CoreUserDeckResponse userDeck = coreApiClient.getUserDeck(job.getUserAccessToken(), userDeckId);
        CorePublicDeckResponse publicDeck = coreApiClient.getPublicDeck(job.getUserAccessToken(), userDeck.publicDeckId(), userDeck.currentVersion());
        CoreCardTemplateResponse template = coreApiClient.getTemplate(job.getUserAccessToken(), publicDeck.templateId());
        List<CoreFieldTemplate> fields = template.fields();

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("mnema-export-");
            Path csvFile = tempDir.resolve("deck.csv");
            writeCsv(job, fields, csvFile, userDeckId);

            Path zipFile = tempDir.resolve("deck-export.zip");
            zipCsv(csvFile, zipFile);

            try (InputStream inputStream = Files.newInputStream(zipFile)) {
                UUID mediaId = mediaApiClient.directUpload(
                        job.getUserId(),
                        "import_file",
                        "application/zip",
                        "deck-export.zip",
                        Files.size(zipFile),
                        inputStream
                );
                updateResult(job.getJobId(), mediaId);
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export failed", ex);
        } finally {
            cleanup(tempDir);
        }
    }

    private void writeCsv(ImportJobEntity job,
                          List<CoreFieldTemplate> fields,
                          Path csvFile,
                          UUID userDeckId) throws IOException {
        List<String> headers = fields.stream().map(CoreFieldTemplate::name).toList();
        try (BufferedWriter writer = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(headers.toArray(String[]::new)).build())) {
            int page = 1;
            int processed = 0;
            while (true) {
                CorePageResponse<CoreUserCardResponse> pageResult = coreApiClient.getUserCards(job.getUserAccessToken(), userDeckId, page, pageSize);
                if (pageResult == null || pageResult.content() == null || pageResult.content().isEmpty()) {
                    break;
                }
                for (CoreUserCardResponse card : pageResult.content()) {
                    List<String> row = new ArrayList<>();
                    for (CoreFieldTemplate field : fields) {
                        row.add(extractValue(card.effectiveContent(), field.name()));
                    }
                    printer.printRecord(row);
                    processed++;
                }
                updateProgress(job.getJobId(), processed);
                if (pageResult.last()) {
                    break;
                }
                page++;
            }
            updateTotals(job.getJobId(), processed);
        }
    }

    private String extractValue(JsonNode content, String fieldName) {
        if (content == null || !content.has(fieldName)) {
            return "";
        }
        JsonNode value = content.get(fieldName);
        if (value.isObject()) {
            JsonNode mediaId = value.get("mediaId");
            return mediaId == null ? "" : mediaId.asText();
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }

    private void zipCsv(Path csvFile, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry("deck.csv");
            zos.putNextEntry(entry);
            Files.copy(csvFile, zos);
            zos.closeEntry();
        }
    }

    @Transactional
    protected void updateResult(UUID jobId, UUID mediaId) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setResultMediaId(mediaId);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    protected void updateProgress(UUID jobId, int processed) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedItems(processed);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    @Transactional
    protected void updateTotals(UUID jobId, int processed) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessedItems(processed);
            job.setTotalItems(processed);
            job.setUpdatedAt(Instant.now());
            jobRepository.save(job);
        });
    }

    private void cleanup(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try (var stream = Files.list(tempDir)) {
            stream.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(tempDir);
        } catch (IOException ignored) {
        }
    }
}
