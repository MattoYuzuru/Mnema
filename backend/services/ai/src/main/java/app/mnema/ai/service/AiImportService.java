package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiImportGenerateRequest;
import app.mnema.ai.controller.dto.AiImportPreviewRequest;
import app.mnema.ai.controller.dto.AiJobResponse;
import app.mnema.ai.controller.dto.CreateAiJobRequest;
import app.mnema.ai.domain.type.AiJobType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiImportService {

    private static final String MODE_IMPORT_PREVIEW = "import_preview";
    private static final String MODE_IMPORT_GENERATE = "import_generate";

    private final AiJobService jobService;
    private final ObjectMapper objectMapper;

    public AiImportService(AiJobService jobService, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    public AiJobResponse createPreviewJob(Jwt jwt, String accessToken, AiImportPreviewRequest request) {
        ObjectNode params = baseParams(request.providerCredentialId(),
                request.sourceMediaId().toString(),
                request.sourceType(),
                request.encoding(),
                request.language(),
                request.model(),
                request.instructions());
        params.put("mode", MODE_IMPORT_PREVIEW);
        if (request.stt() != null && !request.stt().isNull()) {
            params.set("stt", request.stt());
        }
        return jobService.createJob(jwt, accessToken, new CreateAiJobRequest(
                request.requestId(),
                request.deckId(),
                AiJobType.generic,
                params,
                null,
                null,
                null
        ));
    }

    public AiJobResponse createGenerateJob(Jwt jwt, String accessToken, AiImportGenerateRequest request) {
        ObjectNode params = baseParams(request.providerCredentialId(),
                request.sourceMediaId().toString(),
                request.sourceType(),
                request.encoding(),
                request.language(),
                request.model(),
                request.instructions());
        params.put("mode", MODE_IMPORT_GENERATE);
        params.put("count", request.count());
        ArrayNode fieldsNode = params.putArray("fields");
        for (String field : safeFields(request.fields())) {
            fieldsNode.add(field);
        }
        if (request.tts() != null && !request.tts().isNull()) {
            params.set("tts", request.tts());
        }
        if (request.stt() != null && !request.stt().isNull()) {
            params.set("stt", request.stt());
        }
        if (request.image() != null && !request.image().isNull()) {
            params.set("image", request.image());
        }
        if (request.video() != null && !request.video().isNull()) {
            params.set("video", request.video());
        }

        return jobService.createJob(jwt, accessToken, new CreateAiJobRequest(
                request.requestId(),
                request.deckId(),
                AiJobType.generic,
                params,
                null,
                null,
                null
        ));
    }

    private ObjectNode baseParams(java.util.UUID providerCredentialId,
                                  String sourceMediaId,
                                  String sourceType,
                                  String encoding,
                                  String language,
                                  String model,
                                  String instructions) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("sourceMediaId", sourceMediaId);
        putOptional(params, "sourceType", sourceType);
        putOptional(params, "encoding", encoding);
        putOptional(params, "language", language);
        putOptional(params, "model", model);
        putOptional(params, "instructions", instructions);
        if (providerCredentialId != null) {
            params.put("providerCredentialId", providerCredentialId.toString());
        }
        return params;
    }

    private void putOptional(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value.trim());
        }
    }

    private List<String> safeFields(List<String> fields) {
        if (fields == null) {
            return List.of();
        }
        return fields.stream()
                .filter(field -> field != null && !field.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
