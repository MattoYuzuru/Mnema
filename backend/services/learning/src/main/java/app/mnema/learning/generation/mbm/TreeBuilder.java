package app.mnema.learning.generation.mbm;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Turns parsed blocks into the native-v1 envelope. Adjacent text with equal marks is merged first and identifiers are
 * allocated while the final tree is created top-down, which is document pre-order: a node before its children, the
 * root first. A block that kept its ID through a handle consumes no allocation.
 */
final class TreeBuilder {

    /** Root ID of an edited range's wrapper {@code doc}; it consumes no allocation and is dropped on splice. */
    static final UUID RANGE_ROOT = UUID.fromString("00000000-0000-4000-8000-000000000000");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int ID_ATTEMPTS = 16;

    private final IdAllocator ids;
    private final String sourcesHeading;
    private final Set<UUID> used = new HashSet<>();
    private final List<MbmSlot> slots = new ArrayList<>();
    private int nodeCount;

    TreeBuilder(IdAllocator ids, MbmOptions options) {
        this.ids = ids;
        this.sourcesHeading = options.sourcesHeading();
        used.add(RANGE_ROOT);
        options.handles().values().forEach(handle -> used.add(handle.nodeId()));
    }

    /** The compiled envelope. */
    record Built(ObjectNode envelope, int nodeCount, List<MbmSlot> slots) {
    }

    Built build(List<Block> blocks, boolean edit) {
        ObjectNode root = node("doc", edit ? RANGE_ROOT : freshNodeId(), JSON.createObjectNode());
        ArrayNode content = (ArrayNode) root.get("content");
        for (Block block : blocks) {
            block(content, block);
        }
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("formatVersion", 1);
        envelope.set("root", root);
        return new Built(envelope, nodeCount, List.copyOf(slots));
    }

    static byte[] toBytes(ObjectNode envelope) {
        return JSON.writeValueAsBytes(envelope);
    }

    // ------------------------------------------------------------------ blocks

    private void block(ArrayNode parent, Block block) {
        switch (block) {
            case Block.Heading heading -> {
                ObjectNode node = child(parent, "heading", block.keptId(), JSON.createObjectNode().put("level", heading.level()));
                inlines(node, heading.content());
            }
            case Block.Paragraph paragraph -> inlines(child(parent, "paragraph", block.keptId()), paragraph.content());
            case Block.ListBlock list -> list(parent, list);
            case Block.Quote quote -> {
                ObjectNode node = child(parent, "blockquote", block.keptId());
                for (List<Inline> paragraph : quote.paragraphs()) {
                    inlines(child(node, "paragraph", null), paragraph);
                }
            }
            case Block.Divider divider -> child(parent, "divider", block.keptId());
            case Block.Table table -> table(parent, table);
            case Block.Mermaid mermaid -> {
                ObjectNode attrs = JSON.createObjectNode();
                attrs.put("source", mermaid.source());
                attrs.put("title", mermaid.title());
                attrs.put("description", mermaid.description());
                child(parent, "mermaid", block.keptId(), attrs);
            }
            case Block.Code code -> {
                ObjectNode attrs = JSON.createObjectNode();
                if (!code.lang().isEmpty()) {
                    attrs.put("lang", code.lang());
                }
                attrs.put("source", code.source());
                child(parent, "code_block", block.keptId(), attrs);
            }
            case Block.Media media -> media(parent, media);
            case Block.Sources sources -> sources(parent, sources);
        }
    }

    private void list(ArrayNode parent, Block.ListBlock list) {
        ObjectNode attrs = JSON.createObjectNode();
        if (list.ordered() && list.order() > 1) {
            attrs.put("order", list.order());
        }
        ObjectNode node = child(parent, list.ordered() ? "ordered_list" : "bullet_list", list.keptId(), attrs);
        for (List<Inline> item : list.items()) {
            ObjectNode listItem = child(node, "list_item", null);
            inlines(child(listItem, "paragraph", null), item);
        }
    }

    private void table(ArrayNode parent, Block.Table table) {
        ObjectNode attrs = JSON.createObjectNode();
        attrs.put("caption", table.caption());
        ArrayNode columns = attrs.putArray("columns");
        table.columns().forEach(columns::add);
        ArrayNode rows = attrs.putArray("rows");
        for (List<String> row : table.rows()) {
            ArrayNode cells = rows.addArray();
            row.forEach(cells::add);
        }
        child(parent, "table", table.keptId(), attrs);
    }

    private void media(ArrayNode parent, Block.Media media) {
        UUID nodeId = media.keptId() != null ? media.keptId() : freshNodeId();
        UUID assetId = freshAssetId();
        ObjectNode attrs = JSON.createObjectNode();
        attrs.put("assetId", assetId.toString());
        var spec = new LinkedHashMap<String, String>();
        String type;
        switch (media.kind()) {
            case AUDIO -> {
                type = "audio";
                attrs.put("title", media.label());
                attrs.put("transcript", media.text());
                attrs.put("lang", media.lang());
                spec.put("lang", media.lang());
                if (media.voice() != null) {
                    spec.put("voice", media.voice());
                }
                spec.put("text", media.text());
            }
            case IMAGE -> {
                type = "image";
                attrs.put("alt", media.label());
                spec.put("mode", media.mode());
                spec.put("search".equals(media.mode()) ? "query" : "prompt", media.text());
            }
            default -> {
                type = "video";
                attrs.put("title", media.label());
                spec.put("prompt", media.text());
            }
        }
        parent.add(node(type, nodeId, attrs));
        slots.add(new MbmSlot(media.slotKey(), media.kind(), nodeId, assetId, spec));
    }

    private void sources(ArrayNode parent, Block.Sources sources) {
        ObjectNode heading = child(parent, "heading", sources.keptId(), JSON.createObjectNode().put("level", 2));
        inlines(heading, List.of(new Inline.Text(sourcesHeading, List.of())));
        ObjectNode list = child(parent, "bullet_list", null);
        for (Block.Sources.Entry entry : sources.entries()) {
            ObjectNode paragraph = child(child(list, "list_item", null), "paragraph", null);
            String label = entry.title().isBlank() ? entry.url() : entry.title().strip();
            inlines(paragraph, List.of(new Inline.Text("[" + entry.n() + "] ", List.of()),
                    new Inline.Link(entry.url(), List.of(new Inline.Text(label, List.of())))));
        }
    }

    // ------------------------------------------------------------------ inline

    private void inlines(ObjectNode parent, List<Inline> content) {
        ArrayNode target = (ArrayNode) parent.get("content");
        for (Inline inline : merge(content)) {
            switch (inline) {
                case Inline.Text text -> {
                    ObjectNode attrs = JSON.createObjectNode();
                    attrs.put("text", text.text());
                    ArrayNode marks = attrs.putArray("marks");
                    text.marks().forEach(marks::add);
                    child(target, "text", null, attrs);
                }
                case Inline.Ruby ruby -> {
                    ObjectNode attrs = JSON.createObjectNode();
                    attrs.put("base", ruby.base());
                    attrs.put("reading", ruby.reading());
                    child(target, "ruby", null, attrs);
                }
                case Inline.Link link -> {
                    ObjectNode node = child(target, "link", null, JSON.createObjectNode().put("href", link.href()));
                    inlines(node, link.children());
                }
            }
        }
    }

    /** Adjacent text nodes with equal marks become one node. */
    static List<Inline> merge(List<Inline> content) {
        var merged = new ArrayList<Inline>(content.size());
        for (Inline inline : content) {
            if (inline instanceof Inline.Text text && !merged.isEmpty()
                    && merged.get(merged.size() - 1) instanceof Inline.Text previous
                    && previous.marks().equals(text.marks())) {
                merged.set(merged.size() - 1, new Inline.Text(previous.text() + text.text(), text.marks()));
            } else {
                merged.add(inline);
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------ nodes and identifiers

    private ObjectNode child(ArrayNode parent, String type, UUID kept) {
        return child(parent, type, kept, JSON.createObjectNode());
    }

    private ObjectNode child(ObjectNode parent, String type, UUID kept) {
        return child((ArrayNode) parent.get("content"), type, kept, JSON.createObjectNode());
    }

    private ObjectNode child(ObjectNode parent, String type, UUID kept, ObjectNode attrs) {
        return child((ArrayNode) parent.get("content"), type, kept, attrs);
    }

    private ObjectNode child(ArrayNode parent, String type, UUID kept, ObjectNode attrs) {
        ObjectNode node = node(type, kept != null ? kept : freshNodeId(), attrs);
        parent.add(node);
        return node;
    }

    private ObjectNode node(String type, UUID id, ObjectNode attrs) {
        if (++nodeCount > NativeDocumentReader.MAX_NODES) {
            throw new LimitExceededException();
        }
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id.toString());
        node.put("type", type);
        node.put("version", 1);
        node.set("attrs", attrs);
        node.putArray("content");
        return node;
    }

    private UUID freshNodeId() {
        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            UUID id = requireV4(ids.nextNodeId());
            if (used.add(id)) {
                return id;
            }
        }
        throw new IllegalStateException("IdAllocator returned only identifiers that are already in the document");
    }

    private UUID freshAssetId() {
        return requireV4(ids.nextAssetId());
    }

    private static UUID requireV4(UUID id) {
        if (id == null || id.version() != 4 || id.variant() != 2) {
            throw new IllegalStateException("IdAllocator must return UUIDv4 values");
        }
        return id;
    }
}
