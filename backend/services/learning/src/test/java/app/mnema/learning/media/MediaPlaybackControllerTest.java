package app.mnema.learning.media;

import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MediaPlaybackControllerTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final MediaCatalog catalog = mock(MediaCatalog.class);
    private final MediaPlaybackStore store = mock(MediaPlaybackStore.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new MediaPlaybackController(catalog, store))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void pendingAssetNeverReceivesObjectLocation() throws Exception {
        when(catalog.playback(owner, asset))
                .thenReturn(new MediaCatalog.PlaybackDescriptor("PROCESSING", null, null, null));
        mvc.perform(get("/media-assets/" + asset + "/playback"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.state").value("PROCESSING"))
                .andExpect(jsonPath("$.playback").isEmpty())
                .andExpect(jsonPath("$.download").isEmpty());
        verifyNoInteractions(store);
    }

    @Test
    void readyAssetReturnsOnlySignedVariantsAndAttachmentLink() throws Exception {
        var playable = new MediaCatalog.BlobLocation(UUID.randomUUID(), "private/playback", 200, "video/mp4");
        var poster = new MediaCatalog.BlobLocation(UUID.randomUUID(), "private/poster", 20, "image/webp");
        var original = new MediaCatalog.BlobLocation(UUID.randomUUID(), "private/original", 300, "video/quicktime");
        when(catalog.playback(owner, asset))
                .thenReturn(new MediaCatalog.PlaybackDescriptor("READY", playable, poster, original));
        Instant expires = Instant.parse("2026-09-28T12:00:00Z");
        when(store.read("private/playback", false)).thenReturn(new MediaPlaybackStore.SignedRead("https://s3.test/play", expires));
        when(store.read("private/poster", false)).thenReturn(new MediaPlaybackStore.SignedRead("https://s3.test/poster", expires));
        when(store.read("private/original", true)).thenReturn(new MediaPlaybackStore.SignedRead("https://s3.test/save", expires));
        mvc.perform(get("/media-assets/" + asset + "/playback"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.playback.url").value("https://s3.test/play"))
                .andExpect(jsonPath("$.playback.mimeType").value("video/mp4"))
                .andExpect(jsonPath("$.poster.url").value("https://s3.test/poster"))
                .andExpect(jsonPath("$.download.url").value("https://s3.test/save"))
                .andExpect(jsonPath("$.objectKey").doesNotExist());
        verify(store).read("private/original", true);
    }

    @Test
    void foreignAssetUsesTheSameNotFoundBoundary() throws Exception {
        when(catalog.playback(owner, asset)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get("/media-assets/" + asset + "/playback"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(store);
    }

    @Test
    void offlineVariantDownloadStillChecksOwnerAndReachability() throws Exception {
        UUID variant = UUID.randomUUID();
        var location = new MediaCatalog.BlobLocation(UUID.randomUUID(), "private/variant", 20, "image/webp");
        when(catalog.resolve(owner, asset, variant)).thenReturn(location);
        when(store.read("private/variant", true)).thenReturn(new MediaPlaybackStore.SignedRead(
                "https://s3.test/variant", Instant.parse("2026-09-28T12:00:00Z")));
        mvc.perform(get("/media-assets/" + asset + "/variants/" + variant + "/download"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.url").value("https://s3.test/variant"))
                .andExpect(jsonPath("$.mimeType").value("image/webp"));
        UUID foreign = UUID.randomUUID();
        when(catalog.resolve(owner, asset, foreign)).thenThrow(new ResourceNotFoundException());
        mvc.perform(get("/media-assets/" + asset + "/variants/" + foreign + "/download"))
                .andExpect(status().isNotFound());
        verify(store, times(1)).read(anyString(), anyBoolean());
    }
}
