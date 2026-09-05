package app.mnema.identityaccount.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PostboxMail {
    private final String accessKey, secretKey;
    private final URI endpoint;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public PostboxMail(@Value("${identity.postbox.access-key:}") String accessKey,
                       @Value("${identity.postbox.secret-key:}") String secretKey,
                       @Value("${identity.postbox.endpoint}") URI endpoint,
                       @Value("${identity.postbox.allow-loopback-http:false}") boolean loopback, ObjectMapper json) {
        if (!endpoint.equals(URI.create("https://postbox.cloud.yandex.net/v2/email/outbound-emails")) &&
                !(loopback && endpoint.getScheme().equals("http") &&
                        Set.of("127.0.0.1", "localhost", "[::1]").contains(endpoint.getHost())))
            throw new IllegalArgumentException("Postbox endpoint must be the fixed HTTPS API");
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
        this.json = json;
    }

    public boolean configured() {
        return !accessKey.isBlank() && !secretKey.isBlank();
    }

    public boolean reset(String recipient, String link) {
        return send(recipient, "Reset your Mnema password", "Reset your password within 10 minutes: " + link);
    }

    public boolean verification(String recipient, String link) {
        return send(recipient, "Verify your Mnema email", "Verify your email within 10 minutes: " + link);
    }

    private boolean send(String recipient, String subject, String text) {
        if (!configured()) return false;
        java.util.concurrent.CompletableFuture<HttpResponse<Void>> delivery = null;
        try {
            byte[] payload = json.writeValueAsBytes(Map.of("FromEmailAddress", "noreply@mnema.app", "Destination",
                    Map.of("ToAddresses", List.of(recipient)), "Content", Map.of("Simple",
                            Map.of("Subject", Map.of("Data", subject, "Charset", "UTF-8"), "Body",
                                    Map.of("Text", Map.of("Data", text, "Charset", "UTF-8"))))));
            var request = SdkHttpRequest.builder().uri(endpoint).method(SdkHttpMethod.POST)
                    .putHeader("Content-Type", "application/json").build();
            var signed = AwsV4HttpSigner.create()
                    .sign(r -> r.identity(AwsCredentialsIdentity.create(accessKey, secretKey)).request(request)
                            .payload(ContentStreamProvider.fromByteArray(payload))
                            .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "ses")
                            .putProperty(AwsV4HttpSigner.REGION_NAME, "ru-central1"));
            var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            signed.request().headers().forEach((key, values) -> {
                if (!key.equalsIgnoreCase("host") && !key.equalsIgnoreCase("content-length"))
                    values.forEach(value -> builder.header(key, value));
            });
            delivery = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding());
            var response = delivery.get(3, java.util.concurrent.TimeUnit.SECONDS);
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (delivery != null && !delivery.isDone()) delivery.cancel(true);
        }
    }
}
