package app.mnema.learning.generation.mbm;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import tools.jackson.core.JacksonException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles MBM v1 (see {@code contracts/generation/mbm-v1/README.md}) to a native-v1 document. The compiler is pure:
 * no Spring, no I/O, no model call, no clock and no identifier generation of its own. It is thread-safe; one instance
 * can serve every request.
 *
 * <p>Every successful result has already been read by {@link NativeDocumentReader}; a reader rejection is reported as
 * {@link MbmCode#MBM_DOCUMENT_TOO_LARGE} (the reader's size limits are the realistic cause) and never accepted
 * silently. The same code covers three compiler safety bounds that the contract does not list separately: the inline
 * work budget, the inline nesting depth and the 10,000 node bound, which a hostile source can reach before the reader
 * would see the document.
 */
public final class MbmCompiler {

    /** Maximum UTF-8 size of a source. */
    public static final int MAX_SOURCE_BYTES = 256 * 1024;

    private final List<MbmLint> lints;

    public MbmCompiler() {
        this(List.of());
    }

    public MbmCompiler(List<MbmLint> lints) {
        this.lints = List.copyOf(lints);
    }

    /**
     * Compiles {@code source}.
     *
     * @throws IllegalStateException if {@code ids} violates its contract (non-UUIDv4 values, endless duplicates)
     */
    public MbmResult compile(String source, MbmOptions options, IdAllocator ids) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ids, "ids");
        if (source.length() > MAX_SOURCE_BYTES || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            return tooLarge();
        }
        String normalized = normalize(source);
        String[] lines = split(normalized);
        int lastNonblank = lastNonblankLine(lines);

        var findings = new Findings();
        LinkAllowlist allowlist = LinkAllowlist.of(options.allowedLinks(), findings);
        Map<Integer, MbmOptions.ResearchSource> research = filterResearch(options, findings);
        BlockParser.Parsed parsed;
        try {
            var inline = new InlineParser(allowlist, findings, new Budget(normalized.length()));
            parsed = new BlockParser(lines, options, findings, inline, research).parse();
        } catch (LimitExceededException exception) {
            return tooLarge();
        }
        if (parsed.blockStarts() == 0) {
            findings.error(1, null, MbmCode.MBM_EMPTY_DOCUMENT, null);
        }
        if (options.mode() == MbmOptions.Mode.EDIT) {
            options.handles().keySet().stream().sorted(HANDLE_ORDER).filter(handle -> !parsed.usedHandles().contains(handle))
                    .forEach(handle -> findings.error(lastNonblank, null, MbmCode.MBM_EDIT_HANDLE_OMITTED, handle));
        }
        if (findings.hasErrors()) {
            return new MbmResult.Failure(findings.sortedErrors());
        }
        return assemble(normalized, parsed.blocks(), options, ids, findings);
    }

    private MbmResult assemble(String normalized, List<Block> blocks, MbmOptions options, IdAllocator ids,
                               Findings findings) {
        TreeBuilder.Built built;
        NativeDocument validated;
        try {
            built = new TreeBuilder(ids, options).build(blocks, options.mode() == MbmOptions.Mode.EDIT);
            validated = new NativeDocumentReader().read(TreeBuilder.toBytes(built.envelope()));
        } catch (LimitExceededException | IllegalArgumentException | JacksonException exception) {
            return tooLarge();
        }
        var lintFindings = new ArrayList<MbmLint.LintFinding>();
        for (MbmLint lint : lints) {
            lintFindings.addAll(lint.check(normalized, built.envelope().deepCopy()));
        }
        return new MbmResult.Success(built.envelope(), validated.nodeCount(), built.slots(), findings.sortedWarnings(),
                lintFindings);
    }

    private static MbmResult tooLarge() {
        return new MbmResult.Failure(List.of(new MbmFinding(1, null, MbmCode.MBM_DOCUMENT_TOO_LARGE, null)));
    }

    private static Map<Integer, MbmOptions.ResearchSource> filterResearch(MbmOptions options, Findings findings) {
        var research = new HashMap<Integer, MbmOptions.ResearchSource>();
        for (MbmOptions.ResearchSource entry : options.research()) {
            if (NativeProfile.acceptsHref(entry.url())) {
                research.putIfAbsent(entry.n(), new MbmOptions.ResearchSource(entry.n(), entry.url(),
                        sanitizeText(entry.title(), MbmOptions.MAX_RESEARCH_TITLE)));
            } else {
                findings.warning(0, null, MbmCode.MBM_LINK_REJECTED_BY_PROFILE);
            }
        }
        return research;
    }

    /** {@code b2} before {@code b10}, letters in order. */
    private static final Comparator<String> HANDLE_ORDER = Comparator
            .<String>comparingInt(handle -> handle.charAt(0))
            .thenComparingInt(String::length)
            .thenComparing(Comparator.naturalOrder());

    // ------------------------------------------------------------------ source normalization

    /**
     * {@code \r\n} and {@code \r} become {@code \n}. NUL and unpaired surrogates, which no native-v1 string can hold,
     * become U+FFFD so that arbitrary model output stays compilable.
     */
    static String normalize(String source) {
        var out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\r') {
                out.append('\n');
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (c == 0) {
                out.append('\uFFFD');
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < source.length() && Character.isLowSurrogate(source.charAt(i + 1))) {
                    out.append(c).append(source.charAt(++i));
                } else {
                    out.append('\uFFFD');
                }
            } else if (Character.isLowSurrogate(c)) {
                out.append('\uFFFD');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Option text that ends up in the document (research titles, the sources heading): NUL and unpaired surrogates
     * replaced, line breaks and surrounding spaces removed, cut to {@code max} UTF-16 units without splitting a pair.
     */
    static String sanitizeText(String text, int max) {
        String clean = normalize(text).replace('\n', ' ').strip();
        if (clean.length() <= max) {
            return clean;
        }
        int end = Character.isHighSurrogate(clean.charAt(max - 1)) ? max - 1 : max;
        return clean.substring(0, end).stripTrailing();
    }

    /** Lines of the normalized source; a final line break does not start another line. */
    static String[] split(String normalized) {
        var lines = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                lines.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        if (start < normalized.length()) {
            lines.add(normalized.substring(start));
        }
        return lines.toArray(String[]::new);
    }

    private static int lastNonblankLine(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return i + 1;
            }
        }
        return 1;
    }
}
