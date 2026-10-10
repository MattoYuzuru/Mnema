package app.mnema.identityaccount.avatar;

import app.mnema.identityaccount.security.BrowserSessions;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AvatarController {
    private final Avatars avatars;

    public AvatarController(Avatars avatars) {
        this.avatars = avatars;
    }

    @PutMapping(value = "/me/avatar", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void upload(Authentication authentication, @RequestPart("file") MultipartFile file) throws IOException {
        try (var input = file.getInputStream()) {
            avatars.replace(BrowserSessions.access(authentication), AvatarImage.read(input, file.getContentType()));
        }
    }

    @DeleteMapping("/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(Authentication authentication) {
        avatars.remove(BrowserSessions.access(authentication));
    }

    @GetMapping("/me/avatar")
    ResponseEntity<byte[]> own(Authentication authentication) {
        var content = avatars.readOwn(BrowserSessions.access(authentication));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.type()))
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(content.bytes());
    }

    @GetMapping("/profiles/{id}/avatar")
    ResponseEntity<byte[]> read(@PathVariable UUID id,
                                @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        var avatar = avatars.requirePublic(id);
        String etag = Avatars.etag(avatar);
        // A withdrawn photo must disappear within a minute, so the public cache stays short.
        var response = ResponseEntity.status(matches(ifNoneMatch, etag) ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "public, max-age=60")
                .eTag(etag);
        // Revalidation answers from the database row alone and never touches object storage.
        if (matches(ifNoneMatch, etag)) return response.build();
        var content = avatars.load(avatar);
        return response.contentType(MediaType.parseMediaType(content.type())).body(content.bytes());
    }

    /** RFC 9110 weak comparison for If-None-Match: a list of entity tags or {@code *}. */
    static boolean matches(String header, String etag) {
        if (header == null) return false;
        for (String candidate : header.split(",")) {
            String value = candidate.strip();
            if (value.startsWith("W/")) value = value.substring(2);
            if (value.equals("*") || value.equals(etag)) return true;
        }
        return false;
    }
}
