package app.mnema.learning.billing;

import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.HexFormat;
import java.util.List;

/**
 * The trust of the T-Bank client: the bank presents a chain to the Russian Trusted Root CA (Минцифры), which the JVM does not ship, and during the bank's
 * transition («Установка TLS-сертификатов», developer.tbank.ru/eacq/intro/certificates) also chains to the TrustAsia TLS RSA Root CA; the bank asks merchants
 * to trust both. The bundled PEMs ({@code billing/russian-trusted-root-ca.pem}, {@code billing/trustasia-tls-rsa-root-ca.pem}) are checked against their
 * published SHA-256 fingerprints at start (a mismatch stops the start), put alone into an in-memory PKCS12 store and turned into an {@link SSLContext} that
 * only the T-Bank {@code HttpClient} uses. No {@code javax.net.ssl.*} property is set and the JVM default trust store is untouched, so no other connection of
 * the service trusts these roots, and the T-Bank client trusts no other root.
 *
 * <p><b>Accepted risk.</b> The bank's {@code GetState} (and {@code Init}) answers are not signed, so the TLS chain to these pinned roots is the only
 * authenticity control of what the bank says; there is no leaf or intermediate pin. Whoever can obtain a certificate that chains to either root for the
 * bank's host could forge an answer. The amount, order id and terminal key checks of {@link PaymentStateApplier} limit what a forged answer can do, not
 * whether it is believed. The TrustAsia root is dropped once the bank's transition ends.
 */
@Component
final class TBankTrust {
    /** One trusted root: the store alias, the PEM text (any text before the BEGIN line is a comment) and the published SHA-256 of its DER encoding. */
    record Root(String alias, String pem, String fingerprint) { }

    /** SHA-256 of the Russian Trusted Root CA, as published in {@code contracts/billing/README.md}. */
    static final String RUSSIAN_ROOT_FINGERPRINT = "D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31";
    /** SHA-256 of the TrustAsia TLS RSA Root CA, as published by TrustAsia's repository and in {@code contracts/billing/README.md}. */
    static final String TRUST_ASIA_ROOT_FINGERPRINT = "06:C0:8D:7D:AF:D8:76:97:1E:B1:12:4F:E6:7F:84:7E:C0:C7:A1:58:D3:EA:53:CB:E9:40:E2:EA:97:91:F4:C3";
    static final String RUSSIAN_ROOT = "billing/russian-trusted-root-ca.pem";
    static final String TRUST_ASIA_ROOT = "billing/trustasia-tls-rsa-root-ca.pem";
    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";

    private final SSLContext context;
    private final X509TrustManager trustManager;

    TBankTrust() {
        this(List.of(new Root("russian-trusted-root", read(RUSSIAN_ROOT), RUSSIAN_ROOT_FINGERPRINT),
                new Root("trustasia-tls-rsa-root", read(TRUST_ASIA_ROOT), TRUST_ASIA_ROOT_FINGERPRINT)));
    }

    TBankTrust(List<Root> roots) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            for (Root root : roots) store.setCertificateEntry(root.alias(), certificate(root));
            TrustManagerFactory managers = TrustManagerFactory.getInstance("PKIX");
            managers.init(store);
            this.trustManager = (X509TrustManager) managers.getTrustManagers()[0];
            this.context = SSLContext.getInstance("TLS");
            this.context.init(null, managers.getTrustManagers(), null);
        } catch (GeneralSecurityException | IOException failure) {
            throw new IllegalStateException("The T-Bank trusted roots cannot be loaded", failure);
        }
    }

    SSLContext sslContext() {
        return context;
    }

    /** The trust manager behind {@link #sslContext()}, for tests of its anchors. */
    X509TrustManager trustManager() {
        return trustManager;
    }

    private static Certificate certificate(Root root) throws GeneralSecurityException {
        int start = root.pem().indexOf(BEGIN);
        if (start < 0) throw new IllegalStateException("The trusted root certificate " + root.alias() + " is missing");
        Certificate certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(root.pem().substring(start).getBytes(StandardCharsets.US_ASCII)));
        String fingerprint = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        if (!fingerprint.equalsIgnoreCase(root.fingerprint().replace(":", ""))) {
            throw new IllegalStateException("The bundled trusted root certificate " + root.alias() + " does not match its published fingerprint");
        }
        return certificate;
    }

    private static String read(String resource) {
        try (InputStream stream = TBankTrust.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("The trusted root certificate " + resource + " is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("The trusted root certificate " + resource + " cannot be read", failure);
        }
    }
}
