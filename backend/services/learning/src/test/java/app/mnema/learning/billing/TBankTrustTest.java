package app.mnema.learning.billing;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bank's own trust: the bundled roots match their published fingerprints, the context is private to the client and the JVM is untouched. */
class TBankTrustTest {
    private static String pem(String resource) throws Exception {
        String module = "src/main/resources/" + resource;
        String repository = "backend/services/learning/" + module;
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(repository)) && !Files.exists(root.resolve(module))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot find the resource");
        Path file = Files.exists(root.resolve(module)) ? root.resolve(module) : root.resolve(repository);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static TBankTrust.Root russian(String fingerprint) throws Exception {
        return new TBankTrust.Root("russian", pem(TBankTrust.RUSSIAN_ROOT), fingerprint);
    }

    private static TBankTrust.Root trustAsia(String fingerprint) throws Exception {
        return new TBankTrust.Root("trustasia", pem(TBankTrust.TRUST_ASIA_ROOT), fingerprint);
    }

    @Test
    void theBundledRootsMatchTheirFingerprintsAndBuildAPrivateContext() throws Exception {
        TBankTrust trust = new TBankTrust();
        SSLContext context = trust.sslContext();

        assertThat(context.getProtocol()).isEqualTo("TLS");
        assertThat(context).isNotSameAs(SSLContext.getDefault());
        assertThat(System.getProperty("javax.net.ssl.trustStore")).isNull();
        assertThat(TBankTrust.RUSSIAN_ROOT_FINGERPRINT)
                .isEqualTo("D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31");
        assertThat(TBankTrust.TRUST_ASIA_ROOT_FINGERPRINT)
                .isEqualTo("06:C0:8D:7D:AF:D8:76:97:1E:B1:12:4F:E6:7F:84:7E:C0:C7:A1:58:D3:EA:53:CB:E9:40:E2:EA:97:91:F4:C3");
        assertThat(new TBankTrust(List.of(russian(TBankTrust.RUSSIAN_ROOT_FINGERPRINT))).sslContext()).isNotNull();
        assertThat(new TBankTrust(List.of(trustAsia(TBankTrust.TRUST_ASIA_ROOT_FINGERPRINT))).sslContext()).isNotNull();
    }

    @Test
    void theClientTrustsBothRootsTheBankNamesAndNothingElse() {
        X509Certificate[] anchors = new TBankTrust().trustManager().getAcceptedIssuers();

        assertThat(Arrays.stream(anchors).map(anchor -> anchor.getSubjectX500Principal().getName()))
                .containsExactlyInAnyOrder("CN=Russian Trusted Root CA,O=The Ministry of Digital Development and Communications,C=RU",
                        "CN=TrustAsia TLS RSA Root CA,O=TrustAsia Technologies\\, Inc.,C=CN");
    }

    @Test
    void aCertificateThatDoesNotMatchItsFingerprintStopsTheStart() throws Exception {
        String otherRussian = TBankTrust.RUSSIAN_ROOT_FINGERPRINT.replace("D2:6D", "D2:6E");
        String otherTrustAsia = TBankTrust.TRUST_ASIA_ROOT_FINGERPRINT.replace("06:C0", "06:C1");

        assertThatThrownBy(() -> new TBankTrust(List.of(russian(otherRussian)))).isInstanceOf(IllegalStateException.class).hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> new TBankTrust(List.of(russian(TBankTrust.RUSSIAN_ROOT_FINGERPRINT), trustAsia(otherTrustAsia))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trustasia").hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> new TBankTrust(List.of(trustAsia(TBankTrust.RUSSIAN_ROOT_FINGERPRINT)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void textWithoutACertificateOrWithAGarbledOneIsRejected() {
        assertThatThrownBy(() -> new TBankTrust(List.of(new TBankTrust.Root("x", "# only a comment", TBankTrust.RUSSIAN_ROOT_FINGERPRINT))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TBankTrust(List.of(new TBankTrust.Root("x", "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
                TBankTrust.RUSSIAN_ROOT_FINGERPRINT)))).isInstanceOf(IllegalStateException.class);
    }
}
