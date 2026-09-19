import { toggleMark } from "prosemirror-commands";
import { history, redo, undo } from "prosemirror-history";
import { keymap } from "prosemirror-keymap";
import { EditorState, TextSelection } from "prosemirror-state";
import { EditorView } from "prosemirror-view";
import {
  createPreview,
  exportNativeDocument,
  freshenPastedSlice,
  identityNormalizationPlugin,
  importNativeDocument,
  mnemaSchema,
  nodeViews,
  sanitizePastedHtml,
} from "./adapter.js";
import { createFixture, createFutureRootFixture } from "./fixtures.js";

const editorHost = document.querySelector("#editor-surface");
const preview = document.querySelector("#preview");
const output = document.querySelector("#native-output");
const status = document.querySelector("#status");
let session;
let view;
let destroyed = false;
const transactionEvidence = [];

function plugins() {
  return [
    history(),
    identityNormalizationPlugin,
    keymap({ "Mod-z": undo, "Shift-Mod-z": redo, "Mod-y": redo }),
  ];
}

function updateEvidence(label = "Изменения проверены локально") {
  const native = exportNativeDocument(session.readOnly ? session : view.state.doc);
  output.textContent = JSON.stringify(native, null, 2);
  createPreview(native, preview);
  status.textContent = label;
  return native;
}

function mount(native) {
  if (view) view.destroy();
  session = importNativeDocument(native);
  destroyed = false;
  const state = EditorState.create({ doc: session.doc, plugins: plugins() });
  view = new EditorView(editorHost, {
    state,
    editable: () => !session.readOnly,
    nodeViews,
    attributes: {
      "aria-label": session.readOnly ? "Неподдерживаемый материал только для чтения" : "Редактор материала",
      "aria-multiline": "true",
      role: "textbox",
    },
    transformPasted: freshenPastedSlice,
    transformPastedHTML: sanitizePastedHtml,
    dispatchTransaction(transaction) {
      if (destroyed) return;
      const result = view.state.applyTransaction(transaction);
      transactionEvidence.push({
        rootDocChanged: transaction.docChanged,
        appended: result.transactions.slice(1).map((item) => ({
          addToHistory: item.getMeta("addToHistory"),
          identityNormalization: item.getMeta("mnemaIdentityNormalization") === true,
        })),
      });
      view.updateState(result.state);
      session.doc = result.state.doc;
      updateEvidence();
    },
  });
  updateEvidence(session.readOnly ? "Будущая версия открыта только для чтения" : "Черновик открыт");
  return view;
}

function run(command) {
  command(view.state, view.dispatch, view);
  view.focus();
}

document.querySelector("[data-command=strong]").addEventListener("click", () => run(toggleMark(mnemaSchema.marks.strong)));
document.querySelector("[data-command=em]").addEventListener("click", () => run(toggleMark(mnemaSchema.marks.em)));
document.querySelector("[data-command=undo]").addEventListener("click", () => run(undo));
document.querySelector("[data-command=redo]").addEventListener("click", () => run(redo));
document.querySelector("[data-command=ruby]").addEventListener("click", () => {
  const ruby = mnemaSchema.nodes.ruby.create({ nativeId: crypto.randomUUID(), nativeVersion: 1, base: "漢字", reading: "かんじ" });
  view.dispatch(view.state.tr.replaceSelectionWith(ruby).scrollIntoView());
  view.focus();
});
document.querySelector("[data-command=save]").addEventListener("click", () => updateEvidence("Нативный AST прошёл валидацию"));

function activateTab(selected) {
  for (const candidate of document.querySelectorAll("[data-tab]")) candidate.setAttribute("aria-pressed", String(candidate.dataset.tab === selected));
  for (const pane of document.querySelectorAll("[data-pane]")) pane.hidden = pane.dataset.pane !== selected;
  if (selected === "edit" && view) requestAnimationFrame(() => view.focus());
}

for (const tab of document.querySelectorAll("[data-tab]")) {
  tab.addEventListener("click", () => activateTab(tab.dataset.tab));
}

function destroy() {
  destroyed = true;
  view.destroy();
}

window.mnemaPrototype = {
  export: () => exportNativeDocument(session.readOnly ? session : view.state.doc),
  load: mount,
  loadFixture: (options) => mount(createFixture(options)),
  loadFutureRoot: () => mount(createFutureRootFixture()),
  sanitizePastedHtml,
  destroy,
  get destroyed() { return destroyed; },
  get readOnly() { return session.readOnly; },
  get selection() { return { from: view.state.selection.from, to: view.state.selection.to }; },
  get composing() { return view.composing; },
  get transactions() { return structuredClone(transactionEvidence); },
  get view() { return view; },
  select(from, to = from) {
    view.dispatch(view.state.tr.setSelection(TextSelection.create(view.state.doc, from, to)));
    view.focus();
  },
};

mount(createFixture({ long: true }));
if (matchMedia("(max-width: 700px)").matches) activateTab("edit");
