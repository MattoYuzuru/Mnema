package app.mnema.learning.admin.support;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.json.ContentJsonReader;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed credential, no redirects, no retry, bounded body/concurrency and a whole-request deadline. */
@Component
public final class AdminSupportClient {
    private static final int RESPONSE_LIMIT = 1_048_576;
    private static final ContentJsonReader JSON = new ContentJsonReader(RESPONSE_LIMIT, 8, 20_000);
    private final AdminSupportSettings settings;
    private final HttpClient client;
    private final Semaphore permits;

    public AdminSupportClient(AdminSupportSettings settings) {
        this.settings = settings;
        permits = new Semaphore(settings.concurrency());
        client = HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    record Result(int status, JsonNode body, boolean replayed) { }

    Result get(String path) { return exchange(path, null); }
    Result command(String path, JsonNode body) { return exchange(path, body); }

    private Result exchange(String path, JsonNode command) {
        if (!settings.enabled() || !permits.tryAcquire()) throw new SupportUnavailableException();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            if (!path.startsWith("/tickets") || path.contains("#") || path.contains("\\")) throw new SupportUnavailableException();
            var request = HttpRequest.newBuilder(URI.create(settings.endpoint().toString() + path)).timeout(settings.timeout())
                    .header("Accept", "application/json").header("Authorization", "Bearer " + settings.secret());
            if (command == null) request.GET();
            else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(command.toString()));
            pending = client.sendAsync(request.build(), ignored -> new LimitedBody());
            HttpResponse<byte[]> response = pending.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
            String mediaType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0];
            if (!mediaType.equals("application/json") && !mediaType.equals("application/problem+json")) throw new SupportUnavailableException();
            if (response.statusCode() == 400) throw new InvalidRequestException();
            if (response.statusCode() == 404) throw new ResourceNotFoundException();
            if (response.statusCode() == 409) throw new SupportConflictException();
            if (response.statusCode() != 200 && !(command != null && response.statusCode() == 202)) throw new SupportUnavailableException();
            JsonNode body;
            try {
                body = JSON.read(response.body());
                SupportResponses.validate(body, command != null, !path.equals("/tickets") && !path.startsWith("/tickets?"));
            } catch (IllegalArgumentException | InvalidRequestException | DateTimeException failure) { throw new SupportUnavailableException(); }
            if (command != null) verifyAcknowledgement(path, command, body, response.statusCode());
            return new Result(response.statusCode(), body, response.headers().firstValue("Idempotency-Replayed").orElse("").equals("true"));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new SupportUnavailableException();
        } catch (ExecutionException | TimeoutException | IllegalArgumentException failure) {
            throw new SupportUnavailableException();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            permits.release();
        }
    }

    @PreDestroy
    public void close() { client.shutdownNow(); }

    private static void verifyAcknowledgement(String path, JsonNode command, JsonNode body, int status) {
        String ticket = path.substring("/tickets/".length(), path.length() - "/commands".length());
        boolean reply = command.path("type").stringValue().equals("reply");
        boolean note = command.path("type").stringValue().equals("note");
        if (!command.path("commandId").equals(body.path("commandId")) || !ticket.equals(body.path("ticketId").stringValue())
                || body.path("version").longValue() <= command.path("expectedVersion").longValue()
                || (reply && (status != 202 || !"queued".equals(body.path("delivery").stringValue(null))
                    || body.path("outboxId").isNull() || body.path("messageId").isNull()))
                || (!reply && (status != 200 || !body.path("delivery").isNull() || !body.path("outboxId").isNull()))
                || (note && body.path("messageId").isNull()) || (!reply && !note && !body.path("messageId").isNull())) {
            throw new SupportUnavailableException();
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription incoming) { subscription = incoming; incoming.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > RESPONSE_LIMIT - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Support response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new IOException("Support response unavailable")); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
