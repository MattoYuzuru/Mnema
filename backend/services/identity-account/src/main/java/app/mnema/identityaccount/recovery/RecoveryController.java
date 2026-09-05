package app.mnema.identityaccount.recovery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.locks.LockSupport;

@RestController
@RequestMapping("/api/accounts/password-reset")
public class RecoveryController {
    public record Request(@NotBlank @Email @Size(max = 320) String email) {
    }

    public record Confirm(@NotBlank @Size(max = 128) String token, @NotBlank @Size(max = 128) String newPassword) {
    }

    private final PasswordRecovery recovery;

    public RecoveryController(PasswordRecovery recovery) {
        this.recovery = recovery;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void request(@Valid @RequestBody Request r, HttpServletRequest request) {
        long deadline = System.nanoTime() + 4_000_000_000L;
        try {
            recovery.request(r.email(), request.getRemoteAddr());
        } finally {
            awaitPublicDeadline(deadline);
        }
    }

    static void awaitPublicDeadline(long deadline) {
        // Same public completion envelope for eligible, missing, throttled and delivery-failed requests.
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) break;
        }
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@Valid @RequestBody Confirm r) {
        recovery.confirm(r.token(), r.newPassword());
    }
}
