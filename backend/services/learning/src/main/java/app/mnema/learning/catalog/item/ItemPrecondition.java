package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;

import java.util.Enumeration;

final class ItemPrecondition {
    private ItemPrecondition() { }

    static long read(Enumeration<String> headers) {
        if (headers == null || !headers.hasMoreElements()) throw new VersionPreconditionRequiredException();
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.length() > 21
                || !value.matches("\"(0|[1-9][0-9]{0,18})\"")) throw new InvalidRequestException();
        try {
            long version = Long.parseLong(value.substring(1, value.length() - 1));
            if (version == Long.MAX_VALUE) throw new InvalidRequestException();
            return version;
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException();
        }
    }
}
