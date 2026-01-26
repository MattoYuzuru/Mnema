package app.mnema.ai.provider.openai;

import app.mnema.ai.client.media.MediaApiClient;
import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiJobType;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.service.AiJobProcessingResult;
import app.mnema.ai.service.AiJobProcessor;
import app.mnema.ai.vault.EncryptedSecret;
import app.mnema.ai.vault.SecretVault;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "openai")
public class OpenAiJobProcessor implements AiJobProcessor {

    private static final String PROVIDER = "openai";

    private final OpenAiClient openAiClient;
    private final OpenAiProps props;
    private final SecretVault secretVault;
    private final AiProviderCredentialRepository credentialRepository;
    private final MediaApiClient mediaApiClient;
    private final ObjectMapper objectMapper;

    public OpenAiJobProcessor(OpenAiClient openAiClient,
                              OpenAiProps props,
                              SecretVault secretVault,
                              AiProviderCredentialRepository credentialRepository,
                              MediaApiClient mediaApiClient,
                              ObjectMapper objectMapper) {
        this.openAiClient = openAiClient;
        this.props = props;
        this.secretVault = secretVault;
        this.credentialRepository = credentialRepository;
        this.mediaApiClient = mediaApiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiJobProcessingResult process(AiJobEntity job) {
        AiProviderCredentialEntity credential = resolveCredential(job);
        String apiKey = decryptSecret(credential);
        credential.setLastUsedAt(Instant.now());
        credential.setUpdatedAt(Instant.now());
        credentialRepository.save(credential);

        if (job.getType() == AiJobType.tts) {
            return handleTts(job, apiKey);
        }
        return handleText(job, apiKey);
    }

    private AiJobProcessingResult handleText(AiJobEntity job, String apiKey) {
        JsonNode params = safeParams(job);
        String input = extractTextParam(params, "input", "prompt", "text");
        if (input == null || input.isBlank()) {
            input = params.isMissingNode() ? "" : params.toString();
        }
        String model = textOrDefault(params.path("model"), props.defaultModel());
        Integer maxOutputTokens = params.path("maxOutputTokens").isInt()
                ? params.path("maxOutputTokens").asInt()
                : null;

        OpenAiResponseResult response = openAiClient.createResponse(
                apiKey,
                new OpenAiResponseRequest(model, input, maxOutputTokens)
        );

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("text", response.outputText());
        summary.put("model", response.model());
        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                response.model(),
                response.inputTokens(),
                response.outputTokens(),
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private AiJobProcessingResult handleTts(AiJobEntity job, String apiKey) {
        JsonNode params = safeParams(job);
        String text = extractTextParam(params, "text", "input", "prompt");
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("TTS text is required");
        }
        String model = textOrDefault(params.path("model"), props.defaultTtsModel());
        String voice = textOrDefault(params.path("voice"), props.defaultVoice());
        String format = textOrDefault(params.path("format"), props.defaultTtsFormat());

        byte[] audio = openAiClient.createSpeech(
                apiKey,
                new OpenAiSpeechRequest(model, text, voice, format)
        );

        String contentType = resolveAudioContentType(format);
        String fileName = "ai-tts-" + job.getJobId() + "." + format;
        UUID mediaId = mediaApiClient.directUpload(
                job.getUserId(),
                "card_audio",
                contentType,
                fileName,
                audio.length,
                new ByteArrayInputStream(audio)
        );

        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("mediaId", mediaId.toString());
        summary.put("contentType", contentType);
        summary.put("fileName", fileName);
        summary.put("model", model);
        summary.put("voice", voice);

        return new AiJobProcessingResult(
                summary,
                PROVIDER,
                model,
                null,
                null,
                BigDecimal.ZERO,
                job.getInputHash()
        );
    }

    private AiProviderCredentialEntity resolveCredential(AiJobEntity job) {
        JsonNode params = safeParams(job);
        UUID credentialId = parseUuid(params.path("providerCredentialId").asText(null));
        if (credentialId != null) {
            return credentialRepository.findByIdAndUserId(credentialId, job.getUserId())
                    .orElseThrow(() -> new IllegalStateException("Provider credential not found"));
        }
        Optional<AiProviderCredentialEntity> credential = credentialRepository
                .findFirstByUserIdAndProviderAndStatusOrderByCreatedAtAsc(
                        job.getUserId(),
                        PROVIDER,
                        AiProviderStatus.active
                );
        return credential.orElseThrow(() -> new IllegalStateException("No active OpenAI credential"));
    }

    private String decryptSecret(AiProviderCredentialEntity credential) {
        EncryptedSecret encrypted = new EncryptedSecret(
                credential.getEncryptedSecret(),
                credential.getEncryptedDataKey(),
                credential.getKeyId(),
                credential.getNonce(),
                credential.getAad()
        );
        byte[] raw = secretVault.decrypt(encrypted);
        return new String(raw, StandardCharsets.UTF_8).trim();
    }

    private JsonNode safeParams(AiJobEntity job) {
        return job.getParamsJson() == null ? objectMapper.createObjectNode() : job.getParamsJson();
    }

    private String extractTextParam(JsonNode params, String... keys) {
        for (String key : keys) {
            JsonNode value = params.get(key);
            if (value != null && value.isTextual()) {
                String text = value.asText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private String textOrDefault(JsonNode node, String fallback) {
        if (node != null && node.isTextual()) {
            String value = node.asText();
            if (!value.isBlank()) {
                return value;
            }
        }
        return Objects.requireNonNullElse(fallback, "");
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String resolveAudioContentType(String format) {
        if ("wav".equalsIgnoreCase(format)) {
            return "audio/wav";
        }
        if ("ogg".equalsIgnoreCase(format)) {
            return "audio/ogg";
        }
        return "audio/mpeg";
    }
}
