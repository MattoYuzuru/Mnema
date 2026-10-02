# Native document v1 — baseline boundary (#178 / Epic #74)

Implemented by Learning's `catalog.content.NativeDocumentReader`, immutable native
storage, authoring APIs and the production Angular editor/renderer. This file owns
the baseline wire boundary and the Epic #76 rich nodes below; exercise content lives in
the separate [`contracts/study`](../../study/README.md) contract.

Input is UTF-8 JSON read through `ContentJsonReader` **before** ordinary JSON binding
can discard duplicate keys. Output is a defensive `NativeDocument` semantic snapshot.
Validation failures contain only `Invalid native document`, with no private values
or parser cause. API integration must map this boundary failure to `INVALID_REQUEST`;
the reader itself does not introduce an HTTP endpoint or change exception routing.

## Structure and preservation

Envelope contains exactly `formatVersion: 1` and `root`. Every node requires `id`,
`type`, `version`, object `attrs`, and array `content` (including leaves).
IDs are UUIDv4, unique by UUID value within the whole document including opaque
descendants. Uppercase spelling is retained but does not evade duplicate detection.
The same logical node ID intentionally survives across revisions; copying creates
new recursive IDs, moving retains them. IDs alone never authorize access.

Types match `[a-z][a-z0-9_]{0,63}`; versions are integers 1..2147483647. Root type is
`doc`. Unknown type or future version creates an opaque boundary: preserve all
semantic fields, including extension fields, and validate descendants structurally
without interpreting known-looking attributes. Future `doc` versions are entirely
read-only to this version's editor. An unknown document `formatVersion` is rejected.

Supported version-1 nodes reject extra core fields/attributes. Optional attribute
presence, spelling, mark order, array order and opaque JSON values are retained;
there is no Unicode normalization or editor-library default injection. Object-key
order, whitespace and equivalent JSON number spelling are not preserved.

| Baseline type | Children | Type-specific attributes |
|---|---|---|
| doc | nonempty blocks; only at root | none |
| paragraph | inline nodes, possibly empty | none |
| heading | inline nodes, possibly empty | required integer level 1..6 |
| blockquote | nonempty blocks | none |
| bullet_list / ordered_list | nonempty list items | ordered list: optional positive int order |
| list_item | first child type paragraph, then blocks; only in a list | none |
| text | none | nonempty text; optional unique marks from strong/em/code |
| ruby | none | nonempty base and reading |
| link | nonempty inline content; no nested supported links | required href |
| divider | none | none |

All supported nodes additionally allow optional `lang` and `dir` (`auto/ltr/rtl`),
including inline spans. An opaque child remains allowed in a known container slot;
a future paragraph first child remains opaque, not a validated paragraph@1. Editors
must use an appropriate slot placeholder or make its containing structure opaque;
they must not drop it or coerce it to fit a library schema. Known leaves have no
children, including opaque ones. Empty documents use one empty paragraph.

Opaque acceptance grants **no rendering or execution capability**. Registering a
new type/renderer must revalidate retained content against that schema before
activating it; a matching type string is insufficient. Code/math and unknown
future versions remain inert placeholders (`math` and `math_block` stay opaque; a
formula is text until a separate KaTeX/MathML decision, not scheduled). The Epic #76
rich nodes and `code_block` below have explicit validators; their `version: 1` is the
only supported version.

## Rich block nodes (Epic #76)

`image`, `audio`, `video`, `youtube`, `mermaid`, `table` and `code_block` (next section) are block leaves with empty
`content: []`. They share optional `lang`/`dir` with baseline nodes. Media nodes
hold logical UUIDv4 `assetId` values, never object keys or direct URLs. The server
extracts only supported image/audio/video references and binds them inside the
same transaction that inserts a pinned item revision. Unknown/future nodes and
their descendants do not grant media access. Pending assets may be referenced;
the viewer resolves them only after verification.

| Node | Required attributes | Optional attributes |
|---|---|---|
| image | `assetId`, nonblank `alt` | nonblank `caption`, `description` (long text alternative) |
| audio | `assetId`, nonblank `title` | nonblank `transcript` |
| video | `assetId`, nonblank `title` | nonblank `transcript` |
| youtube | `videoId` (exactly 11 URL-safe YouTube ID characters), nonblank `title` | nonblank `transcript` |
| mermaid | nonblank `source`, `title`, `description` | none |
| table | nonblank `caption`, `columns` (1–12 nonblank headings), `rows` (0–100 rectangular rows) | nonblank `summary` |

Text length bounds use UTF-16 code units in both Java and TypeScript: image alt
4096, caption/title/table heading 1024, description/summary 8192, transcript or
Mermaid source 16384, and table cell 4096. Empty table cells are valid. The outer
native scalar and document byte limits still apply. `mermaid.source` remains
editable inert data on the server; the reader never parses or executes diagrams.
The web renderer uses strict Mermaid security settings and a text alternative.
Malformed source shows an error while retaining the source for repair. Tables
render as semantic HTML tables. See [rich.json](valid/rich.json) for a shared
cross-client fixture.
"Nonblank" follows Java `String.isBlank()` on both clients; the shared
[rich text vectors](rich-text-vectors.json) cover Unicode whitespace differences
from JavaScript `trim()`.

A video poster is a derived variant of the same asset. Synchronized caption
tracks need a separate versioned sidecar-reference contract; a transcript alone
is not a synchronized caption track. The current video node must not claim full
caption accessibility before that contract is delivered.

`youtube` stores only a fixed provider video ID, never arbitrary iframe HTML or
an external URL. The viewer constructs a `youtube-nocookie.com/embed/ID` frame
only after the reader activates it, leaves YouTube controls visible, and always
offers a source link. The frame is third-party content and may be unavailable or
disallow embedding; the source link remains usable. See
[youtube.json](valid/youtube.json) for the shared fixture.

## Code blocks

A block leaf with empty `content: []`, allowed wherever a block is. Attributes:

| Attribute | Rule |
|---|---|
| `source` | required string; nonblank (`String.isBlank()`, as Mermaid source); at most 16384 UTF-16 code units; **no carriage return**. Tabs, line feeds, leading/trailing spaces and inner blank lines are data and kept byte for byte |
| `lang` | optional string: empty (same as absent) or a programming-language identifier `[a-z0-9][a-z0-9+#.-]{0,31}` (`sql`, `python`, `c++`, `c#`, `objective-c`). A pattern, not an allowlist, so a new language needs no contract change; it is only a label and a CSS class suffix, never executed or looked up |

Differences from every other supported node, deliberately:

- **`lang` is not a BCP 47 tag here.** The shared optional `lang`/`dir` of the lexical profile below do not apply: `lang`
  names the language of the code, and `dir` is not accepted (code is always rendered left to right).
- **LF only.** A `\r` in `source` is rejected. Writers normalize `\r\n` and `\r` to `\n` before they build the node (the MBM
  compiler and the editor adapter do); the server never rewrites stored content.

The reader never parses, highlights or executes `source`. The web renderer shows it as text in `<pre><code
class="language-…">` without a third-party highlighter, in its own horizontally scrollable, keyboard-focusable region.
Plain-text projection (`NativeNodeIndex.text`, used by exercise `MATERIAL` quotes) of a `code_block` is its `source`.

**Revalidation of retained content.** Before this node was activated, `code_block` was an opaque placeholder and its
attributes were never interpreted (the research fixture carried `language`/`wrap`). The rule above ("registering a new type
must revalidate retained content") is implemented as two reader modes:

- `NativeDocumentReader.read` (publication, drafts, structural writes) validates strictly: a `code_block@1` that breaks the
  table above is rejected, so a new `lang` or `source` outside the limits never enters storage.
- `NativeDocumentReader.readRetained` (the stored-snapshot decoder) keeps a `code_block` that is not valid version 1 (any
  attribute set other than the table above, a bad value, content, extension fields, or a position outside a block slot) as
  an **opaque** node: preserved byte for byte, rendered as an inert placeholder, never projected to text. It sets
  `hasUnsupportedContent`. No migration is needed and rollback (a code revert) leaves data readable.
- The web boundary has the same split: `readNativeDocument` is strict and is used for what the client sends;
  `readRetainedNativeDocument` is used for what the server returns. An author who loads a document with such a retained
  placeholder sees it as an unsupported block; saving the document unchanged is rejected by the server until the placeholder
  is deleted, because the write path is strict.

Shared fixtures: [code.json](valid/code.json) (valid document, round trip) and
[code-block-vectors.json](code-block-vectors.json) (`attrs` accepted and rejected by Java and TypeScript). The
16384-unit bound has generated tests on both sides. MBM v1 compiles a fenced block to this node, see
[mbm-v1](../../generation/mbm-v1/README.md).

## Shared lexical profile

[lexical-vectors.json](lexical-vectors.json) is a shared server/editor corpus. Preserve
accepted strings; UI may offer an explicit conversion before submission.

- `lang`: at most 64 ASCII characters; 2–3-letter language, optional four-letter
  script, optional two-letter or three-digit region, then BCP47 core variants
  (5–8 alphanumerics or digit + 3 alphanumerics); case-insensitive validation,
  duplicate variants forbidden. Extensions/private-use/grandfathered/extlang forms
  are not in this baseline profile. Java Locale and browser Intl are not the grammar.
- `href`: at most 2048 printable ASCII characters, absolute hierarchical HTTPS;
  no spaces, controls, backslashes, invalid percent escapes, or user info. DNS labels
  are LDH, 1..63 characters, whole host at most 253, without empty/trailing labels.
  Numeric-ending hosts must be canonical four-octet decimal IPv4, not octal/hex/short
  forms. IPv6 literals have no zone identifier. Ports are absent or 1..65535.
  A-labels must survive the JDK's conservative `java.net.IDN` decode/re-encode round trip (verified on Java 25);
  this is a subset, **not** a claim of full browser UTS46/IDNA2008 equivalence.
  Unicode host/path input needs explicit punycode/percent-encoding before submission.

Links are navigation data, never fetched by the server. This is not DNS existence,
destination trust, an SSRF allowlist, or media authorization. Renderers must preserve
the original href even when navigation is unavailable; no server/client parser may
silently rewrite persisted content.

## Independent limits

Raw UTF-8 and canonical bytes each ≤1 MiB; parser tokens ≤250,000; JSON nesting ≤128;
native nodes ≤10,000; native depth ≤32 (root=1); string/property-name UTF-8 ≤32 KiB.
All bounds apply, including opaque data. The parser independently rejects malformed
UTF-8, duplicate keys, unsupported JSONB Unicode and non-browser-interoperable numbers.
The canonical-byte check prevents short exponent notation from expanding unboundedly.
These safety limits do not imply each sub-1-MiB object fits every other limit.
The 32-KiB scalar bound is not the 16-KiB physical storage object size: the storage
codec must fragment large scalars without changing their semantic node identity.

## Evidence and integration gate

[valid/mixed.json](valid/mixed.json) preserves the completed research fixture, not
ProseMirror state (its `code_block` was rewritten to the version-1 shape in #303; `math_block`,
`media_reference` and `future_formula` keep covering opaque nodes). Java tests cover it, lexical vectors, opaque fields/descendants,
UUID case duplicates, structure, defensive copies, multilingual scalar/depth/count
boundaries, and a 7,001-node document beyond the earlier 100k-token parser budget.

The delivered adapter preserves optional metadata, aligns shared validation, handles
opaque list slots and is integrated with Angular/browser authoring. It does not claim
the still-unrun real IME/device/assistive-technology sessions recorded in Epic #74
acceptance. No production action occurred; rollback is a protected code revert.

Local verification, 2026-09-12: Java21 / existing PostgreSQL18 Testcontainers,
`./gradlew :services:learning:test --rerun-tasks --console=plain`, exit0, 17 seconds.
176 Learning tests, including70 native reader cases, zero failures/errors/skips.
JaCoCo lines: Learning619/637 (97.17%, existing floor90%), native186/190 (97.89%).
Independent review identified lexical URL/language drift and optional-field/opaque
adapter gaps; this boundary now has explicit shared vectors and preserves semantic
fields without editor normalization. The production adapter consumes those vectors;
the complete protected-delivery and integrated authoring result is recorded in the
[#74 acceptance](../../../docs/engineering/evidence/epic-74/verification/integrated-main-2026-09-19.md).

Sources: [native-format target](../../../docs/architecture/learning-content-format-v2.md),
[Java21 URI](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URI.html),
[Java21 IDN](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/IDN.html),
[RFC9562](https://www.rfc-editor.org/rfc/rfc9562.html). URI/IDN inform the deliberately
bounded link profile, not an assertion that browser and Java normalization agree.
