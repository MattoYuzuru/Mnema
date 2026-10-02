package app.mnema.learning.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Request bodies that fail at the JSON syntax or stream-constraint layer regardless of the command
 * shape. Every request parser must answer them with its own opaque invalid-request failure; a raw
 * parser exception would surface as HTTP 500 instead of 400.
 */
public final class MalformedJsonBodies {

    private MalformedJsonBodies() { }

    public record Body(String name, byte[] bytes) {
        @Override
        public String toString() { return name; }
    }

    public static List<Body> all() {
        List<Body> bodies = new ArrayList<>();
        bodies.add(text("duplicate key", "{\"commandId\":\"a\",\"commandId\":\"b\"}"));
        bodies.add(text("duplicate key through escape", "{\"x\":1,\"\\u0078\":2}"));
        bodies.add(text("nested duplicate key", "{\"a\":{\"b\":1,\"b\":2}}"));
        bodies.add(text("trailing object", "{} {}"));
        bodies.add(text("trailing garbage", "{\"a\":1} x"));
        bodies.add(text("trailing bracket", "{\"a\":1}]"));
        bodies.add(text("unterminated object", "{\"a\":"));
        bodies.add(text("unterminated string", "{\"a\":\"b"));
        bodies.add(text("not-a-number token", "{\"a\":NaN}"));
        bodies.add(text("comment", "{/*c*/\"a\":1}"));
        bodies.add(text("lone surrogate escape", "{\"a\":\"\\ud800\"}"));
        bodies.add(text("NUL escape", "{\"a\":\"\\u0000\"}"));
        bodies.add(text("exponent overflow", "{\"a\":1e999999999}"));
        bodies.add(text("deeper than any limit",
                "{\"a\":".repeat(300) + "{}" + "}".repeat(300)));
        bodies.add(new Body("invalid UTF-8",
                new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xc3, '"', '}'}));
        bodies.add(text("non-object root", "[]"));
        bodies.add(text("empty", ""));
        return List.copyOf(bodies);
    }

    private static Body text(String name, String value) {
        return new Body(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
