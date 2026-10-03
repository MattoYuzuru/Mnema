# MBM v1 — Mnema Block Markup (generation-v1)

MBM is the **only** output format a model writes for materials. A deterministic compiler
turns it into a [native-v1](../../content/native-v1/README.md) document that the same
`NativeDocumentReader` accepts as for any published material. MBM adds no node types: it
is a Markdown subset (with fenced code) plus six directives that map one-to-one onto native-v1 and the
Epic #76 rich nodes. Architecture: [AI generation platform §6](../../../docs/architecture/ai-generation-platform.md).
Status: implemented by the compiler in `app.mnema.learning.generation.mbm`
([#283](https://github.com/MattoYuzuru/Mnema/issues/283)); these fixtures are its acceptance suite and the prompt source for
[`prompts/v1/system.md`](../../../backend/services/learning/src/main/resources/ai/prompts/v1/system.md).

```text
model ──MBM text──▶ compile(source, options, idAllocator) ──▶ native-v1 JSON ──▶ NativeDocumentReader
                         │                                      + slots[] + warnings[]
                         └─ errors[] (no document) ──▶ one repair call ──▶ compile again
```

## Compile contract

`compile(source, options, ids)` is pure and Spring-free (`app.mnema.learning.generation.mbm`).

| Input | Meaning |
|---|---|
| `source` | UTF-8 text, at most 256 KiB. `\r\n` and `\r` are normalized to `\n` first (fixture `crlf`). |
| `options.mode` | `CREATE` (default: a whole new document) or `EDIT` (a range of an existing artifact, below). |
| `options.allowedLinks` | URL strings of the session allowlist: RESEARCH results and URLs found in the user's notes. Filtered through the native-v1 `href` profile first. |
| `options.capabilities` | `videoGeneration`, `imageGeneration` booleans (from `/api/capabilities`, `flag && adapter configured`). |
| `options.research` | `[{n, url, title}]` numbered results of the `RESEARCH` step; used **only** by `::sources` (never `allowedLinks`). Filtered through the `href` profile first. |
| `options.maxMedia` | Maximum media directives for the artifact; default and ceiling 8. The server passes the media counts declared in the spec, so the estimate (which prices only those) cannot be exceeded. |
| `options.existingMediaCount` | `EDIT` only: media nodes of the artifact outside the range; they count against `maxMedia`. |
| `options.handles` | `EDIT` only: `{handle: {nodeId, type}}` of the top-level blocks inside the edited range. |
| `options.existingSlotKeys` | `EDIT` only: slot keys of the artifact that live outside the edited range. |
| `options.sourcesHeading` | Text of the heading that `::sources` emits; default `Sources`. The server derives it from the output language. |
| `ids` | Injected ID allocator with `nextNodeId()` and `nextAssetId()`. The compiler never calls `UUID.randomUUID()` itself. |

Output on success: `document` (a complete native-v1 envelope; for an edit, the range wrapped in a
`doc` whose root ID is the reserved constant `00000000-0000-4000-8000-000000000000`, which consumes no
allocation and is discarded when the range is spliced into the artifact), `slots[]` (one per media
directive) and `warnings[]`. Output on failure: `errors[]` and **no** document.
The compiler reports every finding it can detect, ordered by `(line, column)`, at most 20 errors.
The compiled document is then read by `NativeDocumentReader`; a reader rejection is
`MBM_DOCUMENT_TOO_LARGE` or a compiler bug, never silently accepted.

Findings are `{line, column?, code, attribute?}` (see [`codes.json`](codes.json) for every code, its
severity and the one-line rule used in the repair prompt). `line` is 1-based, or 0 for a finding about the
options (a URL dropped by the profile). `column` is the 1-based Unicode code point of the offending block
or directive; inline findings (nested link, link not allowed, literal delimiter) carry only the first line
of their block and no column; `MBM_EDIT_HANDLE_OMITTED` is reported on the last nonblank line. Findings
never echo content. The model's first line is *expected* to be a single `# title`; the compiler does **not**
enforce it (a document may start with a paragraph and the title fallback of Browse applies). The slop
and structure lint of AI-04 checks it.

### Golden IDs

IDs are allocated **on the final tree**, after adjacent text nodes with equal marks were merged, in
**document pre-order** (a node before its children). Golden `*.native.json` files use deterministic IDs so they
can be compared byte for byte (object-key order and whitespace are not significant, as for native-v1):

- node IDs: `00000000-0000-4000-8000-000000000001`, `…002`, … hexadecimal, starting with the root `doc` (a new
  document) or with the first node of the range (an edit, whose wrapper root is the reserved `…0000`);
- media `assetId`: a separate sequence `00000000-0000-4000-a000-000000000001`, … in document order of the
  media directives;
- a block that keeps its node ID through a handle consumes no number; its descendants are always new.

Production uses random UUIDv4 through the same allocator interface. The compiler must avoid IDs that already
exist in the artifact.

## Grammar

Block structure is line-based. `EOL` ends a line; a blank line (only spaces) separates blocks.
Inline text is parsed per block, after joining lines (below).

```ebnf
document      = { blank-line | block } ;
block         = [ handle ] ( heading | divider | blockquote | list | code-fence | directive | paragraph ) ;
handle        = "[[" letter digit { digit } "]]" " " ;               (* e.g. [[b3]]; start of the block's first line *)

heading       = ( "#" | "##" | "###" ) " " inline EOL ;                 (* "####" or deeper: MBM_HEADING_LEVEL *)
divider       = "---" EOL ;
blockquote    = quote-line { quote-line } ;
quote-line    = ">" [ " " ] [ inline ] EOL ;                           (* a line with no text separates paragraphs *)
list          = bullet-item { item-line } { [ blank-line ] bullet-item { item-line } }
              | ordered-item { item-line } { [ blank-line ] ordered-item { item-line } } ;   (* at most ONE blank line between items *)
bullet-item   = "- " inline EOL ;
ordered-item  = digit { digit } ". " inline EOL ;
item-line     = line-that-is-not-a-block-start EOL ;                   (* continuation of the previous item *)
paragraph     = line { line } ;                                        (* lines up to a blank line or a block start; each line is trimmed *)

code-fence    = backtick-run [ language ] EOL { line } backtick-run EOL ;   (* 3+ backticks; the closing run is at least as long; language is not "mermaid" *)
directive     = "::" name [ attributes ] [ " " text ] EOL [ directive-body ] ;
name          = "table" | "mermaid" | "audio" | "image" | "video" | "sources" ;
attributes    = "{" { space } [ attribute { space attribute } ] { space } "}" ;
attribute     = key "=" '"' { char-without-quote | '\"' | '\\' } '"' ;
key           = letter-lower { letter-lower | digit | "_" } ;
directive-body= pipe-table | mermaid-fence | source-line { source-line } ;
pipe-table    = row separator { row } ;                                (* lines starting with "|" *)
row           = "|" cell { "|" cell } [ "|" ] EOL ;
separator     = "|" dashes { "|" dashes } [ "|" ] EOL ;                (* dashes: "-"+ with optional ":" at either end *)
mermaid-fence = "```mermaid" EOL { line } "```" EOL ;
source-line   = "[" digit { digit } "] " url EOL ;

inline        = { text | strong | em | code | link | ruby } ;
strong        = "**" inline-nonspace "**" ;   em = "*" inline-nonspace "*" ;
code          = backtick-run characters backtick-run ;
link          = "[" link-label "](" url ")" ;
ruby          = "{" base "|" reading "}" ;                              (* base, reading: nonempty, no { } | *)
escape        = "\" ascii-punctuation ;                                 (* yields the character literally *)
```

### Blocks

| MBM | native-v1 | Notes |
|---|---|---|
| `# …` to `### …` | `heading` `level` 1..3 | Deeper levels are `MBM_HEADING_LEVEL`. `#` without a following space is paragraph text. |
| paragraph | `paragraph` | Each line is trimmed of leading and trailing spaces and the lines are joined with a single space (soft break). There is no hard break. |
| `- ` / `1. ` | `bullet_list` / `ordered_list` + `list_item` (one `paragraph` each) | Consecutive items of the same kind form ONE list, also when single blank lines separate them (fixture `loose-list`); two blank lines, a block start or another marker kind end the list. An item is always one paragraph. One level only; an indented list marker is `MBM_NESTED_LIST`. Ordered lists keep only the first number: `order` is set when it is not 1 (a first number of 0 leaves it unset, as native-v1 requires a positive `order`); later numbers are ignored. |
| `> …` | `blockquote` of `paragraph`s | Only paragraphs: other markers inside a quote are literal text. |
| `---` | `divider` | Exactly three hyphens. |
| a fenced block outside `::mermaid` | `code_block` | The info string (after trimming) is empty or one language identifier `[a-z0-9][a-z0-9+#.-]{0,31}` and becomes `lang` (`MBM_CODE_LANGUAGE_INVALID` otherwise; `mermaid` is reserved for `::mermaid`). The source is the lines between the fences joined with `\n`: indentation, tabs, trailing spaces and inner blank lines are data. It must contain a nonblank line (`MBM_EMPTY_CODE_BLOCK`) and be at most 16384 UTF-16 units (`MBM_VALUE_TOO_LONG`). The closing fence is a line of only backticks at least as long as the opening one; an unclosed fence is `MBM_UNTERMINATED_FENCE`. The renderer writes a fence longer than any backtick run at the start of a source line (fixture `code-block`). |

### Inline

Inline text of a block (after trimming and joining) is parsed by **one left-to-right algorithm**. A *span* is the
text being parsed (the whole block text, or the inside of an emphasis, link label or recursion); the start and
end of a span count as non-alphanumeric boundaries. At each position, the first rule that applies wins:

1. **Backslash.** `\` followed by an ASCII punctuation character yields that character as text (two characters
   consumed). Otherwise the backslash is literal.
2. **Code span.** A run of N backticks opens a code span that ends at the next run of **exactly** N backticks in
   the span; the content between is verbatim text with mark `code` (no escapes, no nesting). No such closer: the
   run is literal.
3. **Ruby.** `{base|reading}` where `base` and `reading` are nonempty and contain none of `{ } |` and no
   newline becomes a `ruby` node. Anything else starting with `{` is literal.
4. **Link.** `[` up to its matching `]` (nested brackets counted, escapes skipped), **immediately** followed by
   `(`, a nonempty URL without whitespace, up to the first `)`: a link. The label is parsed as an inline span; a
   link inside a label is `MBM_NESTED_LINK`. Otherwise the `[` is literal.
5. **Emphasis.** A run of `*` of length 1 or 2 is *eligible* to open when the character after it exists and is not
   whitespace, **and** the character before it is not a letter or digit (so an intraword `*` is literal text:
   `snake*case*word`, `2*3*4`). An eligible opener of length N closes at the first later run of exactly N `*` that is
   preceded by a non-whitespace character and **not followed** by a letter or digit, skipping escapes and code
   spans. The inside is parsed recursively as a span with the mark `strong` (N = 2) or `em` (N = 1). Without a
   closer the run is literal and `MBM_LITERAL_DELIMITER` is a warning.
6. A run of three or more `*` is literal text with the warning. A run that is not eligible (followed by a space,
   or intraword) is literal without a warning.
7. Everything else is text.

Underscore is never emphasis. Delimiters next to punctuation work (`(**term**), *em*;`). Unmatched delimiters are
always literal. A text node carries the unique marks of all enclosing spans, outermost first (`["strong","em"]`);
adjacent text with equal marks is one `text` node. Fixtures: `paragraph-marks`, `inline-intraword-punctuation`,
`escapes`, `literals`, `ruby-in-link`.

| MBM | native-v1 |
|---|---|
| `**x**`, `*x*`, `` `x` `` | `text` with marks `strong`, `em`, `code` |
| `{漢字\|かんじ}` | `ruby` {`base`, `reading`} (no marks inside; may sit inside a link label) |
| `[label](url)` | `link` {`href`} with inline children, **only** if `url` is in the filtered allowlist |
| `\*` etc. | the literal character |

Not supported in v1 (literal text): images, autolinks, reference links, link titles, raw HTML, footnotes,
underscore emphasis, tables outside `::table`, nested lists, task lists, hard breaks.

### Directives

A directive starts at the beginning of a line (after an optional handle). Text after `::` that is
not one of the six names is `MBM_UNKNOWN_DIRECTIVE`; `::` inside a line is literal text.
All attribute values are double-quoted. Required attributes must be nonblank.

| Directive | Required attributes | Optional | Body | native-v1 node | Slot |
|---|---|---|---|---|---|
| `::table{caption}` | `caption` | — | pipe table: ≤12 columns, ≤100 body rows, rectangular, nonblank headings; each cell is **trimmed** and is literal text (no inline markup; `\|` and `\\` escapes only); empty cells allowed | `table` {`caption`, `columns`, `rows`} | — |
| `::mermaid{title description}` | `title`, `description` | — | a fenced block whose opening fence has the info string `mermaid`, starting on the next line | `mermaid` {`source`, `title`, `description`} | — |
| `::audio{slot lang title}` | `slot`, `lang`, `title` | `voice` = `female`\|`male` | text after the brace, the spoken text, **at most 600 characters** (`MBM_AUDIO_TEXT_TOO_LONG`) | `audio` {`assetId`, `title`, `transcript`=body, `lang`} | `AUDIO`, spec `{lang, voice?, text}` |
| `::image{slot mode alt}` | `slot`, `mode` = `search`\|`generate`, `alt` | — | text after the brace: search query (`search`) or generation prompt (`generate`), at most 300 characters | `image` {`assetId`, `alt`} | `IMAGE`, spec `{mode, query}` or `{mode, prompt}` |
| `::video{slot title}` | `slot`, `title` | — | text after the brace: generation prompt, at most 300 characters | `video` {`assetId`, `title`} | `VIDEO`, spec `{prompt}` |
| `::sources` | — | — | one line per source: `[n] URL` | `heading` level 2 (`options.sourcesHeading`) + `bullet_list`; each item is a paragraph: text `[n] ` and a `link` whose label is the research result title | — |

Rules shared by all directives:

- `alt`, `title`, `caption`, `description` are mandatory because native-v1 requires them; blank is missing
  (`MBM_MISSING_ATTRIBUTE`). `lang` must satisfy the native-v1 lexical profile
  ([lexical-vectors](../../content/native-v1/lexical-vectors.json)); `voice`, `mode` are closed sets.
- `::image mode="generate"` requires `capabilities.imageGeneration`, `::video` requires
  `capabilities.videoGeneration`; otherwise `MBM_CAPABILITY_OFF`. `video` and `generate` stay in the
  grammar so the server, not the model, decides availability.
- Slot keys match `[a-z][a-z0-9_]{0,31}` and are unique across the document and the existing slots
  outside an edited range. A media directive allocates the node ID **and** a pre-allocated `assetId`
  (the asset is created later by the media step; the node may already reference it, as native-v1 allows
  pending assets). The slot record `{slotKey, kind, nodeId, assetId, spec}` is the compiler output for the
  `generation_media_slot` row.
- **Media cost bound:** at most 8 media directives per artifact (`MBM_TOO_MANY_MEDIA`, reported at the first
  excess directive); the spec's declared counts lower the bound. The estimate prices only the declared counts, so
  the compiler rejects the excess instead of the budget silently growing.
- `::sources`: every line is `[n] URL`; `n` and the exact URL must equal one result of `options.research`
  after the `href` profile filter (`MBM_SOURCE_NOT_IN_RESEARCH`); **`allowedLinks` is not consulted**. Inline `[n]`
  references in prose stay literal text. Page contents are never fetched.
- Numeric limits come from native-v1 and are not restated here, except the MBM ones: source ≤256 KiB, table ≤12
  columns and ≤100 rows, audio text ≤600, image and video query or prompt ≤300, media ≤8.

### Handles and edit ranges

For an edit (`options.mode = EDIT`) the context shows each **top-level** block of the target range with a handle
`[[bN]]` and the compiler receives `options.handles`. The model keeps the handle at the start of a rewritten
block whose type is unchanged. For a handle that is in `options.handles`:

- block type equals the recorded type: the block keeps the recorded `nodeId` (descendants get new IDs);
- block type differs: a new ID is allocated and `MBM_HANDLE_TYPE_CHANGED` is a warning;
- a handle not in `options.handles` is `MBM_UNKNOWN_HANDLE`; a repeated one is `MBM_DUPLICATE_HANDLE`;
- **every declared handle must appear**: an omitted one is the error `MBM_EDIT_HANDLE_OMITTED` — the AI never
  deletes a block (a deletion is a user action). A type change still counts as present;
- **reordering** the handled blocks inside the range is allowed and each keeps its ID (`edit-range-reordered`).

A block without a handle is new. In a new document (`CREATE`) every handle is unknown. Handles are not persisted
and never appear in native-v1. Only top-level blocks have handles in v1: list items and table rows cannot keep IDs
through an edit.

### Link allowlist

Before compilation `options.allowedLinks` and `options.research` are filtered through the native-v1 `href`
profile ([lexical vectors](../../content/native-v1/lexical-vectors.json)): an entry that does not conform (for
example `http://`) is dropped with the warning `MBM_LINK_REJECTED_BY_PROFILE` (line 0). A link becomes a `link`
node only when its URL equals a remaining `allowedLinks` entry (string equality after lowercasing scheme and
host) and the emitted `href` is **the allowlist entry string**. Anything else stays text: the label is kept, with its
marks, the URL is dropped and `MBM_LINK_NOT_ALLOWED` is a **warning** (compilation succeeds). The compiler does not
repair URLs. The model never decides which URLs are trusted (OWASP LLM01/05). Fixtures: `link-allowed`,
`link-not-allowed`, `link-profile-rejected`, `source-url-rejected-by-profile`.

## Errors and warnings

[`codes.json`](codes.json) is the complete, machine-readable table. Fixture coverage is checked by
`GenerationContractFixtureTest`: every error code that is not `fixtureExempt` has an `invalid/` fixture.

| Code | Severity | Scope | Rule (the line shown by the repair prompt) |
|---|---|---|---|
| `MBM_EMPTY_DOCUMENT` | ERROR | document | The document contains no block. |
| `MBM_DOCUMENT_TOO_LARGE` | ERROR | document | The source is larger than 256 KiB, or the compiled document is rejected by a NativeDocumentReader size limit (1 MiB, 10,000 nodes, depth 32), or the source is too deeply nested or too complex to parse. No fixture: it needs a source over 256 KiB. |
| `MBM_HEADING_LEVEL` | ERROR | block | Headings are # to ### only; split the topic or use a paragraph. |
| `MBM_NESTED_LIST` | ERROR | block | Lists are one level; write nested items as separate paragraphs or a table. |
| `MBM_CODE_LANGUAGE_INVALID` | ERROR | block | The info string of a fenced code block is empty or one lowercase identifier such as sql, python or c++ (at most 32 characters of a-z, 0-9, + # . -); a diagram is ::mermaid, not ```mermaid. |
| `MBM_EMPTY_CODE_BLOCK` | ERROR | block | A fenced code block needs at least one nonblank line of code between its fences. |
| `MBM_UNTERMINATED_FENCE` | ERROR | block | Every opening fence needs a closing fence on its own line. |
| `MBM_NESTED_LINK` | ERROR | inline | A link label cannot contain another link. |
| `MBM_UNKNOWN_DIRECTIVE` | ERROR | directive | Only ::table, ::mermaid, ::audio, ::image, ::video and ::sources exist. |
| `MBM_UNKNOWN_ATTRIBUTE` | ERROR | directive | The directive does not define this attribute. |
| `MBM_MISSING_ATTRIBUTE` | ERROR | directive | A required attribute is missing or blank (alt, title, caption, description, slot, lang, mode). |
| `MBM_DUPLICATE_ATTRIBUTE` | ERROR | directive | An attribute may appear once. |
| `MBM_INVALID_ATTRIBUTE_VALUE` | ERROR | directive | The value is outside the allowed set or lexical profile (mode: search\|generate; voice: female\|male; lang: BCP 47 profile of native-v1). |
| `MBM_ATTRIBUTE_SYNTAX` | ERROR | directive | Attributes are {key="value" ...} with double-quoted values; escape \" and \\ only. |
| `MBM_DIRECTIVE_BODY_MISSING` | ERROR | directive | The directive needs its body: text after the brace (audio, image, video), a pipe table (table), a fenced source (mermaid) or [n] URL lines (sources). |
| `MBM_UNEXPECTED_DIRECTIVE_TEXT` | ERROR | directive | Text after the brace is allowed only for ::audio, ::image and ::video. |
| `MBM_VALUE_TOO_LONG` | ERROR | directive | An attribute or body exceeds the bound (alt 4096, title and caption 1024, description 8192, mermaid or code source 16384, table cell 4096, image or video query or prompt 300 UTF-16 code units). |
| `MBM_INVALID_SLOT_KEY` | ERROR | directive | A slot key matches `[a-z][a-z0-9_]{0,31}`. |
| `MBM_DUPLICATE_SLOT` | ERROR | directive | A slot key is unique in the document and among the slots outside an edited range. |
| `MBM_CAPABILITY_OFF` | ERROR | directive | The capability behind this directive or mode (videoGeneration, imageGeneration) is not enabled for the session. |
| `MBM_TABLE_MALFORMED` | ERROR | directive | A table is a header row, a separator row (\|---\|) with the same cell count, then body rows. |
| `MBM_TABLE_HEADER_BLANK` | ERROR | directive | Every column heading must be nonblank. |
| `MBM_TABLE_TOO_WIDE` | ERROR | directive | A table has at most 12 columns. |
| `MBM_TABLE_TOO_TALL` | ERROR | directive | A table has at most 100 body rows. |
| `MBM_TABLE_RAGGED` | ERROR | directive | Every body row has exactly as many cells as the header. |
| `MBM_SOURCE_NOT_IN_RESEARCH` | ERROR | directive | Every ::sources line is [n] URL where n and the exact URL belong to one result of the session research. |
| `MBM_UNKNOWN_HANDLE` | ERROR | block | A [[handle]] must be one of the handles given for the edited range; new documents have none. |
| `MBM_DUPLICATE_HANDLE` | ERROR | block | A handle may be used on one block only. |
| `MBM_TOO_MANY_MEDIA` | ERROR | directive | At most 8 media directives per artifact (the media counts declared in the spec cap it lower); the estimate prices only the declared counts. |
| `MBM_AUDIO_TEXT_TOO_LONG` | ERROR | directive | The text of ::audio is at most 600 characters; split it into several clips. |
| `MBM_EDIT_HANDLE_OMITTED` | ERROR | block | Every handle of the edited range must appear in the output; removing a block is not an AI action. Reordering is allowed. |
| `MBM_LINK_NOT_ALLOWED` | WARNING | inline | The link URL is not in the allowlist: the label stays as text and the URL is dropped. |
| `MBM_HANDLE_TYPE_CHANGED` | WARNING | block | The block with this handle changed type: a new node ID was allocated. |
| `MBM_LITERAL_DELIMITER` | WARNING | inline | A run of three or more asterisks, or an asterisk run that could open emphasis but has no closer, stays literal text. |
| `MBM_LINK_REJECTED_BY_PROFILE` | WARNING | document | An allowlist or research URL violates the native-v1 href profile and was dropped before compilation (reported at line 0). |

## Fixtures

| Path | Content |
|---|---|
| `valid/<case>.mbm` | model output |
| `valid/<case>.native.json` | expected native-v1 document (golden IDs) |
| `valid/<case>.meta.json` | `{description, input?, expected?}`; `input` is the `options` above, `expected` has `warnings[]` and `slots[]`. `valid/.gitattributes` keeps the CRLF fixture byte-exact |
| `invalid/<case>.mbm` | model output that must fail |
| `invalid/<case>.errors.json` | expected `[{line, column?, code, attribute?}]` in report order |
| `invalid/<case>.meta.json` | `{description, input?}` — options the case needs (capability off, research, handles) |

Every `*.native.json` is accepted by `NativeDocumentReader` (checked by `GenerationContractFixtureTest`). The test also checks structure: the number of media directive lines equals the slots, heading lines (plus the one `::sources` adds) equal heading nodes, and every error line is within the source.

Node coverage — every native-v1 and #76 node a compiler can emit appears in at least one fixture:

| Node | Fixtures |
|---|---|
| `doc`, `paragraph`, `text` (+ marks) | `paragraph-marks`, `literals`, `inline-intraword-punctuation`, `escapes`, `crlf` |
| `heading` 1–3 | `headings`, `ruby`, `sources` |
| `bullet_list`, `ordered_list`, `list_item` | `lists`, `loose-list`, `lesson-vocabulary`, `sources` |
| `blockquote`, `divider` | `blockquote-divider`, `lesson-vocabulary` |
| `ruby` | `ruby`, `ruby-in-link`, `lesson-vocabulary` |
| `link` | `link-allowed`, `link-not-allowed`, `link-profile-rejected`, `ruby-in-link`, `sources`, `lesson-vocabulary` |
| `table` | `table`, `edit-range` |
| `mermaid` | `mermaid` |
| `code_block` | `code-block` |
| `audio` | `audio`, `lesson-vocabulary` |
| `image` | `image-search`, `image-generate`, `lesson-vocabulary` |
| `video` | `video` (capability on) |
| `youtube` | not producible: MBM has no YouTube directive |

Directive coverage: `table`, `mermaid`, `audio`, `image` (`search` and `generate`), `video`, `sources` each
have a valid fixture and, where an attribute or body can be wrong, an invalid one. Edit behavior:
`edit-range`, `edit-range-reordered`, `edit-range-type-changed`, `unknown-handle`, `duplicate-handle`,
`edit-handle-omitted`, `duplicate-slot-existing`. Cost bounds: `too-many-media`, `audio-text-too-long`,
`image-query-too-long`.

## Planned extensions and open questions

- **`code_block`** is part of MBM v1 since [CONTENT-01 (#303)](https://github.com/MattoYuzuru/Mnema/issues/303): a fenced
  block outside `::mermaid` (fixtures `code-block`, `code-language-invalid`, `code-block-empty`). `math` stays outside MBM:
  a formula is written as text until a separate KaTeX/MathML decision (not scheduled).
- `::verify` (a block listing statements the model is unsure of), drafted in the context research, is
  **not** in v1; the system prompt does not mention it.
- Native → MBM serialization (the edit context) is specified only through the handle rule above. Nodes that
  MBM cannot express (`youtube`, opaque `math`, a retained `code_block` that is not valid v1, future versions) cannot be inside an AI
  edit target; AI-11 refuses such a target before any model call (`400 INVALID_REQUEST`, [decision 15](../README.md)). As neighbours they are
  shown in the edit context by their first line.
- Media blocks with handles: AI-11 never shows a media block to the model as a target (it is shown as a placeholder line in the outline) and
  never drops it in a rewrite, so no handle is declared for it; whether a media redo keeps the `assetId` when the spec is unchanged is a runtime
  rule for the media tasks (AI-09, AI-10), not fixed here.
- Handles for list items and table rows (exercise `MATERIAL` references cite blocks) are not in v1.
- Inline markup inside table cells is literal in v1 (cells are only trimmed).
- Column positions are code points; CJK width and grapheme clusters are not modeled.
