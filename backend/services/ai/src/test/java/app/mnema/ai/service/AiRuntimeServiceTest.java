package app.mnema.ai.service;

import app.mnema.ai.config.AiRuntimeProps;
import app.mnema.ai.controller.dto.AiRuntimeCapabilitiesResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiRuntimeServiceTest {

    @Test
    void getCapabilitiesMergesRuntimeModelsAndVoices() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ollama.test/api/tags"))
                .andRespond(withSuccess("""
                        {"models":[
                          {"name":"llava:latest","size":123,"modified_at":"2026-04-07T00:00:00Z"},
                          {"name":"flux-dev","size":456,"modified_at":"2026-04-06T00:00:00Z"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://openai.test/v1/models"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"id":"whisper-large-v3","metadata":{"capabilities":["stt"]}},
                          {"id":"kokoro-tts","metadata":{"capabilities":["tts"]}},
                          {"id":"llava:latest","metadata":{"capabilities":["vision","image"]}}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://openai.test/v1/audio/voices"))
                .andRespond(withSuccess("""
                        {"data":[{"id":"nova"},{"id":"alloy"}]}
                        """, MediaType.APPLICATION_JSON));

        AiRuntimeService service = new AiRuntimeService(
                new AiRuntimeProps(true, "ollama", true, "http://ollama.test", "http://openai.test"),
                builder
        );

        AiRuntimeCapabilitiesResponse response = service.getCapabilities();

        assertEquals("system_managed", response.mode());
        assertTrue(response.ollama().available());
        assertEquals(4, response.ollama().models().size());
        assertEquals(java.util.List.of("alloy", "nova"), response.ollama().voices());
        assertTrue(response.providers().stream().filter(provider -> provider.key().equals("ollama")).findFirst().orElseThrow().stt());
        assertTrue(response.providers().stream().filter(provider -> provider.key().equals("ollama")).findFirst().orElseThrow().tts());
        assertTrue(response.providers().stream().filter(provider -> provider.key().equals("ollama")).findFirst().orElseThrow().image());
        server.verify();
    }

    @Test
    void getCapabilitiesFallsBackWhenRuntimeEndpointsFail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ollama.test/api/tags")).andRespond(withServerError());
        server.expect(requestTo("http://openai.test/v1/models")).andRespond(withServerError());
        server.expect(requestTo("http://openai.test/v1/audio/voices")).andRespond(withServerError());

        AiRuntimeService service = new AiRuntimeService(
                new AiRuntimeProps(false, "user", true, "http://ollama.test", "http://openai.test"),
                builder
        );

        AiRuntimeCapabilitiesResponse response = service.getCapabilities();

        assertEquals("user_keys", response.mode());
        assertFalse(response.ollama().available());
        assertTrue(response.ollama().models().isEmpty());
        assertTrue(response.ollama().voices().isEmpty());
        server.verify();
    }
}
