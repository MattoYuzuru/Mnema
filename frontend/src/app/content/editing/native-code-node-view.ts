import { Node as ProseMirrorNode } from 'prosemirror-model';
import { NodeSelection } from 'prosemirror-state';
import { EditorView, NodeView } from 'prosemirror-view';

import { CODE_BLOCK_MAX_LANGUAGE, CODE_BLOCK_MAX_SOURCE, isCodeBlockLanguage } from '../rendering/native-render-state';

let instances = 0;
/**
 * Set by Esc, cleared by the next key typed in any code textarea. While it is set, Tab keeps its normal meaning (move focus)
 * so that a keyboard user can leave a code block, and the next ones, forward: without it the textarea would swallow every Tab.
 * Shared by all code blocks because the Tab order passes through each of them in turn.
 */
let tabMovesFocus = false;

/**
 * Edits a native `code_block` in place: a language field and a plain `<textarea>`, so tabs, line breaks and trailing
 * spaces are exactly what the author typed. The node stays one atom of the document; nothing is executed or highlighted.
 *
 * Keyboard: Tab inserts a tab character (code needs indentation), Shift+Tab moves to the previous field, and Escape
 * leaves the block: it selects the block in the document and returns focus to the editor, and from then on Tab moves
 * focus forward again until the author types in a code block (see `tabMovesFocus`). The hint under the field says so;
 * the textarea is never a keyboard trap.
 */
export class NativeCodeNodeView implements NodeView {
    readonly dom: HTMLElement;
    private node: ProseMirrorNode;
    private readonly language: HTMLInputElement;
    private readonly source: HTMLTextAreaElement;

    constructor(node: ProseMirrorNode, private readonly view: EditorView,
                private readonly getPos: () => number | undefined) {
        this.node = node;
        const id = `mnema-code-${++instances}`;
        this.dom = document.createElement('div');
        this.dom.className = 'mnema-code-node';
        this.dom.contentEditable = 'false';

        const languageLabel = document.createElement('label');
        languageLabel.className = 'mnema-code-language';
        languageLabel.textContent = 'Язык кода';
        this.language = document.createElement('input');
        this.language.type = 'text';
        this.language.maxLength = CODE_BLOCK_MAX_LANGUAGE;
        this.language.placeholder = 'sql, python, c++';
        this.language.spellcheck = false;
        this.language.autocomplete = 'off';
        this.language.autocapitalize = 'none';
        this.language.dataset['field'] = 'lang';
        this.language.setAttribute('aria-describedby', `${id}-language-hint`);
        languageLabel.append(this.language);
        const languageHint = document.createElement('span');
        languageHint.id = `${id}-language-hint`;
        languageHint.className = 'mnema-code-hint';
        languageHint.textContent = 'Необязательно: строчные латинские буквы, цифры и + # . -';

        const sourceLabel = document.createElement('label');
        sourceLabel.className = 'mnema-code-source';
        sourceLabel.textContent = 'Код';
        this.source = document.createElement('textarea');
        this.source.rows = 6;
        this.source.maxLength = CODE_BLOCK_MAX_SOURCE;
        this.source.spellcheck = false;
        this.source.autocomplete = 'off';
        this.source.autocapitalize = 'none';
        this.source.wrap = 'off';
        this.source.dir = 'ltr';
        this.source.dataset['field'] = 'source';
        this.source.setAttribute('aria-describedby', `${id}-hint`);
        sourceLabel.append(this.source);
        const hint = document.createElement('p');
        hint.id = `${id}-hint`;
        hint.className = 'mnema-code-hint';
        hint.textContent = 'Tab вставляет отступ. Esc выходит из блока кода: после него Tab переходит к следующему элементу. Shift+Tab возвращает к полю языка.';

        this.dom.append(languageLabel, languageHint, sourceLabel, hint);
        this.syncValues(true);
        this.syncDisabled();

        this.language.addEventListener('input', () => this.commitLanguage());
        this.language.addEventListener('change', () => {
            this.language.value = this.language.value.trim().toLowerCase();
            this.commitLanguage();
        });
        this.source.addEventListener('input', () => this.write({ source: this.source.value }));
        this.source.addEventListener('keydown', event => this.keydown(event));
    }

    update(node: ProseMirrorNode): boolean {
        if (node.type !== this.node.type) return false;
        this.node = node;
        this.syncValues(false);
        this.syncDisabled();
        return true;
    }

    selectNode(): void { this.dom.classList.add('ProseMirror-selectednode'); }
    deselectNode(): void { this.dom.classList.remove('ProseMirror-selectednode'); }
    stopEvent(event: Event): boolean {
        return event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement;
    }
    ignoreMutation(): boolean { return true; }

    private syncValues(force: boolean): void {
        const lang = typeof this.node.attrs['lang'] === 'string' ? this.node.attrs['lang'] as string : '';
        if (force || this.language !== document.activeElement) {
            this.language.value = lang;
            this.language.removeAttribute('aria-invalid');
        }
        const source = String(this.node.attrs['source']);
        if (force || (this.source !== document.activeElement && this.source.value !== source)) this.source.value = source;
    }

    private syncDisabled(): void {
        this.language.disabled = !this.view.editable;
        this.source.disabled = !this.view.editable;
    }

    /** Only a valid language reaches the document; an invalid one is flagged and the last valid value stays. */
    private commitLanguage(): void {
        const candidate = this.language.value.trim().toLowerCase();
        if (!isCodeBlockLanguage(candidate)) {
            this.language.setAttribute('aria-invalid', 'true');
            return;
        }
        this.language.removeAttribute('aria-invalid');
        this.write({ lang: candidate === '' ? null : candidate });
    }

    private keydown(event: KeyboardEvent): void {
        if (event.isComposing || event.key === 'Process') return;
        if (event.key === 'Tab' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey) {
            if (tabMovesFocus) return;
            event.preventDefault();
            const { selectionStart, selectionEnd } = this.source;
            if (this.source.value.length - (selectionEnd - selectionStart) < CODE_BLOCK_MAX_SOURCE) {
                this.source.setRangeText('\t', selectionStart, selectionEnd, 'end');
                this.write({ source: this.source.value });
            }
        } else if (event.key === 'Escape') {
            event.preventDefault();
            tabMovesFocus = true;
            this.leave();
        } else if (!['Tab', 'Shift', 'Control', 'Alt', 'Meta'].includes(event.key)) {
            tabMovesFocus = false;
        }
    }

    private leave(): void {
        const position = this.getPos();
        if (position === undefined) return;
        this.view.dispatch(this.view.state.tr.setSelection(NodeSelection.create(this.view.state.doc, position)));
        this.view.focus();
    }

    private write(changes: Record<string, unknown>): void {
        if (!this.view.editable) return;
        const position = this.getPos();
        if (position === undefined) return;
        this.view.dispatch(this.view.state.tr.setNodeMarkup(position, undefined, { ...this.node.attrs, ...changes }));
    }
}
