package app.mnema.learning.media;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC serialization and error contract; storage protocol is exercised separately. */
class MediaUploadControllerTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final MediaUploadService service = mock(MediaUploadService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new MediaUploadController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void intentAndPartUrlsArePrivateAndNeverExposeStorageKeys() throws Exception {
        UUID intent = UUID.randomUUID();
        when(service.start(owner, intent, "upload", "image", "image/png", 3))
                .thenReturn(new MediaUploadService.UploadView(asset, 0, "OPEN", 0, "PENDING_UPLOAD",
                        "SINGLE", 3,
                        "image/png", Instant.parse("2026-09-29T00:00:00Z"), null, null,
                        "https://storage.example/signed", Map.of("content-length", "3"),
                        Instant.parse("2026-09-28T12:00:00Z"), List.of()));
        mvc.perform(post("/media-assets/upload-intents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intentId\":\"" + intent + "\",\"origin\":\"upload\",\"kind\":\"image\","
                                + "\"mime\":\"image/png\",\"byteLength\":3}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.assetId").value(asset.toString()))
                .andExpect(jsonPath("$.url").value("https://storage.example/signed"))
                .andExpect(jsonPath("$.stagingKey").doesNotExist())
                .andExpect(jsonPath("$.storageUploadId").doesNotExist());
    }

    @Test
    void invalidPathAndUploadConflictUseStableProblemCodes() throws Exception {
        mvc.perform(post("/media-assets/upload-intents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"origin\":\"upload\",\"kind\":\"image\",\"mime\":\"image/png\",\"byteLength\":3}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/media-assets/bad-id/upload"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        when(service.status(owner, asset)).thenThrow(new MediaUploadConflictException());
        mvc.perform(get("/media-assets/" + asset + "/upload"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEDIA_UPLOAD_CONFLICT"));
        UUID command = UUID.randomUUID();
        mvc.perform(post("/media-assets/upload-intents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intentId\":\"" + command + "\",\"intentId\":\"" + command
                                + "\",\"origin\":\"upload\",\"kind\":\"image\",\"mime\":\"image/png\","
                                + "\"byteLength\":3}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/media-assets/upload-intents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"padding\":\"" + "x".repeat(3_000) + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
