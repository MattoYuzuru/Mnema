# Native document v1 — baseline boundary (#178 / Epic #74)

Implemented by Learning's `catalog.content.NativeDocumentReader`; not yet wired to
HTTP, storage, drafts, or a production editor. This defines baseline structure, not
the complete rich authoring capability set of #74. No database/dependency change.

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
activating it; a matching type string is insufficient. Baseline unsupported examples
include code/math/media/table nodes. Their actual rich schemas/renderers remain #74
work; an inert placeholder is not evidence of full rich-authoring completion.

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
  A-labels must survive Java 21's conservative IDN decode/re-encode round trip;
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
ProseMirror state. Java tests cover it, lexical vectors, opaque fields/descendants,
UUID case duplicates, structure, defensive copies, multilingual scalar/depth/count
boundaries, and a 7,001-node document beyond the earlier 100k-token parser budget.

The research adapter still needs exact optional metadata preservation, aligned
validation, opaque list-slot handling, and Angular/browser integration before
adoption. This slice does not claim real IME/device/assistive-technology verification.
No migration or production action is required; rollback is a protected code revert.

Local verification, 2026-09-12: Java21 / existing PostgreSQL18 Testcontainers,
`./gradlew :services:learning:test --rerun-tasks --console=plain`, exit0, 17 seconds.
176 Learning tests, including70 native reader cases, zero failures/errors/skips.
JaCoCo lines: Learning619/637 (97.17%, existing floor90%), native186/190 (97.89%).
Independent review identified lexical URL/language drift and optional-field/opaque
adapter gaps; this boundary now has explicit shared vectors and preserves semantic
fields without editor normalization. The later production adapter must consume
those vectors; its remaining gaps are not hidden by this reader's green tests.
Full repository exact-candidate gate and protected delivery are still pending.

Sources: [native-format target](../../../docs/architecture/learning-content-format-v2.md),
[Java21 URI](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/URI.html),
[Java21 IDN](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/IDN.html),
[RFC9562](https://www.rfc-editor.org/rfc/rfc9562.html). URI/IDN inform the deliberately
bounded link profile, not an assertion that browser and Java normalization agree.
