package app.mnema.learning.platform.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class LoggablePathTest {
    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath("/api");
        return request;
    }

    @Test
    void aMappedPublicRouteIsLoggedAsItsTemplate() {
        MockHttpServletRequest request = request("/api/public/decks/Kq7xT3mNpR/items/7f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/public/decks/{code}/items/{memberKey}");
        assertThat(ApiExceptionHandler.loggablePath(request)).isEqualTo("/api/public/decks/{code}/items/{memberKey}");
    }

    @Test
    void anUnmappedPublicRouteHasItsCodeSegmentRedacted() {
        assertThat(ApiExceptionHandler.loggablePath(request("/api/public/decks/Kq7xT3mNpR/items"))).isEqualTo("/api/public/decks/{code}/items");
        assertThat(ApiExceptionHandler.loggablePath(request("/api/public/decks/Kq7xT3mNpR"))).isEqualTo("/api/public/decks/{code}");
        assertThat(ApiExceptionHandler.loggablePath(request("/api/public/decks/Kq7xT3mNpR/")))
                .doesNotContain("Kq7xT3mNpR");
    }

    @Test
    void otherPathsAreLoggedAsTheyAre() {
        MockHttpServletRequest request = request("/api/decks/11111111-1111-4111-8111-111111111111/items");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/decks/{deckId}/items");
        assertThat(ApiExceptionHandler.loggablePath(request)).isEqualTo("/api/decks/11111111-1111-4111-8111-111111111111/items");
    }
}
