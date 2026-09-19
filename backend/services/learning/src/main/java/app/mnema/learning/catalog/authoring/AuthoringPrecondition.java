package app.mnema.learning.catalog.authoring;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;

import java.util.Enumeration;

final class AuthoringPrecondition {
    private AuthoringPrecondition() { }

    static long read(Enumeration<String> headers) {
        if (headers == null || !headers.hasMoreElements()) throw new VersionPreconditionRequiredException();
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.length() > 21
                || !value.matches("\"(0|[1-9][0-9]{0,18})\"")) throw new InvalidRequestException();
        try {
            long result = Long.parseLong(value.substring(1, value.length() - 1));
            if (result == Long.MAX_VALUE) throw new InvalidRequestException();
            return result;
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException();
        }
    }
}
