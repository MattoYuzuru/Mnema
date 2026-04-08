package app.mnema.ai.service;

import app.mnema.ai.controller.dto.CreateAiProviderRequest;
import app.mnema.ai.controller.dto.UpdateAiProviderStatusRequest;
import app.mnema.ai.domain.entity.AiProviderCredentialEntity;
import app.mnema.ai.domain.type.AiProviderStatus;
import app.mnema.ai.repository.AiProviderCredentialRepository;
import app.mnema.ai.security.CurrentUserProvider;
import app.mnema.ai.vault.EncryptedSecret;
import app.mnema.ai.vault.SecretVault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiProviderServiceTest {

    @Mock
    private AiProviderCredentialRepository credentialRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private SecretVault secretVault;

    @Mock
    private Jwt jwt;

    private AiProviderService service;

    @BeforeEach
    void setUp() {
        service = new AiProviderService(credentialRepository, currentUserProvider, secretVault);
    }

    @Test
    void createCredentialNormalizesProviderAndAliasAndEncryptsSecret() {
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(jwt)).thenReturn(userId);
        when(secretVault.encrypt(any())).thenReturn(new EncryptedSecret(
                new byte[]{1}, new byte[]{2}, "key-1", new byte[]{3}, new byte[]{4}
        ));
        when(credentialRepository.save(any(AiProviderCredentialEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createCredential(jwt, new CreateAiProviderRequest(" x.ai ", "  Primary ", " secret "));

        assertThat(response.provider()).isEqualTo("grok");
        assertThat(response.alias()).isEqualTo("Primary");
        assertThat(response.status()).isEqualTo(AiProviderStatus.active);

        ArgumentCaptor<AiProviderCredentialEntity> captor = ArgumentCaptor.forClass(AiProviderCredentialEntity.class);
        verify(credentialRepository).save(captor.capture());
        AiProviderCredentialEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getProvider()).isEqualTo("grok");
        assertThat(saved.getAlias()).isEqualTo("Primary");
        assertThat(saved.getEncryptedSecret()).containsExactly((byte) 1);
        assertThat(saved.getEncryptedDataKey()).containsExactly((byte) 2);
        assertThat(saved.getKeyId()).isEqualTo("key-1");
        assertThat(saved.getNonce()).containsExactly((byte) 3);
        assertThat(saved.getAad()).containsExactly((byte) 4);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void createCredentialRejectsBlankSecretAndMissingProvider() {
        when(currentUserProvider.requireUserId(jwt)).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.createCredential(jwt, new CreateAiProviderRequest("  ", null, "secret")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.createCredential(jwt, new CreateAiProviderRequest("openai", null, "  ")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("Secret is required"));

        verify(credentialRepository, never()).save(any());
    }

    @Test
    void listUpdateAndDeleteUseCurrentUserScope() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(jwt)).thenReturn(userId);

        AiProviderCredentialEntity entity = new AiProviderCredentialEntity();
        entity.setId(credentialId);
        entity.setUserId(userId);
        entity.setProvider("anthropic");
        entity.setAlias("Claude");
        entity.setStatus(AiProviderStatus.active);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        when(credentialRepository.findByUserIdAndStatus(userId, AiProviderStatus.active)).thenReturn(List.of(entity));
        when(credentialRepository.findByIdAndUserId(credentialId, userId)).thenReturn(Optional.of(entity));
        when(credentialRepository.save(any(AiProviderCredentialEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.listActiveCredentials(jwt)).singleElement().satisfies(response -> {
            assertThat(response.provider()).isEqualTo("anthropic");
            assertThat(response.alias()).isEqualTo("Claude");
        });

        var updated = service.updateStatus(jwt, credentialId, new UpdateAiProviderStatusRequest(false));
        assertThat(updated.status()).isEqualTo(AiProviderStatus.inactive);

        service.deleteCredential(jwt, credentialId);

        verify(credentialRepository).delete(entity);
    }

    @Test
    void wrapsUserIdResolutionAndMissingCredentialAsHttpErrors() {
        UUID userId = UUID.randomUUID();
        UUID credentialId = UUID.randomUUID();
        when(currentUserProvider.requireUserId(jwt))
                .thenThrow(new IllegalStateException("user_id claim missing"))
                .thenReturn(userId);

        assertThatThrownBy(() -> service.listActiveCredentials(jwt))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        when(credentialRepository.findByIdAndUserId(credentialId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(jwt, credentialId, new UpdateAiProviderStatusRequest(true)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
