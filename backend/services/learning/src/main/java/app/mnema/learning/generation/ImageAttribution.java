package app.mnema.learning.generation;

import app.mnema.learning.ai.ImageSearch;
import app.mnema.learning.generation.Rows.Candidate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * What a stock image must credit once it leaves the Workshop (approve and hand-off, #296). Every image node whose asset is a found candidate gets
 * its attribution in the {@code caption}: «{author} · {source} · {license}», appended after an existing caption with « — » and bounded to the
 * 1024 code points of the node schema. The Workshop's revisions never carry it (the slot's {@code attribution} does), so the document is built here,
 * from the revision, every time: nothing is ever credited twice. {@link #media} is the audit entry of each such image for the provenance.
 */
final class ImageAttribution {
    private static final int MAX_CAPTION = 1_024;
    private static final String SEPARATOR = " — ";

    private ImageAttribution() { }

    /** A copy of {@code document} with the captions; the document itself when no image node is a candidate. */
    static JsonNode apply(JsonNode document, List<Candidate> candidates) {
        if (candidates.isEmpty()) return document;
        JsonNode copy = document.deepCopy();
        for (JsonNode block : EditDocument.blocks(copy)) {
            Candidate candidate = candidateOf(block, candidates);
            if (candidate == null) continue;
            ObjectNode attrs = (ObjectNode) block.path("attrs");
            attrs.put("caption", caption(attrs.path("caption").stringValue(""), text(candidate)));
        }
        return copy;
    }

    /** {@code [{assetId, source, sourceId, license, sourcePageUrl}]} of the candidates the image nodes of {@code document} use. */
    static ArrayNode media(JsonNode document, List<Candidate> candidates) {
        ArrayNode media = Json.array();
        for (JsonNode block : EditDocument.blocks(document)) {
            Candidate candidate = candidateOf(block, candidates);
            if (candidate == null) continue;
            media.addObject().put("assetId", candidate.assetId().toString()).put("source", candidate.source()).put("sourceId", candidate.sourceId())
                    .put("license", candidate.license()).put("sourcePageUrl", candidate.sourcePageUrl());
        }
        return media;
    }

    private static Candidate candidateOf(JsonNode block, List<Candidate> candidates) {
        if (!block.path("type").stringValue("").equals("image")) return null;
        String asset = block.path("attrs").path("assetId").stringValue("");
        return candidates.stream().filter(each -> each.assetId().toString().equals(asset)).findFirst().orElse(null);
    }

    /** «Автор · Источник · Лицензия»; without an author the source and the license. */
    static String text(Candidate candidate) {
        String label = label(candidate.source());
        return candidate.author().isBlank() ? label + " · " + candidate.license() : candidate.author() + " · " + label + " · " + candidate.license();
    }

    private static String label(String source) {
        try {
            return ImageSearch.Source.valueOf(source).label();
        } catch (IllegalArgumentException unknown) {
            return source;
        }
    }

    private static String caption(String existing, String attribution) {
        String own = existing == null ? "" : existing.strip();
        if (own.isEmpty()) return bound(attribution, MAX_CAPTION);
        int room = MAX_CAPTION - SEPARATOR.length() - attribution.codePointCount(0, attribution.length());
        if (room < 1) return bound(attribution, MAX_CAPTION);
        return bound(own, room) + SEPARATOR + attribution;
    }

    private static String bound(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) return text;
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
    }
}
