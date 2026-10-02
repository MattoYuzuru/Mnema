package app.mnema.learning.generation.mbm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Inline rules of MBM v1: one left-to-right algorithm applied to a span of text (the block, an emphasis inside, a link
 * label). At each position the first applicable rule wins: backslash, code span, ruby, link, emphasis, text.
 * Recursion is bounded by {@link #MAX_DEPTH} and every look-ahead is charged to the {@link Budget}.
 */
final class InlineParser {

    /** Nesting bound of emphasis and link labels; real text never nests this deep. */
    static final int MAX_DEPTH = 128;

    private final LinkAllowlist allowlist;
    private final Findings findings;
    private final Budget budget;

    InlineParser(LinkAllowlist allowlist, Findings findings, Budget budget) {
        this.allowlist = allowlist;
        this.findings = findings;
        this.budget = budget;
    }

    /** Parses one block's text; findings are attached to {@code line}, the first line of the block. */
    List<Inline> parse(String text, int line) {
        return new Run(text, line).span(0, text.length(), List.of(), 0);
    }

    private final class Run {
        private final String s;
        private final int line;
        private boolean nestedLinkReported;
        private boolean insideLabel;

        Run(String s, int line) {
            this.s = s;
            this.line = line;
        }

        List<Inline> span(int from, int to, List<String> marks, int depth) {
            if (depth > MAX_DEPTH) {
                throw new LimitExceededException();
            }
            var out = new ArrayList<Inline>();
            var text = new StringBuilder();
            int p = from;
            boolean afterBang = false;
            while (p < to) {
                budget.spend(1);
                char c = s.charAt(p);
                boolean image = afterBang;
                afterBang = false;
                if (c == '\\') {
                    if (p + 1 < to && isAsciiPunctuation(s.charAt(p + 1))) {
                        text.append(s.charAt(p + 1));
                        p += 2;
                    } else {
                        text.append(c);
                        p++;
                    }
                } else if (c == '`') {
                    p = codeSpan(p, to, marks, out, text);
                } else if (c == '{') {
                    p = ruby(p, to, out, text, marks);
                } else if (c == '[') {
                    p = link(p, to, marks, depth, out, text, image);
                } else if (c == '*') {
                    p = emphasis(p, from, to, marks, depth, out, text);
                } else {
                    text.append(c);
                    afterBang = c == '!';
                    p++;
                }
            }
            flush(out, text, marks);
            return out;
        }

        private int codeSpan(int p, int to, List<String> marks, List<Inline> out, StringBuilder text) {
            int run = runLength('`', p, to);
            int close = findCodeClose(p + run, to, run);
            if (close < 0) {
                text.append("`".repeat(run));
                return p + run;
            }
            flush(out, text, marks);
            out.add(new Inline.Text(s.substring(p + run, close), withMark(marks, "code")));
            return close + run;
        }

        private int ruby(int p, int to, List<Inline> out, StringBuilder text, List<String> marks) {
            int bar = -1;
            int q = p + 1;
            while (q < to) {
                budget.spend(1);
                char c = s.charAt(q);
                if (c == '{' || (c == '|' && bar >= 0)) {
                    break;
                }
                if (c == '|') {
                    bar = q;
                } else if (c == '}') {
                    if (bar > p + 1 && q > bar + 1) {
                        flush(out, text, marks);
                        out.add(new Inline.Ruby(s.substring(p + 1, bar), s.substring(bar + 1, q)));
                        return q + 1;
                    }
                    break;
                }
                q++;
            }
            text.append('{');
            return p + 1;
        }

        private int link(int p, int to, List<String> marks, int depth, List<Inline> out, StringBuilder text,
                         boolean image) {
            int close = matchingBracket(p, to);
            if (close < 0 || close + 1 >= to || s.charAt(close + 1) != '(' || close == p + 1) {
                text.append('[');
                return p + 1;
            }
            int urlStart = close + 2;
            int urlEnd = urlStart;
            while (urlEnd < to && s.charAt(urlEnd) != ')' && !Character.isWhitespace(s.charAt(urlEnd))) {
                budget.spend(1);
                urlEnd++;
            }
            if (urlEnd >= to || s.charAt(urlEnd) != ')' || urlEnd == urlStart) {
                text.append('[');
                return p + 1;
            }
            if (image) {
                // Images are not supported in v1: {@code ![alt](url)} stays literal text, no link and no warning.
                text.append(s, p, urlEnd + 1);
                return urlEnd + 1;
            }
            if (insideLabel) {
                if (!nestedLinkReported) {
                    nestedLinkReported = true;
                    findings.error(line, null, MbmCode.MBM_NESTED_LINK, null);
                }
                text.append('[');
                return p + 1;
            }
            String url = s.substring(urlStart, urlEnd);
            boolean outer = insideLabel;
            insideLabel = true;
            List<Inline> label;
            try {
                label = span(p + 1, close, marks, depth + 1);
            } finally {
                insideLabel = outer;
            }
            flush(out, text, marks);
            Optional<String> href = allowlist.lookup(url);
            if (href.isPresent()) {
                out.add(new Inline.Link(href.get(), label));
            } else {
                findings.warning(line, null, MbmCode.MBM_LINK_NOT_ALLOWED);
                out.addAll(label);
            }
            return urlEnd + 1;
        }

        private int emphasis(int p, int from, int to, List<String> marks, int depth, List<Inline> out,
                             StringBuilder text) {
            int run = runLength('*', p, to);
            if (run >= 3) {
                findings.warning(line, null, MbmCode.MBM_LITERAL_DELIMITER);
                text.append("*".repeat(run));
                return p + run;
            }
            boolean followedByContent = p + run < to && !isWhitespace(s.codePointAt(p + run));
            boolean afterWord = p > from && Character.isLetterOrDigit(s.codePointBefore(p));
            if (!followedByContent || afterWord) {
                text.append("*".repeat(run));
                return p + run;
            }
            int close = findEmphasisClose(p + run, to, run);
            if (close < 0) {
                findings.warning(line, null, MbmCode.MBM_LITERAL_DELIMITER);
                text.append("*".repeat(run));
                return p + run;
            }
            flush(out, text, marks);
            out.addAll(span(p + run, close, withMark(marks, run == 2 ? "strong" : "em"), depth + 1));
            return close + run;
        }

        private int runLength(char c, int p, int to) {
            int end = p;
            while (end < to && s.charAt(end) == c) {
                end++;
            }
            budget.spend(end - p);
            return end - p;
        }

        /** Start of the next run of exactly {@code run} backticks at or after {@code from}, or -1. */
        private int findCodeClose(int from, int to, int run) {
            int q = from;
            while (q < to) {
                budget.spend(1);
                if (s.charAt(q) == '`') {
                    int length = runLength('`', q, to);
                    if (length == run) {
                        return q;
                    }
                    q += length;
                } else {
                    q++;
                }
            }
            return -1;
        }

        /** The matching {@code ]} of the {@code [} at {@code p}: brackets nest, escapes are skipped. */
        private int matchingBracket(int p, int to) {
            int depthCount = 1;
            int q = p + 1;
            while (q < to) {
                budget.spend(1);
                char c = s.charAt(q);
                if (c == '\\' && q + 1 < to && isAsciiPunctuation(s.charAt(q + 1))) {
                    q += 2;
                    continue;
                }
                if (c == '[') {
                    depthCount++;
                } else if (c == ']' && --depthCount == 0) {
                    return q;
                }
                q++;
            }
            return -1;
        }

        /**
         * The closer of an emphasis opener: the first later run of exactly {@code run} asterisks that is preceded by
         * a non-whitespace character and not followed by a letter or digit; escapes and code spans are skipped.
         */
        private int findEmphasisClose(int from, int to, int run) {
            int q = from;
            while (q < to) {
                budget.spend(1);
                char c = s.charAt(q);
                if (c == '\\' && q + 1 < to && isAsciiPunctuation(s.charAt(q + 1))) {
                    q += 2;
                } else if (c == '`') {
                    int length = runLength('`', q, to);
                    int close = findCodeClose(q + length, to, length);
                    q = close < 0 ? q + length : close + length;
                } else if (c == '*') {
                    int length = runLength('*', q, to);
                    if (length == run && q > from && !isWhitespace(s.codePointBefore(q))
                            && !(q + length < to && Character.isLetterOrDigit(s.codePointAt(q + length)))) {
                        return q;
                    }
                    q += length;
                } else {
                    q++;
                }
            }
            return -1;
        }
    }

    private static void flush(List<Inline> out, StringBuilder text, List<String> marks) {
        if (!text.isEmpty()) {
            out.add(new Inline.Text(text.toString(), marks));
            text.setLength(0);
        }
    }

    private static List<String> withMark(List<String> marks, String mark) {
        if (marks.contains(mark)) {
            return marks;
        }
        var copy = new ArrayList<String>(marks.size() + 1);
        copy.addAll(marks);
        copy.add(mark);
        return List.copyOf(copy);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isAsciiPunctuation(char c) {
        return (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
    }
}
