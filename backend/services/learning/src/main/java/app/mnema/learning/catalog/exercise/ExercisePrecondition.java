package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;

import java.util.Enumeration;

final class ExercisePrecondition {
    private ExercisePrecondition() { }

    static long read(Enumeration<String> headers) {
        if (headers == null || !headers.hasMoreElements()) throw new VersionPreconditionRequiredException();
        String token = headers.nextElement();
        if (headers.hasMoreElements() || token == null || token.length() > 21
                || !token.matches("\"(0|[1-9][0-9]{0,18})\"")) throw new InvalidRequestException();
        try {
            long parsed = Long.parseLong(token.substring(1, token.length() - 1));
            if (parsed == Long.MAX_VALUE) throw new InvalidRequestException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException();
        }
    }
}
