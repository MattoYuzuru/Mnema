package app.mnema.ai.service;

import app.mnema.ai.controller.dto.AiProviderResponse;
import app.mnema.ai.controller.dto.CreateAiProviderRequest;
import app.mnema.ai.controller.dto.UpdateAiProviderStatusRequest;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.security.CurrentUserProvider;
import app.mnema.ai.vault.EncryptedSecret;
import app.mnema.ai.vault.SecretVault;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AiProviderService {

    private final AiProviderCredentialRepository credentialRepository;
    private final CurrentUserProvider currentUserProvider;
    private final SecretVault secretVault;

    public AiProviderService(AiProviderCredentialRepository credentialRepository,
                             CurrentUserProvider currentUserProvider,
                             SecretVault secretVault) {
        this.credentialRepository = credentialRepository;
        this.currentUserProvider = currentUserProvider;
        this.secretVault = secretVault;
    }

    @Transactional
    public AiProviderResponse createCredential(Jwt jwt, CreateAiProviderRequest request) {
        UUID userId = requireUserId(jwt);
        String provider = normalizeProvider(request.provider());
        String alias = normalizeAlias(request.alias());
        if (request.secret() == null || request.secret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Secret is required");
        }

        EncryptedSecret encrypted = secretVault.encrypt(request.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();

        AiProviderCredentialEntity entity = new AiProviderCredentialEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setProvider(provider);
        entity.setAlias(alias);
        entity.setEncryptedSecret(encrypted.ciphertext());
        entity.setEncryptedDataKey(encrypted.encryptedDataKey());
        entity.setKeyId(encrypted.keyId());
        entity.setNonce(encrypted.nonce());
        entity.setAad(encrypted.aad());
        entity.setStatus(AiProviderStatus.active);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        return toResponse(credentialRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<AiProviderResponse> listActiveCredentials(Jwt jwt) {
        UUID userId = requireUserId(jwt);
        return credentialRepository.findByUserIdAndStatus(userId, AiProviderStatus.active)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AiProviderResponse updateStatus(Jwt jwt, UUID id, UpdateAiProviderStatusRequest request) {
        UUID userId = requireUserId(jwt);
        AiProviderCredentialEntity entity = credentialRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider credential not found"));
        AiProviderStatus status = request.active() ? AiProviderStatus.active : AiProviderStatus.inactive;
        entity.setStatus(status);
        entity.setUpdatedAt(Instant.now());
        return toResponse(credentialRepository.save(entity));
    }

    @Transactional
    public void deleteCredential(Jwt jwt, UUID id) {
        UUID userId = requireUserId(jwt);
        AiProviderCredentialEntity entity = credentialRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider credential not found"));
        credentialRepository.delete(entity);
    }

    private UUID requireUserId(Jwt jwt) {
        try {
            return currentUserProvider.requireUserId(jwt);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage());
        }
    }

    private String normalizeProvider(String provider) {
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider is required");
        }
        String trimmed = provider.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider is required");
        }
        String normalized = trimmed.toLowerCase();
        if ("claude".equals(normalized)) {
            return "anthropic";
        }
        if ("google".equals(normalized) || "google-gemini".equals(normalized)) {
            return "gemini";
        }
        if ("xai".equals(normalized) || "x.ai".equals(normalized)) {
            return "grok";
        }
        if ("dashscope".equals(normalized) || "aliyun".equals(normalized) || "alibaba".equals(normalized)) {
            return "qwen";
        }
        return normalized;
    }

    private String normalizeAlias(String alias) {
        if (alias == null) {
            return null;
        }
        String trimmed = alias.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AiProviderResponse toResponse(AiProviderCredentialEntity entity) {
        return new AiProviderResponse(
                entity.getId(),
                entity.getProvider(),
                entity.getAlias(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getLastUsedAt(),
                entity.getUpdatedAt()
        );
    }
}
