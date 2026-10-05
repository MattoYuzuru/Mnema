package app.mnema.learning.speech;

import app.mnema.learning.ai.Transcription;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP surface of speech inputs ({@code contracts/speech}): the raw recording in, a transcript to edit out. Every response is
 * {@code Cache-Control: private, no-store}; the owner is the token subject and nothing else. The recording is read bounded, never more than 2 MiB and one
 * byte, before any transaction starts, and its type is checked against an allowlist before a byte is read.
 *
 * <p>{@code X-Stub-Transcript} (percent-encoded UTF-8) scripts the answer of the Stub for a harness; it is read only when the Stub is the transcription
 * port ({@link Transcription#scriptable()}), so in production the header is never looked at.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
class SpeechInputController {
    static final String STUB_HEADER = "X-Stub-Transcript";
    private static final Pattern AUDIO_TYPE = Pattern.compile("(audio/(?:mp4|mpeg|ogg|webm))(?:\\s*;\\s*codecs\\s*=\\s*\"?([a-z0-9.,\\s]{1,40})\"?)?");
    private static final Pattern DURATION = Pattern.compile("[1-9][0-9]{0,4}");
    private static final Pattern LANG = Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8}){0,3}");
    private static final int MAX_SCRIPT = 2_000;
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private final SpeechInputService inputs;
    private final SpeechConsents consents;
    private final Transcription transcription;

    SpeechInputController(SpeechInputService inputs, SpeechConsents consents, Transcription transcription) {
        this.inputs = inputs;
        this.consents = consents;
        this.transcription = transcription;
    }

    @PostMapping("/speech-inputs")
    ResponseEntity<JsonNode> submit(@AuthenticationPrincipal Jwt identity, HttpServletRequest request, InputStream body) {
        UUID owner = owner(identity);
        query(request, Set.of("purpose", "lang", "deckId"));
        Transcription.Purpose purpose = purpose(parameter(request, "purpose"));
        String lang = lang(parameter(request, "lang"));
        UUID deck = parameter(request, "deckId") == null ? null : entity(parameter(request, "deckId"));
        UUID key = key(request.getHeader("Idempotency-Key"));
        int durationMs = duration(request.getHeader("X-Audio-Duration-Ms"));
        String mime = mime(request.getContentType());
        // the declared length refuses an oversized recording before a byte is read; the bounded read refuses a chunked or lying one
        if (request.getContentLengthLong() > SpeechInputSettings.MAX_BYTES) throw new PayloadTooLargeException();
        byte[] audio = read(body);
        if (audio.length == 0) throw new InvalidRequestException();
        String script = transcription.scriptable() ? script(request.getHeader(STUB_HEADER)) : null;
        SpeechInputService.Accepted accepted = inputs.submit(new SpeechInputService.Submission(owner, key, purpose, lang, deck, durationMs, mime, audio, script));
        var response = ResponseEntity.status(HttpStatus.ACCEPTED).headers(privateHeaders());
        if (accepted.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(inputs.acceptedBody(accepted));
    }

    @GetMapping("/speech-inputs/{id}")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String id, HttpServletRequest request) {
        UUID owner = owner(identity);
        UUID input = entity(id);
        query(request, Set.of());
        return ResponseEntity.ok().headers(privateHeaders()).body(inputs.read(owner, input));
    }

    @DeleteMapping("/speech-inputs/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt identity, @PathVariable String id, HttpServletRequest request) {
        UUID owner = owner(identity);
        UUID input = entity(id);
        query(request, Set.of());
        inputs.delete(owner, input);
        return ResponseEntity.noContent().headers(privateHeaders()).build();
    }

    // ------------------------------------------------------------------ consent

    @GetMapping("/speech-consent")
    ResponseEntity<JsonNode> consent(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        UUID owner = owner(identity);
        query(request, Set.of());
        return ResponseEntity.ok().headers(privateHeaders()).body(consents.view(owner));
    }

    @PutMapping(value = "/speech-consent", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> accept(@AuthenticationPrincipal Jwt identity, HttpServletRequest request, InputStream body) {
        UUID owner = owner(identity);
        query(request, Set.of());
        JsonNode json;
        try {
            json = JSON.readTree(bounded(body, 1_024));
        } catch (tools.jackson.core.JacksonException malformed) {
            throw new InvalidRequestException();
        }
        if (!json.isObject() || json.size() != 2 || !json.path("version").isString() || !json.path("processing").isString()) throw new InvalidRequestException();
        Transcription.Region processing;
        try {
            processing = Transcription.Region.valueOf(json.path("processing").stringValue(""));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException();
        }
        return ResponseEntity.ok().headers(privateHeaders()).body(consents.accept(owner, json.path("version").stringValue(""), processing));
    }

    @DeleteMapping("/speech-consent")
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        UUID owner = owner(identity);
        query(request, Set.of());
        consents.withdraw(owner);
        return ResponseEntity.noContent().headers(privateHeaders()).build();
    }

    // ------------------------------------------------------------------ input checks

    /** The base type of an allowed {@code Content-Type}; parameters other than {@code codecs} (and codecs of an unlisted codec family) are not accepted. */
    static String mime(String contentType) {
        if (contentType == null) throw new InvalidRequestException();
        Matcher matcher = AUDIO_TYPE.matcher(contentType.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) throw new InvalidRequestException();
        return matcher.group(1);
    }

    static Transcription.Purpose purpose(String value) {
        if (value == null) throw new InvalidRequestException();
        try {
            return Transcription.Purpose.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new InvalidRequestException();
        }
    }

    static String lang(String value) {
        if (value == null) return null;
        if (!LANG.matcher(value).matches()) throw new InvalidRequestException();
        return value;
    }

    static int duration(String value) {
        if (value == null || !DURATION.matcher(value.strip()).matches()) throw new InvalidRequestException();
        int millis = Integer.parseInt(value.strip());
        if (millis > Transcription.MAX_SECONDS * 1000) throw new InvalidRequestException();
        return millis;
    }

    private static UUID key(String value) {
        if (value == null) throw new InvalidRequestException();
        try {
            UUID key = UuidPolicy.requireCommandId(UUID.fromString(value.strip()));
            if (!key.toString().equals(value.strip())) throw new InvalidRequestException();
            return key;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** The scripted answer of the Stub: percent-encoded UTF-8 ({@code +} stays a plus); an absent header is no script, an empty one a clip without speech. */
    static String script(String header) {
        if (header == null) return null;
        String decoded;
        try {
            decoded = URLDecoder.decode(header.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw new InvalidRequestException();
        }
        if (decoded.length() > MAX_SCRIPT) throw new InvalidRequestException();
        return decoded;
    }

    /** At most {@link SpeechInputSettings#MAX_BYTES} bytes; one more is {@link PayloadTooLargeException}, and nothing past that byte is read. */
    private static byte[] read(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(SpeechInputSettings.MAX_BYTES + 1);
            if (bytes.length > SpeechInputSettings.MAX_BYTES) throw new PayloadTooLargeException();
            return bytes;
        } catch (IOException failure) {
            throw new InvalidRequestException();
        }
    }

    private static byte[] bounded(InputStream input, int max) {
        try {
            byte[] bytes = input.readNBytes(max + 1);
            if (bytes.length > max) throw new InvalidRequestException();
            return bytes;
        } catch (IOException failure) {
            throw new InvalidRequestException();
        }
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        for (String name : Collections.list(request.getParameterNames())) {
            if (!allowed.contains(name) || request.getParameterValues(name).length != 1) throw new InvalidRequestException();
        }
    }

    private static String parameter(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value != null && value.isBlank()) throw new InvalidRequestException();
        return value;
    }

    private static UUID owner(Jwt identity) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(identity.getSubject()), "owner");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static UUID entity(String value) {
        try {
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), "id");
            if (!id.toString().equals(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }
}
