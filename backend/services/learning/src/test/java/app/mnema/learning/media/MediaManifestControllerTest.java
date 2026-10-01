package app.mnema.learning.media;

import app.mnema.learning.platform.api.ApiExceptionHandler;
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

class MediaManifestControllerTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final MediaManifestCatalog catalog = mock(MediaManifestCatalog.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        mvc = MockMvcBuilders.standaloneSetup(new MediaManifestController(catalog))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void conditionalReadKeepsTheImmutableETagAndPrivateCacheBoundary() throws Exception {
        var manifest = new MediaManifestCatalog.Snapshot(UUID.randomUUID(), 2, "\"1234\"",
                "{\"schemaVersion\":1}", Instant.parse("2026-12-27T12:00:00Z"), new byte[32]);
        when(catalog.current(owner, deck)).thenReturn(manifest);
        when(catalog.read(owner, deck, manifest.id())).thenReturn(manifest);
        mvc.perform(get("/decks/" + deck + "/media-manifests/current"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", manifest.etag()))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().json(manifest.body()));
        mvc.perform(get("/decks/" + deck + "/media-manifests/" + manifest.id())
                        .header("If-None-Match", manifest.etag()))
                .andExpect(status().isNotModified()).andExpect(header().string("ETag", manifest.etag()))
                .andExpect(content().string(""));
    }
}
