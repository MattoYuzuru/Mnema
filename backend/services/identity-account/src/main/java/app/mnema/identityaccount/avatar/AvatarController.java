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

    @GetMapping("/profiles/{id}/avatar")
    ResponseEntity<byte[]> read(@PathVariable UUID id) {
        var content = avatars.read(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.type()))
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(content.bytes());
    }
}
