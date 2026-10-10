package app.mnema.identityaccount.contract;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserOriginsTest {
    @Test
    void selectsOnlyExplicitClientsAndExactOrigins() {
        var origins = new BrowserOrigins("https://mnema.app", "https://admin.mnema.app");
        assertThat(origins.all()).containsExactly("https://mnema.app", "https://admin.mnema.app");
        assertThat(origins.forClient(null)).isEqualTo("https://mnema.app");
        assertThat(origins.forClient("mnema-admin-web")).isEqualTo("https://admin.mnema.app");
        assertThatThrownBy(() -> origins.forClient("https://evil.test")).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> new BrowserOrigins("https://mnema.app", "").forClient("mnema-admin-web"))
                .isInstanceOf(AccountFailure.class);
    }

    @Test
    void rejectsPathsCredentialsFragmentsAndDuplicateOrigins() {
        for (String value : new String[]{"http://admin.mnema.app", "https://admin.mnema.app/", "https://admin.mnema.app/path",
                "https://user@admin.mnema.app", "https://admin.mnema.app?q=1", "https://admin.mnema.app#fragment"}) {
            assertThatThrownBy(() -> new BrowserOrigins("https://mnema.app", value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new BrowserOrigins("https://mnema.app", "https://mnema.app"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ignoresRequestOriginAndUntrustedReturnTargets() {
        var origins = new BrowserOrigins("https://mnema.app", "https://admin.mnema.app");
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://evil.test");
        request.setParameter("mnema_client_id", "mnema-admin-web");
        assertThat(origins.loginOrigin(request)).isEqualTo("https://mnema.app");
        request.setAttribute("identity.login-origin", "https://evil.test");
        assertThat(origins.loginOrigin(request)).isEqualTo("https://mnema.app");
        request.setAttribute("identity.login-origin", "https://admin.mnema.app");
        assertThat(origins.loginOrigin(request)).isEqualTo("https://admin.mnema.app");
    }
}
