package app.mnema.learning.billing;

import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
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

/**
 * The trust of the T-Bank client: the bank presents a chain to the Russian Trusted Root CA (Минцифры), which the JVM does not ship. The bundled PEM
 * ({@code billing/russian-trusted-root-ca.pem}) is checked against its published SHA-256 fingerprint at start (a mismatch stops the start), put alone into an
 * in-memory PKCS12 store and turned into an {@link SSLContext} that only the T-Bank {@code HttpClient} uses. No {@code javax.net.ssl.*} property is set and the
 * JVM default trust store is untouched, so no other connection of the service trusts this root.
 *
 * <p><b>Accepted risk.</b> The bank's {@code GetState} (and {@code Init}) answers are not signed, so the TLS chain to this pinned root is the only
 * authenticity control of what the bank says; there is no leaf or intermediate pin. Whoever can obtain a certificate that chains to the Russian Trusted Root CA
 * for the bank's host could forge an answer. The amount, order id and terminal key checks of {@link PaymentStateApplier} limit what a forged answer can do,
 * not whether it is believed.
 */
@Component
final class TBankTrust {
    /** SHA-256 of the DER certificate, as published in {@code contracts/billing/README.md}. */
    static final String FINGERPRINT = "D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31";
    private static final String RESOURCE = "billing/russian-trusted-root-ca.pem";
    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";

    private final SSLContext context;

    TBankTrust() {
        this(read(), FINGERPRINT);
    }

    /** @param pem the certificate; any text before the BEGIN line is a comment and is dropped */
    TBankTrust(String pem, String expectedFingerprint) {
        this.context = build(pem, expectedFingerprint);
    }

    SSLContext sslContext() {
        return context;
    }

    private static SSLContext build(String pem, String expectedFingerprint) {
        int start = pem.indexOf(BEGIN);
        if (start < 0) throw new IllegalStateException("The Russian trusted root certificate is missing");
        try {
            Certificate certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.substring(start).getBytes(StandardCharsets.US_ASCII)));
            String fingerprint = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if (!fingerprint.equalsIgnoreCase(expectedFingerprint.replace(":", ""))) {
                throw new IllegalStateException("The bundled Russian trusted root certificate does not match its published fingerprint");
            }
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("russian-trusted-root", certificate);
            TrustManagerFactory managers = TrustManagerFactory.getInstance("PKIX");
            managers.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException failure) {
            throw new IllegalStateException("The Russian trusted root certificate cannot be loaded", failure);
        }
    }

    private static String read() {
        try (InputStream stream = TBankTrust.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("The Russian trusted root certificate is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("The Russian trusted root certificate cannot be read", failure);
        }
    }
}
