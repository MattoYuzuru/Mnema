package app.mnema.learning.billing;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bank's own trust: the bundled root matches its published fingerprint, the context is private to the client and the JVM is untouched. */
class TBankTrustTest {
    private static String pem() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("backend/services/learning/src/main/resources/billing/russian-trusted-root-ca.pem"))
                && !Files.exists(root.resolve("src/main/resources/billing/russian-trusted-root-ca.pem"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find the resource");
        Path file = Files.exists(root.resolve("src/main/resources/billing/russian-trusted-root-ca.pem"))
                ? root.resolve("src/main/resources/billing/russian-trusted-root-ca.pem")
                : root.resolve("backend/services/learning/src/main/resources/billing/russian-trusted-root-ca.pem");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void theBundledRootMatchesItsFingerprintAndBuildsAPrivateContext() throws Exception {
        SSLContext context = new TBankTrust().sslContext();

        assertThat(context.getProtocol()).isEqualTo("TLS");
        assertThat(context).isNotSameAs(SSLContext.getDefault());
        assertThat(System.getProperty("javax.net.ssl.trustStore")).isNull();
        assertThat(new TBankTrust(pem(), TBankTrust.FINGERPRINT).sslContext()).isNotNull();
        assertThat(TBankTrust.FINGERPRINT).isEqualTo("D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31");
    }

    @Test
    void aCertificateThatDoesNotMatchTheFingerprintStopsTheStart() throws Exception {
        String other = TBankTrust.FINGERPRINT.replace("D2:6D", "D2:6E");

        assertThatThrownBy(() -> new TBankTrust(pem(), other)).isInstanceOf(IllegalStateException.class).hasMessageContaining("fingerprint");
    }

    @Test
    void textWithoutACertificateOrWithAGarbledOneIsRejected() {
        assertThatThrownBy(() -> new TBankTrust("# only a comment", TBankTrust.FINGERPRINT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TBankTrust("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n", TBankTrust.FINGERPRINT))
                .isInstanceOf(IllegalStateException.class);
    }
}
