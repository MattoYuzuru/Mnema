package app.mnema.ai.service;

import app.mnema.ai.domain.entity.AiJobEntity;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiJobProcessorRouter implements AiJobProcessor {

    private final Map<String, AiProviderProcessor> processors;
    private final AiProviderCredentialRepository credentialRepository;
    private final ObjectMapper objectMapper;
    private final String defaultProvider;

    public AiJobProcessorRouter(List<AiProviderProcessor> processors,
                                AiProviderCredentialRepository credentialRepository,
                                ObjectMapper objectMapper,
                                @Value("${app.ai.provider:stub}") String defaultProvider) {
        this.processors = processors.stream()
                .collect(Collectors.toMap(
                        p -> normalizeProvider(p.provider()),
                        Function.identity(),
                        (a, b) -> a
                ));
        this.credentialRepository = credentialRepository;
        this.objectMapper = objectMapper;
        this.defaultProvider = normalizeProvider(defaultProvider);
    }

    @Override
    public AiJobProcessingResult process(AiJobEntity job) {
        String provider = resolveProvider(job);
        AiProviderProcessor processor = processors.get(provider);
        if (processor == null) {
            throw new IllegalStateException("Unsupported AI provider: " + provider);
        }
        return processor.process(job);
    }

    private String resolveProvider(AiJobEntity job) {
        JsonNode params = job.getParamsJson() == null ? objectMapper.createObjectNode() : job.getParamsJson();
        UUID credentialId = parseUuid(params.path("providerCredentialId").asText(null));
        if (credentialId != null) {
            AiProviderCredentialEntity credential = credentialRepository.findByIdAndUserId(credentialId, job.getUserId())
                    .orElseThrow(() -> new IllegalStateException("Provider credential not found"));
            if (credential.getStatus() != AiProviderStatus.active) {
                throw new IllegalStateException("Provider credential is inactive");
            }
            return normalizeProvider(credential.getProvider());
        }

        String providerFromParams = params.path("provider").asText(null);
        if (providerFromParams != null && !providerFromParams.isBlank()) {
            return normalizeProvider(providerFromParams);
        }

        if (defaultProvider == null || defaultProvider.isBlank()) {
            return "stub";
        }
        return defaultProvider;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid providerCredentialId");
        }
    }

    private String normalizeProvider(String provider) {
        if (provider == null) {
            return "stub";
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }
}
