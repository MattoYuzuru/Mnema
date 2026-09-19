import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
import { createOpaqueListFixture, createOptionalMetadataFixture } from "./src/fixtures.js";

const playwrightPath = process.env.PLAYWRIGHT_MODULE;
if (!playwrightPath) throw new Error("Set PLAYWRIGHT_MODULE to an existing Playwright index.mjs");
const { chromium } = await import(pathToFileURL(playwrightPath));
const baseUrl = process.env.PROTOTYPE_URL ?? "http://127.0.0.1:4174";
const chromePath = process.env.CHROME_PATH ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const browser = await chromium.launch({ executablePath: chromePath, headless: true });
const evidence = {
  timestamp: new Date().toISOString(),
  runnerRuntime: process.version,
  browserVersion: browser.version(),
  desktop: {},
  mobileEmulation: {},
  limitations: [
    "Synthetic composition events are not a real Japanese OS IME session.",
    "Mobile evidence uses Chrome desktop device emulation, not physical Android/iOS hardware or a touch keyboard.",
    "No screen reader, Safari, Firefox, Angular integration, or acknowledged server draft was exercised.",
    "Arbitrary IDNA A-label parity with Java 21 IDN is advisory in this dependency-free browser adapter; the frozen shared vectors pass and the server remains publication authority.",
  ],
};

function observe(page, bucket) {
  bucket.consoleErrors = [];
  bucket.pageErrors = [];
  bucket.failedRequests = [];
  bucket.externalRequests = [];
  page.on("console", (message) => {
    if (message.type() === "error" || message.type() === "warning") bucket.consoleErrors.push(`${message.type()}: ${message.text()}`);
  });
  page.on("pageerror", (error) => bucket.pageErrors.push(error.message));
  page.on("requestfailed", (request) => bucket.failedRequests.push(`${request.url()} :: ${request.failure()?.errorText}`));
  page.on("request", (request) => {
    if (!request.url().startsWith(baseUrl)) bucket.externalRequests.push(request.url());
  });
}

function assertClean(bucket) {
  assert.deepEqual(bucket.consoleErrors, []);
  assert.deepEqual(bucket.pageErrors, []);
  assert.deepEqual(bucket.failedRequests, []);
  assert.deepEqual(bucket.externalRequests, []);
}

const desktopContext = await browser.newContext({
  viewport: { width: 1440, height: 1000 },
  permissions: ["clipboard-read", "clipboard-write"],
  reducedMotion: "reduce",
});
const desktop = await desktopContext.newPage();
observe(desktop, evidence.desktop);
await desktop.goto(baseUrl, { waitUntil: "networkidle" });
await desktop.locator(".ProseMirror").waitFor();

evidence.desktop.initial = await desktop.evaluate(() => ({
  h1: document.querySelectorAll("h1").length,
  editorLabel: document.querySelector(".ProseMirror").getAttribute("aria-label"),
  editable: document.querySelector(".ProseMirror").getAttribute("contenteditable"),
  ruby: document.querySelectorAll("ruby").length,
  rtl: document.querySelectorAll("[dir=rtl]").length,
  unsupported: document.querySelectorAll(".unsupported").length,
  scrollWidth: document.documentElement.scrollWidth,
  clientWidth: document.documentElement.clientWidth,
  leakedPayload: document.querySelector(".workspace").textContent.includes("onerror=globalThis.pwned") || document.querySelector(".workspace").innerHTML.includes("globalThis.pwned"),
  nativeJsonContainsPrivateState: /mnema_text_identity|nativeId|nativeVersion|selection|plugin|ProseMirror/i.test(document.querySelector("#native-output").textContent),
}));
assert.equal(evidence.desktop.initial.h1, 1);
assert.equal(evidence.desktop.initial.editorLabel, "Редактор материала");
assert.equal(evidence.desktop.initial.editable, "true");
assert.ok(evidence.desktop.initial.ruby >= 2);
assert.ok(evidence.desktop.initial.rtl >= 2);
assert.ok(evidence.desktop.initial.unsupported >= 4);
assert.equal(evidence.desktop.initial.scrollWidth, evidence.desktop.initial.clientWidth);
assert.equal(evidence.desktop.initial.leakedPayload, false);
assert.equal(evidence.desktop.initial.nativeJsonContainsPrivateState, false);

evidence.desktop.sanitizedHtml = await desktop.evaluate(() => window.mnemaPrototype.sanitizePastedHtml(`
  <p style="position:fixed" onclick="globalThis.pwned=1">Безопасная <strong>вставка</strong></p>
  <a href="javascript:globalThis.pwned=2">плохая ссылка</a>
  <a href="https://example.test/good" onmouseover="globalThis.pwned=3">хорошая ссылка</a>
  <script>globalThis.pwned=4</script><svg onload="globalThis.pwned=5"><text>svg</text></svg>
  <iframe src="https://example.test/"></iframe><form><input autofocus></form><img src=x onerror="globalThis.pwned=6">
`));
assert.match(evidence.desktop.sanitizedHtml, /Безопасная <strong>вставка<\/strong>/);
assert.match(evidence.desktop.sanitizedHtml, /https:\/\/example\.test\/good/);
assert.doesNotMatch(evidence.desktop.sanitizedHtml, /script|svg|iframe|form|img|onerror|onclick|onmouseover|javascript:/i);

evidence.desktop.sanitizedOrderedListBounds = await desktop.evaluate(() => ({
  overflow: window.mnemaPrototype.sanitizePastedHtml('<ol start="2147483648"><li>overflow</li></ol>'),
  maximum: window.mnemaPrototype.sanitizePastedHtml('<ol start="2147483647"><li>maximum</li></ol>'),
  zero: window.mnemaPrototype.sanitizePastedHtml('<ol start="0"><li>zero</li></ol>'),
}));
assert.doesNotMatch(evidence.desktop.sanitizedOrderedListBounds.overflow, /start=/);
assert.match(evidence.desktop.sanitizedOrderedListBounds.maximum, /start="2147483647"/);
assert.doesNotMatch(evidence.desktop.sanitizedOrderedListBounds.zero, /start=/);

const beforeSafeHtmlPaste = await desktop.evaluate(() => window.mnemaPrototype.export());
await desktop.evaluate(() => {
  globalThis.pwned = 0;
  const editor = document.querySelector(".ProseMirror");
  editor.focus();
  const paragraph = editor.querySelector("p");
  const selection = getSelection();
  const range = document.createRange();
  range.selectNodeContents(paragraph);
  range.collapse(false);
  selection.removeAllRanges();
  selection.addRange(range);
  const transfer = new DataTransfer();
  transfer.setData("text/html", `<p onclick="globalThis.pwned=1">HTML-БЕЗОПАСНО <strong>жирно</strong> <a href="https://example.test/pasted" onclick="globalThis.pwned=4">безопасная ссылка</a><img src=x onerror="globalThis.pwned=2"><script>globalThis.pwned=3</script></p>`);
  transfer.setData("text/plain", "HTML-БЕЗОПАСНО жирно безопасная ссылка");
  editor.dispatchEvent(new ClipboardEvent("paste", { clipboardData: transfer, bubbles: true, cancelable: true }));
});
await desktop.waitForTimeout(100);
const afterSafeHtmlPaste = await desktop.evaluate(() => window.mnemaPrototype.export());
evidence.desktop.syntheticHtmlPaste = await desktop.evaluate(() => {
  const native = window.mnemaPrototype.export();
  let pastedLink = null;
  const visit = (node) => {
    if (node.type === "link" && node.attrs.href === "https://example.test/pasted") pastedLink = node;
    node.content.forEach(visit);
  };
  visit(native.root);
  return {
    nativeIncludesText: JSON.stringify(native).includes("HTML-БЕЗОПАСНО"),
    executed: globalThis.pwned,
    unsafeDom: /script|img|onerror|onclick/i.test(document.querySelector(".ProseMirror").innerHTML),
    safeLink: pastedLink && { id: pastedLink.id, href: pastedLink.attrs.href, text: pastedLink.content.map((node) => node.attrs.text).join("") },
  };
});
assert.equal(evidence.desktop.syntheticHtmlPaste.nativeIncludesText, true);
assert.equal(evidence.desktop.syntheticHtmlPaste.executed, 0);
assert.equal(evidence.desktop.syntheticHtmlPaste.unsafeDom, false);
assert.match(evidence.desktop.syntheticHtmlPaste.safeLink.id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
assert.deepEqual(
  { href: evidence.desktop.syntheticHtmlPaste.safeLink.href, text: evidence.desktop.syntheticHtmlPaste.safeLink.text },
  { href: "https://example.test/pasted", text: "безопасная ссылка" },
);
await desktop.keyboard.press("Meta+z");
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), beforeSafeHtmlPaste);
await desktop.keyboard.press("Meta+Shift+z");
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), afterSafeHtmlPaste);
evidence.desktop.syntheticHtmlPaste.undoRedo = "pass; undo restored exact prior AST and redo restored the fresh pasted link UUID";

const plainToken = " PLAIN-CLIPBOARD-742E ";
await desktop.waitForTimeout(600);
const beforePlainPaste = await desktop.evaluate(() => window.mnemaPrototype.export());
await desktop.evaluate(async (value) => navigator.clipboard.writeText(value), plainToken);
await desktop.locator(".ProseMirror").focus();
await desktop.keyboard.press("Meta+End");
await desktop.keyboard.press("Meta+V");
await desktop.waitForTimeout(100);
assert.equal(await desktop.evaluate((value) => JSON.stringify(window.mnemaPrototype.export()).includes(value.trim()), plainToken), true);
const afterPlainPaste = await desktop.evaluate(() => window.mnemaPrototype.export());
await desktop.keyboard.press("Meta+z");
assert.equal(await desktop.evaluate((value) => JSON.stringify(window.mnemaPrototype.export()).includes(value.trim()), plainToken), false);
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), beforePlainPaste);
await desktop.keyboard.press("Meta+Shift+z");
assert.equal(await desktop.evaluate((value) => JSON.stringify(window.mnemaPrototype.export()).includes(value.trim()), plainToken), true);
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), afterPlainPaste);
evidence.desktop.realPlainTextPasteUndoRedo = "pass; undo restored exact prior AST and redo restored exact pasted IDs";

const compositionDuring = await desktop.evaluate(() => {
  const editor = document.querySelector(".ProseMirror");
  editor.focus();
  editor.dispatchEvent(new CompositionEvent("compositionstart", { data: "か", bubbles: true }));
  return window.mnemaPrototype.composing;
});
await desktop.keyboard.insertText("かな");
await desktop.evaluate(() => document.querySelector(".ProseMirror").dispatchEvent(new CompositionEvent("compositionend", { data: "かな", bubbles: true })));
await desktop.waitForTimeout(100);
evidence.desktop.syntheticComposition = {
  viewReportedComposing: compositionDuring,
  nativeIncludesText: await desktop.evaluate(() => JSON.stringify(window.mnemaPrototype.export()).includes("かな")),
  classification: "synthetic event + browser insertText; not real IME evidence",
};
assert.equal(evidence.desktop.syntheticComposition.nativeIncludesText, true);

await desktop.evaluate(() => window.mnemaPrototype.select(2, 12));
await desktop.getByRole("button", { name: "Полужирный" }).click();

evidence.desktop.identityNormalization = await desktop.evaluate(() => {
  const ids = [];
  const visit = (node) => { ids.push(node.id); node.content.forEach(visit); };
  visit(window.mnemaPrototype.export().root);
  const appended = window.mnemaPrototype.transactions.flatMap((entry) => entry.appended);
  return {
    ids: ids.length,
    uniqueIds: new Set(ids).size,
    groupedHistoryNormalizations: appended.filter((entry) => entry.identityNormalization && entry.addToHistory !== false).length,
  };
});
assert.equal(evidence.desktop.identityNormalization.ids, evidence.desktop.identityNormalization.uniqueIds);
assert.ok(evidence.desktop.identityNormalization.groupedHistoryNormalizations > 0);

const linkedSplit = await desktop.evaluate(() => {
  const native = {
    formatVersion: 1,
    root: {
      id: "00000000-0000-4000-8000-000000000970", type: "doc", version: 1, attrs: {},
      content: [{
        id: "00000000-0000-4000-8000-000000000971", type: "paragraph", version: 1, attrs: { lang: "en", dir: "ltr" },
        content: [{
          id: "00000000-0000-4000-8000-000000000972", type: "link", version: 1, attrs: { href: "https://example.test/split" },
          content: [{ id: "00000000-0000-4000-8000-000000000973", type: "text", version: 1, attrs: { text: "abcdef", marks: [] }, content: [] }],
        }],
      }],
    },
  };
  window.mnemaPrototype.load(native);
  const view = window.mnemaPrototype.view;
  view.dispatch(view.state.tr.split(4));
  return { before: native, after: window.mnemaPrototype.export() };
});
const splitLinks = linkedSplit.after.root.content.map((node) => node.content[0]);
assert.equal(splitLinks[0].id, "00000000-0000-4000-8000-000000000972");
assert.notEqual(splitLinks[1].id, splitLinks[0].id);
await desktop.getByRole("button", { name: "Отменить" }).click();
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), linkedSplit.before);
await desktop.getByRole("button", { name: "Повторить" }).click();
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), linkedSplit.after);
evidence.desktop.linkedSplitUndoRedo = {
  firstLinkRetained: splitLinks[0].id,
  secondLinkFresh: splitLinks[1].id,
  exactUndoRedo: true,
};

const optionalMetadata = createOptionalMetadataFixture();
await desktop.evaluate((native) => window.mnemaPrototype.load(native), optionalMetadata);
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), optionalMetadata);
const optionalAfterEdit = await desktop.evaluate(() => {
  const view = window.mnemaPrototype.view;
  view.dispatch(view.state.tr.insertText("!", 3));
  return window.mnemaPrototype.export();
});
assert.equal(optionalAfterEdit.root.attrs.lang, "RU-ru");
assert.equal(optionalAfterEdit.root.content[0].content[0].attrs.lang, "sh");
assert.deepEqual(optionalAfterEdit.root.content[0].content[0].attrs.marks, ["code", "strong"]);
assert.equal(Object.hasOwn(optionalAfterEdit.root.content[1].attrs, "dir"), false);
assert.equal(Object.hasOwn(optionalAfterEdit.root.content[3].attrs, "order"), false);
assert.equal(optionalAfterEdit.root.content[1].content[1].attrs.href, "HTTPS://example.test/Case");
await desktop.getByRole("button", { name: "Отменить" }).click();
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), optionalMetadata);
await desktop.getByRole("button", { name: "Повторить" }).click();
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), optionalAfterEdit);
evidence.desktop.optionalMetadataRoundTrip = {
  exactInitial: true,
  exactUndoRedo: true,
  rootLanguageSpelling: optionalAfterEdit.root.attrs.lang,
  originalMarkOrder: optionalAfterEdit.root.content[0].content[0].attrs.marks,
  absentDefaultsStayedAbsent: true,
};

const opaqueLists = createOpaqueListFixture();
await desktop.evaluate((native) => window.mnemaPrototype.load(native), opaqueLists);
assert.deepEqual(await desktop.evaluate(() => window.mnemaPrototype.export()), opaqueLists);
assert.equal(await desktop.locator(".ProseMirror > .unsupported").count(), 2);
evidence.desktop.opaqueListFallback = {
  exactRoundTrip: true,
  wholeContainerPlaceholders: 2,
  futureDirectListChildInert: true,
  futureFirstParagraphInert: true,
};

const externalListHost = {
  formatVersion: 1,
  root: {
    id: "00000000-0000-4000-8000-000000000995", type: "doc", version: 1, attrs: {},
    content: [{ id: "00000000-0000-4000-8000-000000000996", type: "paragraph", version: 1, attrs: {}, content: [] }],
  },
};
async function parseExternalOrderedList(start) {
  return desktop.evaluate(async ({ native, listStart }) => {
    window.mnemaPrototype.load(native);
    const { DOMParser: ProseMirrorDomParser } = await import("/node_modules/prosemirror-model/dist/index.js");
    const safeContainer = document.createElement("div");
    safeContainer.innerHTML = window.mnemaPrototype.sanitizePastedHtml(
      `<ol start="${listStart}"><li>external order</li></ol>`,
    );
    const view = window.mnemaPrototype.view;
    const parsed = ProseMirrorDomParser.fromSchema(view.state.schema).parse(safeContainer);
    view.dispatch(view.state.tr.replaceWith(0, view.state.doc.content.size, parsed.content));
    return window.mnemaPrototype.export();
  }, { native: externalListHost, listStart: start });
}

const overflowListPaste = await parseExternalOrderedList("2147483648");
const overflowList = overflowListPaste.root.content.find((node) => node.type === "ordered_list");
assert.ok(overflowList);
assert.equal(Object.hasOwn(overflowList.attrs, "order"), false);
const maximumListPaste = await parseExternalOrderedList("2147483647");
const maximumList = maximumListPaste.root.content.find((node) => node.type === "ordered_list");
assert.ok(maximumList);
assert.equal(maximumList.attrs.order, 2_147_483_647);
evidence.desktop.externalOrderedListBounds = {
  pipeline: "sanitizer -> ProseMirror DOM parser -> identity normalization -> native export",
  overflowNormalizedToAbsentDefault: true,
  positiveInt32MaximumPreserved: maximumList.attrs.order,
};

await desktop.evaluate(() => window.mnemaPrototype.loadFutureRoot());
evidence.desktop.futureRoot = await desktop.evaluate(() => ({
  readOnly: window.mnemaPrototype.readOnly,
  editable: document.querySelector(".ProseMirror").getAttribute("contenteditable"),
  opaqueCount: document.querySelectorAll(".ProseMirror .unsupported").length,
  outputHasFuturePayload: document.querySelector("#native-output").textContent.includes("future_canvas"),
  editorLeaksFuturePayload: document.querySelector(".ProseMirror").textContent.includes("commands") || document.querySelector(".ProseMirror").innerHTML.includes("do-not-run"),
  executed: globalThis.pwned,
}));
assert.deepEqual(evidence.desktop.futureRoot, { readOnly: true, editable: "false", opaqueCount: 1, outputHasFuturePayload: true, editorLeaksFuturePayload: false, executed: 0 });

await desktop.evaluate(() => window.mnemaPrototype.loadFixture({ long: true }));
await desktop.screenshot({ path: "screenshots/editor-1440.png" });
const beforeDestroy = await desktop.locator("#native-output").textContent();
await desktop.evaluate(() => window.mnemaPrototype.destroy());
await desktop.locator("#editor-surface").dispatchEvent("input");
evidence.desktop.teardown = {
  destroyed: await desktop.evaluate(() => window.mnemaPrototype.destroyed),
  staleOutputMutation: beforeDestroy !== await desktop.locator("#native-output").textContent(),
  editorDomRemoved: await desktop.locator(".ProseMirror").count() === 0,
};
assert.deepEqual(evidence.desktop.teardown, { destroyed: true, staleOutputMutation: false, editorDomRemoved: true });
assertClean(evidence.desktop);
await desktopContext.close();

const mobileContext = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
const mobile = await mobileContext.newPage();
observe(mobile, evidence.mobileEmulation);
await mobile.goto(baseUrl, { waitUntil: "networkidle" });
await mobile.locator(".ProseMirror").waitFor();
await mobile.touchscreen.tap(60, 310);
await mobile.locator(".ProseMirror").focus();
await mobile.keyboard.press("Meta+End");
await mobile.keyboard.insertText(" МОБИЛЬНЫЙ-ВВОД ");
const selectionBeforeTab = await mobile.evaluate(() => window.mnemaPrototype.selection);
await mobile.getByRole("button", { name: "Вид", exact: true }).click();
assert.equal(await mobile.locator("[data-pane=edit]").isHidden(), true);
assert.match(await mobile.locator("#preview").textContent(), /МОБИЛЬНЫЙ-ВВОД/);
await mobile.getByRole("button", { name: "Материал", exact: true }).click();
await mobile.waitForTimeout(50);
const selectionAfterTab = await mobile.evaluate(() => window.mnemaPrototype.selection);
assert.deepEqual(selectionAfterTab, selectionBeforeTab);

evidence.mobileEmulation.layout = await mobile.evaluate(() => ({
  scrollWidth: document.documentElement.scrollWidth,
  clientWidth: document.documentElement.clientWidth,
  selection: window.mnemaPrototype.selection,
  insertedText: JSON.stringify(window.mnemaPrototype.export()).includes("МОБИЛЬНЫЙ-ВВОД"),
  touchTargetsBelow44: [...document.querySelectorAll("button")].filter((element) => {
    const box = element.getBoundingClientRect();
    return box.width > 0 && box.height > 0 && (box.width < 44 || box.height < 44);
  }).map((element) => element.textContent.trim()),
}));
assert.equal(evidence.mobileEmulation.layout.scrollWidth, evidence.mobileEmulation.layout.clientWidth);
assert.equal(evidence.mobileEmulation.layout.insertedText, true);
assert.deepEqual(evidence.mobileEmulation.layout.touchTargetsBelow44, []);
await mobile.screenshot({ path: "screenshots/editor-390.png" });
assertClean(evidence.mobileEmulation);
await mobileContext.close();

await browser.close();
console.log(JSON.stringify(evidence, null, 2));
