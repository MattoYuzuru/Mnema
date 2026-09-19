import assert from "node:assert/strict";
import test from "node:test";
import { Fragment, Slice } from "prosemirror-model";
import { joinBackward, splitBlock } from "prosemirror-commands";
import { history, redo, redoDepth, undo, undoDepth } from "prosemirror-history";
import { EditorState, TextSelection } from "prosemirror-state";
import {
  exportNativeDocument,
  freshenPastedSlice,
  identityNormalizationPlugin,
  importNativeDocument,
  isSafeHttpsUrl,
  mnemaSchema,
  moveTopLevelBlock,
  validateNativeDocument,
} from "../src/adapter.js";
import {
  createFixture,
  createFutureRootFixture,
  createOpaqueListFixture,
  createOptionalMetadataFixture,
  fixtureId,
  sharedLexicalVectors,
} from "../src/fixtures.js";

function stateFor(document) {
  const session = importNativeDocument(document);
  return EditorState.create({ doc: session.doc, plugins: [history(), identityNormalizationPlugin] });
}

function apply(state, transaction) {
  return state.applyTransaction(transaction).state;
}

function dispatchCommand(state, command) {
  let next = state;
  const handled = command(state, (transaction) => { next = apply(state, transaction); });
  assert.equal(handled, true);
  return next;
}

function collectIds(value, into = []) {
  if (value && typeof value === "object") {
    if (typeof value.id === "string") into.push(value.id);
    for (const child of value.content ?? []) collectIds(child, into);
  }
  return into;
}

function nativeTexts(document) {
  const output = [];
  function visit(node) {
    if (node.type === "text") output.push(node);
    node.content.forEach(visit);
  }
  visit(document.root);
  return output;
}

function findTextPosition(doc, id) {
  let found = null;
  doc.descendants((node, pos) => {
    if (!node.isText) return;
    const identity = node.marks.find((mark) => mark.type === mnemaSchema.marks.mnema_text_identity);
    if (identity?.attrs.id === id) found = pos;
  });
  assert.notEqual(found, null);
  return found;
}

function assertUniqueIds(document) {
  const ids = collectIds(document.root);
  assert.equal(new Set(ids).size, ids.length);
  ids.forEach((id) => assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i));
}

test("Mnema AST round-trips semantically and never exports private ProseMirror state", () => {
  const native = createFixture();
  const session = importNativeDocument(native);
  const exported = exportNativeDocument(session.doc);
  assert.deepEqual(exported, native);
  const serialized = JSON.stringify(exported);
  assert.doesNotMatch(serialized, /mnema_text_identity|nativeId|nativeVersion|selection|plugin|ProseMirror/i);
  assertUniqueIds(exported);
});

test("optional attributes, original spelling, mark order, and absent defaults round-trip exactly", () => {
  const native = createOptionalMetadataFixture();
  native.root.id = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA";
  let state = stateFor(native);
  assert.deepEqual(exportNativeDocument(state.doc), native);

  const start = findTextPosition(state.doc, fixtureId(802));
  state = apply(state, state.tr.addMark(start + 2, start + 6, mnemaSchema.marks.em.create()));
  const formatted = exportNativeDocument(state.doc);
  const runs = formatted.root.content[0].content;
  assert.deepEqual(runs.find((node) => node.attrs.text === "rk o").attrs.marks, ["code", "strong", "em"]);
  assert.equal(Object.hasOwn(formatted.root.content[1].attrs, "dir"), false);
  assert.equal(Object.hasOwn(formatted.root.content[3].attrs, "order"), false);
  assert.equal(Object.hasOwn(formatted.root.content[1].content[0].attrs, "marks"), false);
  assert.equal(formatted.root.id, native.root.id);
  assert.equal(formatted.root.content[1].content[1].attrs.href, "HTTPS://example.test/Case");
  const after = structuredClone(formatted);

  state = dispatchCommand(state, undo);
  assert.deepEqual(exportNativeDocument(state.doc), native);
  state = dispatchCommand(state, redo);
  assert.deepEqual(exportNativeDocument(state.doc), after);
});

test("unrepresentable native list slots stay whole-container opaque and copy losslessly with fresh IDs", () => {
  const native = createOpaqueListFixture();
  let state = stateFor(native);
  assert.equal(state.doc.childCount, 2);
  assert.equal(state.doc.child(0).type, mnemaSchema.nodes.unsupported_block);
  assert.equal(state.doc.child(1).type, mnemaSchema.nodes.unsupported_block);
  assert.deepEqual(exportNativeDocument(state.doc), native);

  const fresh = freshenPastedSlice(new Slice(Fragment.from(state.doc.firstChild), 0, 0));
  state = apply(state, state.tr.insert(state.doc.content.size, fresh.content));
  const exported = exportNativeDocument(state.doc);
  assert.deepEqual(exported.root.content.slice(0, 2), native.root.content);
  assert.deepEqual(exported.root.content[2].content[0].attrs, native.root.content[0].content[0].attrs);
  assert.notDeepEqual(collectIds(exported.root.content[2]), collectIds(native.root.content[0]));
  assertUniqueIds(exported);
});

test("formatting a substring splits runs without duplicate IDs; undo and redo remain visible-history operations", () => {
  const baseline = createFixture();
  let state = stateFor(baseline);
  const originalId = fixtureId(5);
  const start = findTextPosition(state.doc, originalId);
  state = apply(state, state.tr.addMark(start + 7, start + 20, mnemaSchema.marks.strong.create()));

  let exported = exportNativeDocument(state.doc);
  assertUniqueIds(exported);
  assert.equal(nativeTexts(exported).filter((node) => node.id === originalId).length, 1);
  assert.ok(nativeTexts(exported).some((node) => node.attrs.marks.includes("strong")));
  const idsAfterFormat = new Set(nativeTexts(exported).map((node) => node.id));
  const formatted = structuredClone(exported);
  assert.equal(undoDepth(state), 1, "normalization must be grouped into one visible history event");

  state = dispatchCommand(state, undo);
  exported = exportNativeDocument(state.doc);
  assertUniqueIds(exported);
  assert.equal(nativeTexts(exported).filter((node) => node.id === originalId).length, 1);
  assert.equal(nativeTexts(exported).some((node) => node.attrs.marks.includes("strong") && node.attrs.text.includes("русский")), false);
  assert.deepEqual(exported, baseline, "undo must restore the exact native AST, including text identities");
  assert.equal(undoDepth(state), 0);
  assert.equal(redoDepth(state), 1);

  state = dispatchCommand(state, redo);
  exported = exportNativeDocument(state.doc);
  assertUniqueIds(exported);
  assert.ok(nativeTexts(exported).some((node) => node.attrs.marks.includes("strong")));
  assert.deepEqual(new Set(nativeTexts(exported).map((node) => node.id)), idsAfterFormat);
  assert.deepEqual(exported, formatted, "redo must restore the exact formatted AST and generated IDs");
  assert.equal(undoDepth(state), 1);
});

test("split normalization is part of the same history event and round-trips exact IDs", () => {
  const fixture = createFixture();
  fixture.root.content = [fixture.root.content[1]];
  let state = stateFor(fixture);
  const originalParagraph = fixtureId(4);
  const start = findTextPosition(state.doc, fixtureId(5));
  state = apply(state, state.tr.setSelection(TextSelection.create(state.doc, start + 14)));
  state = dispatchCommand(state, splitBlock);
  let exported = exportNativeDocument(state.doc);
  assert.equal(exported.root.content.length, 2);
  assert.equal(exported.root.content.filter((node) => node.id === originalParagraph).length, 1);
  assertUniqueIds(exported);
  const split = structuredClone(exported);
  assert.equal(undoDepth(state), 1);

  state = dispatchCommand(state, undo);
  assert.deepEqual(exportNativeDocument(state.doc), fixture);
  state = dispatchCommand(state, redo);
  assert.deepEqual(exportNativeDocument(state.doc), split);
});

test("join undo and redo restore the exact before/after native identities", () => {
  const fixture = createFixture();
  fixture.root.content = [fixture.root.content[1], fixture.root.content[2]];
  let state = stateFor(fixture);
  const originalParagraph = fixtureId(4);

  const firstSize = state.doc.child(0).nodeSize;
  state = apply(state, state.tr.setSelection(TextSelection.create(state.doc, firstSize + 1)));
  state = dispatchCommand(state, joinBackward);
  let exported = exportNativeDocument(state.doc);
  assert.equal(exported.root.content.length, 1);
  assert.equal(exported.root.content[0].id, originalParagraph);
  assertUniqueIds(exported);
  const joined = structuredClone(exported);

  state = dispatchCommand(state, undo);
  assert.deepEqual(exportNativeDocument(state.doc), fixture);
  state = dispatchCommand(state, redo);
  assert.deepEqual(exportNativeDocument(state.doc), joined);
});

test("top-level move retains every identity and unknown payload", () => {
  const fixture = createFixture();
  let state = stateFor(fixture);
  const beforeIds = collectIds(exportNativeDocument(state.doc).root).sort();
  state = apply(state, moveTopLevelBlock(state, 5, 0));
  const exported = exportNativeDocument(state.doc);
  assert.equal(exported.root.content[0].id, fixtureId(19));
  assert.deepEqual(collectIds(exported.root).sort(), beforeIds);
  assert.deepEqual(exported.root.content[0], fixture.root.content[5]);
  const moved = structuredClone(exported);
  state = dispatchCommand(state, undo);
  assert.deepEqual(exportNativeDocument(state.doc), fixture);
  state = dispatchCommand(state, redo);
  assert.deepEqual(exportNativeDocument(state.doc), moved);
});

test("paste/copy freshens IDs recursively, including opaque unknown subtrees", () => {
  const fixture = createFixture();
  let state = stateFor(fixture);
  const unknown = state.doc.child(5);
  const fresh = freshenPastedSlice(new Slice(Fragment.from(unknown), 0, 0));
  const copied = fresh.content.firstChild;
  assert.notEqual(copied.attrs.nativeId, unknown.attrs.nativeId);
  assert.notDeepEqual(collectIds(copied.attrs.payload), collectIds(unknown.attrs.payload));

  state = apply(state, state.tr.insert(state.doc.content.size, fresh.content));
  const exported = exportNativeDocument(state.doc);
  assert.equal(exported.root.content.filter((node) => node.type === "table").length, 2);
  assertUniqueIds(exported);
  const pasted = structuredClone(exported);
  state = dispatchCommand(state, undo);
  assert.deepEqual(exportNativeDocument(state.doc), fixture);
  state = dispatchCommand(state, redo);
  assert.deepEqual(exportNativeDocument(state.doc), pasted);
});

test("copy assigns one fresh native link identity across all of its text runs", () => {
  const fixture = createFixture();
  let state = stateFor(fixture);
  const linkedParagraph = state.doc.child(2);
  const fresh = freshenPastedSlice(new Slice(Fragment.from(linkedParagraph), 0, 0));
  state = apply(state, state.tr.insert(state.doc.content.size, fresh.content));
  const exported = exportNativeDocument(state.doc);
  const originalLink = exported.root.content[2].content[1];
  const copiedLink = exported.root.content.at(-1).content[1];
  assert.equal(copiedLink.type, "link");
  assert.equal(copiedLink.content.length, 2);
  assert.notEqual(copiedLink.id, originalLink.id);
  assertUniqueIds(exported);
});

test("external HTTPS link marks are normalized and paste freshening supplies native identities", () => {
  const href = "https://example.test/external";
  const externalLink = mnemaSchema.marks.link.create({ nativeId: null, nativeVersion: 1, href });
  const externalParagraph = mnemaSchema.nodes.paragraph.create(
    { nativeId: null, nativeVersion: 1, lang: undefined, dir: "auto" },
    mnemaSchema.text("external safe link", [externalLink]),
  );
  let state = EditorState.create({
    doc: mnemaSchema.nodes.doc.create(
      { nativeId: fixtureId(950), nativeVersion: 1 },
      externalParagraph,
    ),
    plugins: [history(), identityNormalizationPlugin],
  });

  state = apply(state, state.tr.insertText("!", 2));
  let exported = exportNativeDocument(state.doc);
  assert.equal(exported.root.content[0].content[0].type, "link");
  assert.equal(exported.root.content[0].content[0].attrs.href, href);
  assertUniqueIds(exported);

  const fresh = freshenPastedSlice(new Slice(Fragment.from(externalParagraph), 0, 0));
  state = stateFor(createFixture());
  state = apply(state, state.tr.insert(state.doc.content.size, fresh.content));
  exported = exportNativeDocument(state.doc);
  const pastedLink = exported.root.content.at(-1).content[0];
  assert.equal(pastedLink.type, "link");
  assert.equal(pastedLink.attrs.href, href);
  assertUniqueIds(exported);
});

test("splitting linked content keeps the first link ID and undo/redo restores exact native ASTs", () => {
  const fixture = createFixture();
  fixture.root.content = [fixture.root.content[2]];
  const originalLinkId = fixture.root.content[0].content[1].id;
  const linkedTextId = fixture.root.content[0].content[1].content[0].id;
  let state = stateFor(fixture);
  const start = findTextPosition(state.doc, linkedTextId);
  state = apply(state, state.tr.setSelection(TextSelection.create(state.doc, start + 5)));
  state = dispatchCommand(state, splitBlock);

  let exported = exportNativeDocument(state.doc);
  assert.equal(exported.root.content.length, 2);
  const links = exported.root.content.map((paragraph) => paragraph.content.find((node) => node.type === "link"));
  assert.equal(links[0].id, originalLinkId);
  assert.notEqual(links[1].id, originalLinkId);
  assertUniqueIds(exported);
  const split = structuredClone(exported);
  assert.equal(undoDepth(state), 1);

  state = dispatchCommand(state, undo);
  assert.deepEqual(exportNativeDocument(state.doc), fixture);
  state = dispatchCommand(state, redo);
  assert.deepEqual(exportNativeDocument(state.doc), split);
});

test("links preserve mixed text, ruby, and opaque children and copied atoms receive the fresh link mark", () => {
  const fixture = createFixture();
  const paragraph = fixture.root.content[1];
  const originalLink = {
    id: fixtureId(960), type: "link", version: 1, attrs: { href: "https://example.test/mixed" },
    content: [
      paragraph.content[0],
      { id: fixtureId(961), type: "ruby", version: 1, attrs: { base: "学", reading: "がく" }, content: [] },
      { id: fixtureId(962), type: "future_inline", version: 3, attrs: { source: "inert" }, content: [
        { id: fixtureId(963), type: "future_leaf", version: 1, attrs: { value: 7 }, content: [] },
      ] },
    ],
  };
  paragraph.content = [originalLink];
  fixture.root.content = [paragraph];

  let state = stateFor(fixture);
  assert.deepEqual(exportNativeDocument(state.doc), fixture);
  const fresh = freshenPastedSlice(new Slice(Fragment.from(state.doc.firstChild), 0, 0));
  const copiedParagraph = fresh.content.firstChild;
  for (const atom of [copiedParagraph.child(1), copiedParagraph.child(2)]) {
    const copiedMark = atom.marks.find((mark) => mark.type === mnemaSchema.marks.link);
    assert.ok(copiedMark);
    assert.notEqual(copiedMark.attrs.nativeId, originalLink.id);
  }

  state = apply(state, state.tr.insert(state.doc.content.size, fresh.content));
  const exported = exportNativeDocument(state.doc);
  const copiedLink = exported.root.content[1].content[0];
  assert.equal(copiedLink.type, "link");
  assert.deepEqual(copiedLink.content.map((node) => node.type), ["text", "ruby", "future_inline"]);
  assert.notEqual(copiedLink.id, originalLink.id);
  assert.notEqual(copiedLink.content[1].id, originalLink.content[1].id);
  assert.notDeepEqual(collectIds(copiedLink.content[2]), collectIds(originalLink.content[2]));
  assertUniqueIds(exported);
});

test("unknown types and future known versions survive an unrelated edit plus undo/redo", () => {
  const fixture = createFixture();
  let state = stateFor(fixture);
  const unknownInline = structuredClone(fixture.root.content[3].content[1]);
  const futureHeading = structuredClone(fixture.root.content[4]);
  const unknownTable = structuredClone(fixture.root.content[5]);
  const position = findTextPosition(state.doc, fixtureId(5));
  state = apply(state, state.tr.insertText("НОВОЕ ", position));
  for (const command of [undo, redo]) state = dispatchCommand(state, command);
  const exported = exportNativeDocument(state.doc);
  assert.deepEqual(exported.root.content[3].content.find((node) => node.id === fixtureId(15)), unknownInline);
  assert.deepEqual(exported.root.content.find((node) => node.id === fixtureId(17)), futureHeading);
  assert.deepEqual(exported.root.content.find((node) => node.id === fixtureId(19)), unknownTable);
});

test("future root opens read-only and exports the untouched native document", () => {
  const fixture = createFutureRootFixture();
  const session = importNativeDocument(fixture);
  assert.equal(session.readOnly, true);
  assert.equal(session.doc.childCount, 1);
  assert.equal(session.doc.firstChild.type, mnemaSchema.nodes.unsupported_block);
  assert.deepEqual(exportNativeDocument(session), fixture);
});

test("validation rejects duplicate IDs, unsafe links, invalid direction, and oversized scalars", () => {
  const duplicate = createFixture();
  duplicate.root.content[0].id = duplicate.root.id;
  assert.throws(() => validateNativeDocument(duplicate), /duplicate UUID/);

  const unsafeLink = createFixture();
  unsafeLink.root.content[2].content[1].attrs.href = "javascript:globalThis.pwned=1";
  assert.throws(() => validateNativeDocument(unsafeLink), /only https links/);

  const invalidDirection = createFixture();
  invalidDirection.root.content[1].attrs.dir = "sideways";
  assert.throws(() => validateNativeDocument(invalidDirection), /direction/);

  const executableKnownAttr = createFixture();
  executableKnownAttr.root.content[1].attrs.onclick = "globalThis.pwned=1";
  assert.throws(() => validateNativeDocument(executableKnownAttr), /unsupported attributes onclick/);

  const oversized = createFixture();
  oversized.root.content[1].content[0].attrs.text = "я".repeat(17_000);
  assert.throws(() => validateNativeDocument(oversized), /scalar exceeds/);

  assert.equal(isSafeHttpsUrl("https://example.test/path"), true);
  assert.equal(isSafeHttpsUrl("https://user:pass@example.test/path"), false);
  assert.equal(isSafeHttpsUrl("data:text/html,<script>1</script>"), false);
  assert.equal(isSafeHttpsUrl("//example.test/path"), false);
});

test("validation matches frozen native-v1 lexical vectors", () => {
  const withLanguage = (lang) => ({
    formatVersion: 1,
    root: {
      id: fixtureId(980), type: "doc", version: 1, attrs: {},
      content: [{ id: fixtureId(981), type: "paragraph", version: 1, attrs: { lang }, content: [] }],
    },
  });
  for (const lang of sharedLexicalVectors.lang.accept) assert.doesNotThrow(() => validateNativeDocument(withLanguage(lang)), lang);
  for (const lang of sharedLexicalVectors.lang.reject) assert.throws(() => validateNativeDocument(withLanguage(lang)), undefined, lang);
  for (const href of sharedLexicalVectors.href.accept) assert.equal(isSafeHttpsUrl(href), true, href);
  for (const href of sharedLexicalVectors.href.reject) assert.equal(isSafeHttpsUrl(href), false, href);
});

test("validation enforces host grammar and semantic UUID uniqueness while preserving opaque extensions", () => {
  const duplicateCase = createOptionalMetadataFixture();
  duplicateCase.root.id = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA";
  duplicateCase.root.content[0].id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  assert.throws(() => validateNativeDocument(duplicateCase), /duplicate UUID/);

  const extraEnvelope = createOptionalMetadataFixture();
  extraEnvelope.runtime = {};
  assert.throws(() => validateNativeDocument(extraEnvelope), /formatVersion 1/);

  const extraKnownField = createOptionalMetadataFixture();
  extraKnownField.root.content[0].extension = true;
  assert.throws(() => validateNativeDocument(extraKnownField), /exactly the native core fields/);

  const futureList = createOpaqueListFixture();
  assert.doesNotThrow(() => validateNativeDocument(futureList));
  assert.equal(validateNativeDocument(futureList).root.content[1].content[0].content[0].extension.preserved, true);

  const emptyList = createOptionalMetadataFixture();
  emptyList.root.content[3].content = [];
  assert.throws(() => validateNativeDocument(emptyList), /requires content/);

  const misplacedItem = createOptionalMetadataFixture();
  misplacedItem.root.content = [misplacedItem.root.content[3].content[0]];
  assert.throws(() => validateNativeDocument(misplacedItem), /only allowed in a list/);

  const nestedLink = createOptionalMetadataFixture();
  const outer = nestedLink.root.content[1].content[1];
  outer.content = [{ ...structuredClone(outer), id: fixtureId(990) }];
  assert.throws(() => validateNativeDocument(nestedLink), /nested links/);

  const tooLargeVersion = createOptionalMetadataFixture();
  tooLargeVersion.root.content[0].version = 2_147_483_648;
  assert.throws(() => validateNativeDocument(tooLargeVersion), /32-bit/);

  const absentMarks = createOptionalMetadataFixture();
  assert.equal(Object.hasOwn(absentMarks.root.content[1].content[0].attrs, "marks"), false);
  assert.doesNotThrow(() => validateNativeDocument(absentMarks));

  const nullMarks = createOptionalMetadataFixture();
  nullMarks.root.content[0].content[0].attrs.marks = null;
  assert.throws(() => validateNativeDocument(nullMarks), /unsupported or duplicate mark/);

  const maximumOrder = createOptionalMetadataFixture();
  maximumOrder.root.content[3].attrs.order = 2_147_483_647;
  assert.doesNotThrow(() => validateNativeDocument(maximumOrder));
  assert.equal(exportNativeDocument(importNativeDocument(maximumOrder).doc).root.content[3].attrs.order, 2_147_483_647);

  const overflowOrder = createOptionalMetadataFixture();
  overflowOrder.root.content[3].attrs.order = 2_147_483_648;
  assert.throws(() => validateNativeDocument(overflowOrder), /positive 32-bit integer/);

  const deepOpaque = createOpaqueListFixture();
  let nested = deepOpaque.root.content[0].content[0].attrs;
  for (let index = 0; index < 130; index += 1) nested = nested.next = {};
  assert.throws(() => validateNativeDocument(deepOpaque), /JSON depth 128/);

  const oversizedKey = createOpaqueListFixture();
  oversizedKey.root.content[0].content[0].attrs["я".repeat(17_000)] = true;
  assert.throws(() => validateNativeDocument(oversizedKey), /property name exceeds/);
});

test("long Russian + RTL/LTR + ruby fixture remains within the bounded adapter contract", () => {
  const fixture = createFixture({ long: true });
  const before = performance.now();
  const session = importNativeDocument(fixture);
  const exported = exportNativeDocument(session.doc);
  const elapsedMs = performance.now() - before;
  assert.deepEqual(exported, fixture);
  assert.match(JSON.stringify(exported), /Память|العِلْمُ|漢字|かんじ/);
  assert.ok(exported.root.content.length > 180);
  assertUniqueIds(exported);
  assert.ok(Number.isFinite(elapsedMs));
});
