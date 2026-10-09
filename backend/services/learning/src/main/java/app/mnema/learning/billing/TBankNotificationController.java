package app.mnema.learning.billing;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@code POST /api/billing/tbank/notifications}: the endpoint the bank calls, reachable without a session or a bearer (its own security chain, exactly this
 * path and method). The answer is {@code text/plain} {@code OK} or {@code ERROR}, whatever the caller's {@code Accept}: the bank reads the body, not the
 * negotiation. Everything about authenticity is in {@link TBankNotifications}.
 */
@RestController
@RequestMapping("/billing/tbank")
public final class TBankNotificationController {
    private final TBankNotifications notifications;

    TBankNotificationController(TBankNotifications notifications) {
        this.notifications = notifications;
    }

    @PostMapping("/notifications")
    ResponseEntity<String> receive(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(TBankNotifications.MAX_BYTES + 1);
        HttpStatus status = switch (notifications.handle(bytes)) {
            case OK -> HttpStatus.OK;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).header("Cache-Control", "no-store")
                .body(status == HttpStatus.OK ? "OK" : "ERROR");
    }
}
