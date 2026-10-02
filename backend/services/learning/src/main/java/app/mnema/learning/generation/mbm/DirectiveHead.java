package app.mnema.learning.generation.mbm;

import java.util.ArrayList;
import java.util.List;

/**
 * The first line of a directive: {@code ::name{key="value" ...} text}. Values are double-quoted and escape only
 * {@code \"} and {@code \\}; any other backslash is literal. A malformed attribute list is reported as one
 * {@code syntaxError} and nothing else is trusted from the line.
 */
record DirectiveHead(String name, List<Attribute> attributes, String text, boolean syntaxError) {

    record Attribute(String key, String value) {
    }

    /** Parses a line that starts with {@code ::}. */
    static DirectiveHead parse(String line) {
        int length = line.length();
        int p = 2;
        while (p < length && line.charAt(p) != '{' && !Character.isWhitespace(line.charAt(p))) {
            p++;
        }
        String name = line.substring(2, p);
        if (p >= length || line.charAt(p) != '{') {
            return new DirectiveHead(name, List.of(), line.substring(p).strip(), false);
        }
        p++;
        var attributes = new ArrayList<Attribute>();
        while (true) {
            while (p < length && isBlank(line.charAt(p))) {
                p++;
            }
            if (p >= length) {
                return syntax(name);
            }
            if (line.charAt(p) == '}') {
                p++;
                break;
            }
            int keyStart = p;
            if (!isLower(line.charAt(p))) {
                return syntax(name);
            }
            while (p < length && (isLower(line.charAt(p)) || isDigit(line.charAt(p)) || line.charAt(p) == '_')) {
                p++;
            }
            String key = line.substring(keyStart, p);
            if (p + 1 >= length || line.charAt(p) != '=' || line.charAt(p + 1) != '"') {
                return syntax(name);
            }
            p += 2;
            var value = new StringBuilder();
            boolean closed = false;
            while (p < length) {
                char c = line.charAt(p);
                if (c == '"') {
                    p++;
                    closed = true;
                    break;
                }
                if (c == '\\' && p + 1 < length && (line.charAt(p + 1) == '"' || line.charAt(p + 1) == '\\')) {
                    value.append(line.charAt(p + 1));
                    p += 2;
                } else {
                    value.append(c);
                    p++;
                }
            }
            if (!closed || p >= length || !(isBlank(line.charAt(p)) || line.charAt(p) == '}')) {
                return syntax(name);
            }
            attributes.add(new Attribute(key, value.toString()));
        }
        if (p < length && !Character.isWhitespace(line.charAt(p))) {
            return syntax(name);
        }
        return new DirectiveHead(name, attributes, line.substring(p).strip(), false);
    }

    private static DirectiveHead syntax(String name) {
        return new DirectiveHead(name, List.of(), "", true);
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    private static boolean isLower(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
