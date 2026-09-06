package app.mnema.learning.platform.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded Identity transport; deadlines include the entire body, not only response headers. */
final class IdentityHttp implements AutoCloseable {
    private final HttpClient client;
    private final Duration timeout;
    private final Semaphore permits;

    IdentityHttp(Duration timeout, int concurrency) {
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(10)) > 0
                || concurrency < 1 || concurrency > 256) {
            throw new IllegalArgumentException("Invalid Identity transport limits");
        }
        this.timeout = timeout;
        this.permits = new Semaphore(concurrency);
        this.client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpResponse<byte[]> get(URI uri, String bearer, int limit) throws IOException {
        if (!permits.tryAcquire()) throw new IOException("Identity capacity unavailable");
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/json").GET();
            if (bearer != null) request.header("Authorization", "Bearer " + bearer);
            pending = client.sendAsync(request.build(), info -> new LimitedBody(limit));
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Identity request interrupted");
        } catch (ExecutionException | TimeoutException exception) {
            throw new IOException("Identity request unavailable");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            permits.release();
        }
    }

    @Override
    public void close() {
        client.shutdownNow();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            subscription = incoming;
            incoming.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                int count = buffer.remaining();
                if (count > limit - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Identity response exceeds limit"));
                    return;
                }
                byte[] chunk = new byte[count];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(new IOException("Identity response unavailable"));
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
