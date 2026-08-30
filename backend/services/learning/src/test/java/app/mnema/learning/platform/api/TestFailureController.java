package app.mnema.learning.platform.api;

import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

@RestController
@Profile("api-handler-fixture")
class TestFailureController {

    @GetMapping("/_test/idempotency")
    void idempotency() {
        throw new IdempotencyConflictException();
    }

    @GetMapping("/_test/version")
    void version() {
        throw new VersionConflictException();
    }

    @GetMapping("/_test/precondition-required")
    void preconditionRequired() {
        throw new VersionPreconditionRequiredException();
    }

    @PostMapping("/_test/validation")
    void validation(@Valid @RequestBody TestRequest request) {
    }

    @GetMapping("/_test/unexpected")
    void unexpected() {
        throw new IllegalStateException("database-password");
    }

    record TestRequest(@NotBlank String name) {
    }
}
