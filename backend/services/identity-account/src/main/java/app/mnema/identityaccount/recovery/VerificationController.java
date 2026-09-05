package app.mnema.identityaccount.recovery;

import app.mnema.identityaccount.security.ClientAddresses;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts/email-verification")
public class VerificationController {
    public record Confirm(@NotBlank @Size(max = 128) String token) {
    }

    private final PasswordRecovery recovery;
    private final ClientAddresses clientAddresses;

    public VerificationController(PasswordRecovery recovery, ClientAddresses clientAddresses) {
        this.recovery = recovery;
        this.clientAddresses = clientAddresses;
    }

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void request(@Valid @RequestBody RecoveryController.Request body, HttpServletRequest request) {
        long deadline = System.nanoTime() + 4_000_000_000L;
        try {
            recovery.requestVerification(body.email(), clientAddresses.resolve(request));
        } finally {
            RecoveryController.awaitPublicDeadline(deadline);
        }
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirm(@Valid @RequestBody Confirm body) {
        recovery.confirmVerification(body.token());
    }
}
