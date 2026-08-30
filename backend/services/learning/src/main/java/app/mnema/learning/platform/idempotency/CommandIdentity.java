package app.mnema.learning.platform.idempotency;

import app.mnema.learning.platform.id.UuidPolicy;

import java.util.UUID;
import java.util.regex.Pattern;

public record CommandIdentity(UUID commandId, UUID actorId, String scope, String type) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");
    private static final int MAX_NAME_LENGTH = 80;

    public CommandIdentity {
        commandId = UuidPolicy.requireCommandId(commandId);
        actorId = UuidPolicy.requireEntityId(actorId, "actorId");
        scope = requireName(scope, "scope");
        type = requireName(type, "type");
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_NAME_LENGTH || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase dotted or hyphenated name of at most " + MAX_NAME_LENGTH + " characters"
            );
        }
        return value;
    }
}
