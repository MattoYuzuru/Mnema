import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Silent container readiness check; no body, URL or exception enters Docker logs. */
public final class MnemaReadiness {
    public static void main(String[] args) {
        try {
            if ((args.length != 1 && args.length != 2) || !args[0].matches("1808[125]")) System.exit(1);
            String path = args.length == 1 ? "/api/actuator/health/readiness" : args[1];
            if (!path.equals("/api/actuator/health/readiness") && !path.equals("/actuator/health/readiness")) System.exit(1);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + args[0]
                    + path)).timeout(Duration.ofSeconds(3)).GET().build();
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER).build()) {
                System.exit(client.send(request, HttpResponse.BodyHandlers.discarding())
                        .statusCode() == 200 ? 0 : 1);
            }
        } catch (Exception ignored) {
            System.exit(1);
        }
    }
}
