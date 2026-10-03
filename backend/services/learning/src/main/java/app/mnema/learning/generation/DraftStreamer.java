package app.mnema.learning.generation;

import app.mnema.learning.ai.StreamListener;
import app.mnema.learning.generation.mbm.MbmAutoFixer;
import app.mnema.learning.generation.mbm.MbmCompiler;
import app.mnema.learning.generation.mbm.MbmOptions;
import app.mnema.learning.generation.mbm.MbmResult;
import app.mnema.learning.generation.mbm.RandomIdAllocator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Turns the streamed text of a draft into {@code BLOCKS_APPENDED} checkpoints. Text is cut at the last block boundary (a
 * blank line outside a code fence), compiled to native-v1 blocks, and the blocks that were not sent yet become one event,
 * at most once per {@code interval} and at most {@code maxBytes} per event. The node ids of a preview are provisional: the
 * stored revision gets its own. A prefix that does not compile (a table cut in the middle) is skipped and retried at the
 * next boundary; a preview never fails the draft.
 *
 * <p>The listener runs on the thread of the provider call, which holds no database transaction; a checkpoint is one short
 * transaction of its own. A cancelled or void claim aborts the call by throwing from {@link #onDelta}.
 */
final class DraftStreamer implements StreamListener {
    /** Aborts the provider call from inside the listener: the claim was cancelled or its lease is gone. */
    static final class Aborted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Aborted() {
            super("Draft aborted", null, false, false);
        }
    }

    /** Where checkpoints go; returns false when the claim is void. */
    @FunctionalInterface
    interface Sink {
        boolean checkpoint(int generation, int startIndex, ArrayNode blocks);
    }

    private static final MbmCompiler COMPILER = new MbmCompiler();

    private final MbmOptions options;
    private final Sink sink;
    private final java.util.function.BooleanSupplier stop;
    private final long intervalNanos;
    private final int maxBytes;
    private final StringBuilder text = new StringBuilder();
    private int generation;
    private int sent;
    private long lastCheckpoint;
    private boolean previewing = true;
    private boolean aborted;

    DraftStreamer(MbmOptions options, Sink sink, java.util.function.BooleanSupplier stop, Duration interval, int maxBytes,
                  int firstGeneration) {
        this.options = options;
        this.sink = sink;
        this.stop = stop;
        this.intervalNanos = interval.toNanos();
        this.maxBytes = maxBytes;
        this.generation = firstGeneration;
    }

    int generation() { return generation; }

    boolean aborted() { return aborted; }

    @Override
    public void onDelta(String delta) {
        if (stop.getAsBoolean()) {
            aborted = true;
            throw new Aborted();
        }
        text.append(delta);
        if (previewing && delta.indexOf('\n') >= 0) checkpoint();
    }

    @Override
    public void onRestart() {
        restart();
    }

    /** A new draft begins (a repair, the strong route, a provider retry): earlier blocks are void for the client. */
    void restart() {
        text.setLength(0);
        sent = 0;
        previewing = true;
        generation++;
    }

    private void checkpoint() {
        long now = System.nanoTime();
        if (lastCheckpoint != 0 && now - lastCheckpoint < intervalNanos) return;
        int cut = lastBoundary(text);
        if (cut <= 0) return;
        MbmResult result = COMPILER.compile(MbmAutoFixer.fix(text.substring(0, cut)).text(), options, new RandomIdAllocator());
        if (!(result instanceof MbmResult.Success success)) return;
        JsonNode content = success.document().path("root").path("content");
        if (content.size() <= sent) return;
        ArrayNode blocks = Json.array();
        int bytes = 0;
        for (int index = sent; index < content.size(); index++) {
            int size = content.get(index).toString().getBytes(StandardCharsets.UTF_8).length;
            if (size > maxBytes) {
                // A block that cannot be an event is never previewed; the stored revision carries it.
                previewing = false;
                break;
            }
            if (bytes + size > maxBytes) break;
            bytes += size;
            blocks.add(content.get(index).deepCopy());
        }
        if (blocks.isEmpty()) return;
        boolean accepted;
        try {
            accepted = sink.checkpoint(generation, sent, blocks);
        } catch (RuntimeException unavailable) {
            // A preview is a courtesy: a checkpoint that cannot be written (database trouble, a row over its bound) stops the
            // previews of this draft and never fails it; the stored revision is the truth.
            previewing = false;
            return;
        }
        if (!accepted) {
            aborted = true;
            throw new Aborted();
        }
        sent += blocks.size();
        lastCheckpoint = now;
    }

    /**
     * The offset after the last blank line that is outside a fenced block (code, mermaid source), or 0 when no block is
     * complete yet. Whatever follows is a block still being written.
     */
    static int lastBoundary(CharSequence text) {
        int boundary = 0;
        int lineStart = 0;
        int fence = 0;
        boolean previousBlank = false;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '\n') continue;
            String line = text.subSequence(lineStart, index).toString().strip();
            int fenceLength = fenceLength(line);
            if (fence == 0 && fenceLength > 0) {
                fence = fenceLength;
            } else if (fence > 0 && fenceLength >= fence && line.chars().allMatch(c -> c == '`')) {
                fence = 0;
            }
            boolean blank = line.isEmpty();
            if (blank && !previousBlank && fence == 0 && lineStart > 0) boundary = index + 1;
            previousBlank = blank;
            lineStart = index + 1;
        }
        return boundary;
    }

    private static int fenceLength(String line) {
        int length = 0;
        while (length < line.length() && line.charAt(length) == '`') length++;
        return length >= 3 ? length : 0;
    }
}
