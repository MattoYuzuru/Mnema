package app.mnema.learning.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/**
 * Yandex SpeechKit v1 REST (https://yandex.cloud/ru/docs/speechkit/tts/request; the page now redirects to aistudio.yandex.ru, the request shape below is
 * the lead's 2026-10-04 research and was not re-read here): {@code POST {base}/speech/v1/tts:synthesize}, {@code Authorization: Api-Key <key>}, a form of
 * {@code text}, {@code lang=ru-RU}, {@code voice}, {@code format=mp3} and {@code folderId}; the body is the MP3. Only Russian is served (anything else is
 * not applicable, so routing skips this provider), and the price is per character in roubles ({@link SpeechSettings#yandexRubPerMillionChars()}), converted
 * to micro-US-dollars with {@code learning.generation.usd-rub-rate}.
 */
final class YandexSpeechSynthesis implements SpeechAdapter {
    static final String PROVIDER = "yandex";
    private static final int[] MPEG1_L3_KBPS = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};

    private final ChatHttp http;
    private final URI endpoint;
    private final String key;
    private final boolean enabled;
    private final SpeechSettings settings;
    private final BigDecimal usdRubRate;
    private final Clock clock;

    YandexSpeechSynthesis(AiProperties.Provider provider, ChatHttp http, SpeechSettings settings, BigDecimal usdRubRate, Clock clock) {
        this.http = http;
        String base = provider.baseUrl().isEmpty() ? "https://tts.api.cloud.yandex.net" : provider.baseUrl();
        this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/speech/v1/tts:synthesize");
        this.key = provider.apiKey();
        this.enabled = provider.enabled();
        this.settings = settings;
        this.usdRubRate = usdRubRate;
        this.clock = clock;
    }

    @Override public String provider() { return PROVIDER; }

    @Override
    public boolean configured() { return enabled && !key.isEmpty() && !settings.yandexFolderId().isEmpty() && http != null; }

    @Override public AiProperties.EgressMode egress() { return http == null ? AiProperties.EgressMode.DIRECT : http.egress(); }

    @Override
    public boolean supports(String lang) {
        String tag = lang.toLowerCase(java.util.Locale.ROOT);
        return tag.equals("ru") || tag.startsWith("ru-");
    }

    @Override public String voiceName(String voice) { return "female".equals(voice) ? settings.yandexFemale() : settings.yandexMale(); }

    @Override public String format() { return "mp3"; }

    @Override
    public AiResult<SpeechSynthesis.Audio> synthesize(String model, String modelVersion, SpeechSynthesis.Request request, Duration budget) {
        if (!supports(request.lang())) return AiResult.failed(new AiFailure.Refusal("lang_not_supported"));
        String form = "text=" + enc(request.text()) + "&lang=ru-RU&voice=" + enc(voiceName(request.voice())) + "&format=mp3&folderId="
                + enc(settings.yandexFolderId());
        HttpRequest.Builder post = HttpRequest.newBuilder(endpoint).header("Authorization", "Api-Key " + key)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        ChatHttp.Reply reply;
        try {
            reply = http.send(post, budget, null);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(switch (exception.kind()) {
                case TIMEOUT -> new AiFailure.Timeout();
                case TOO_LARGE -> new AiFailure.InvalidOutput("body_too_large");
                case IO -> new AiFailure.Transient("io_error");
            });
        }
        if (reply.status() / 100 != 2) return AiResult.failed(ImageSource.statusFailure(reply.status(), reply.retryAfter(), clock));
        byte[] audio = reply.body();
        long duration = mp3DurationMs(audio);
        if (duration < 0) return AiResult.failed(new AiFailure.InvalidOutput("not_mp3"));
        return AiResult.ok(new SpeechSynthesis.Audio(audio, "audio/mpeg", request.text().length(), duration,
                new SpeechSynthesis.Identity(PROVIDER, model, modelVersion, format(), voiceName(request.voice())), 0L));
    }

    @Override
    public long costMicros(SpeechSynthesis.Audio audio) {
        // roubles per million characters is micro-roubles per character; divided by the rate it is micro-dollars
        return BigDecimal.valueOf(audio.billedCharacters()).multiply(settings.yandexRubPerMillionChars()).divide(usdRubRate, 0, RoundingMode.CEILING)
                .longValue();
    }

    /**
     * The duration of an MP3 estimated from its first frame header (the constant bitrate SpeechKit answers with), or -1 when the bytes do not start with an
     * MPEG audio frame (after an optional ID3v2 tag).
     */
    static long mp3DurationMs(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 10 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            offset = 10 + ((bytes[6] & 0x7f) << 21 | (bytes[7] & 0x7f) << 14 | (bytes[8] & 0x7f) << 7 | (bytes[9] & 0x7f));
        }
        if (offset + 4 > bytes.length || (bytes[offset] & 0xff) != 0xff || (bytes[offset + 1] & 0xe0) != 0xe0) return -1;
        boolean mpeg1Layer3 = (bytes[offset + 1] & 0x1e) == 0x1a;
        int kbps = mpeg1Layer3 ? MPEG1_L3_KBPS[(bytes[offset + 2] & 0xf0) >> 4] : 0;
        if (kbps == 0) kbps = 128;
        return Math.max(1, (bytes.length - offset) * 8L / kbps);
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
