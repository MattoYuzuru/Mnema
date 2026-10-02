package app.mnema.learning.ai;

/** Image generation port (later tiers). Interface only. */
public interface ImageGeneration {
    AiResult<Image> generate(Request request);

    record Request(String prompt, String size, String style) { }

    record Image(byte[] bytes, String mimeType) { }
}
