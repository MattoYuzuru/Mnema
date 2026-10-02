import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    OnDestroy,
    ViewEncapsulation,
    computed,
    effect,
    input,
    output,
    signal,
    viewChild
} from '@angular/core';
import { baseKeymap, setBlockType, toggleMark, wrapIn } from 'prosemirror-commands';
import { history, redo, undo } from 'prosemirror-history';
import { keymap } from 'prosemirror-keymap';
import { liftListItem, sinkListItem, splitListItem, wrapInList } from 'prosemirror-schema-list';
import { EditorState, NodeSelection, Selection } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';

import { NativeDocument, NativeNode } from '../native-document';
import { buildNativeRenderState, isAllowedNativeHref } from '../rendering/native-render-state';
import { youtubeVideoId } from '../youtube-video-id';
import {
    NativeEditorAdapterError,
    NativeEditorEmptyCodeError,
    exportNativeDocument,
    importNativeDocument,
    nativeEditorSchema,
    nativeTextIdentityPlugin,
    sanitizePastedHtml
} from './native-editor-adapter';
import { NativeCodeNodeView } from './native-code-node-view';
import { NativeTableNodeView } from './native-table-node-view';

@Component({
    selector: 'app-native-editor',
    templateUrl: './native-editor.component.html',
    styleUrl: './native-editor.component.css',
    encapsulation: ViewEncapsulation.None,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'mnema-native-editor', '[class.is-readonly]': '!editable()' }
})
export class NativeEditorComponent implements AfterViewInit, OnDestroy {
    readonly document = input.required<NativeDocument>();
    readonly disabled = input(false);
    readonly documentChange = output<NativeDocument>();
    readonly focusChange = output<boolean>();
    readonly filesAdded = output<readonly File[]>();

    readonly editable = signal(true);
    readonly empty = signal(true);
    readonly failure = signal<string | null>(null);
    readonly rubyBase = signal('');
    readonly rubyReading = signal('');
    readonly rubySelected = signal(false);
    readonly linkHref = signal('');
    readonly linkSelected = signal(false);
    readonly activePopover = signal<'link' | 'ruby' | null>(null);
    readonly selectionActions = signal(false);
    readonly popoverLeft = signal(0);
    readonly popoverTop = signal(0);
    readonly activeMarks = signal<ReadonlySet<string>>(new Set());
    readonly canInsertRuby = computed(() => this.rubyBase().trim().length > 0 && this.rubyReading().trim().length > 0);
    readonly richKind = signal<'image' | 'audio' | 'video' | 'youtube' | 'mermaid'>('image');
    readonly mediaInspectorOpen = signal(false);
    readonly richAssetId = signal('');
    readonly richTitle = signal('');
    readonly richCaption = signal('');
    readonly richDescription = signal('');
    readonly richTranscript = signal('');
    readonly richSource = signal('');
    readonly richYoutubeUrl = signal('');
    readonly richSelected = signal(false);

    private readonly editorHost = viewChild.required<ElementRef<HTMLElement>>('editorHost');
    private readonly richTools = viewChild<ElementRef<HTMLElement>>('richTools');
    private editorView: EditorView | null = null;
    private viewInitialized = false;
    private readonly disabledSync = effect(() => {
        const disabled = this.disabled();
        this.editorView?.setProps({ editable: () => !disabled });
        if (this.editorView !== null) this.syncTableDisabled(disabled);
    });
    private readonly documentSync = effect(() => {
        const document = this.document();
        if (this.viewInitialized) this.syncDocument(document);
    });

    ngAfterViewInit(): void {
        this.viewInitialized = true;
        this.syncDocument(this.document());
    }

    ngOnDestroy(): void {
        this.viewInitialized = false;
        this.editorView?.destroy();
        this.editorView = null;
    }

    private syncDocument(document: NativeDocument): void {
        try {
            if (this.editorView !== null && this.sameAsEditor(document)) return;
            const imported = importNativeDocument(document);
            this.editable.set(imported.editable);
            this.empty.set(imported.document !== null && this.isEmpty(imported.document));
            if (imported.document === null) {
                this.editorView?.destroy();
                this.editorView = null;
                this.editorHost().nativeElement.replaceChildren();
                return;
            }
            const state = this.createState(imported.document);
            if (this.editorView !== null) {
                this.editorView.updateState(state);
                this.editorView.setProps({ editable: () => !this.disabled() });
                this.syncTableDisabled(this.disabled());
                this.updateFormattingState(state);
                this.failure.set(null);
                return;
            }
            this.editorView = new EditorView(this.editorHost().nativeElement, {
                state,
                editable: () => !this.disabled(),
                attributes: {
                    class: 'mnema-editor-surface',
                    role: 'textbox',
                    'aria-label': 'Содержание материала',
                    'aria-multiline': 'true',
                    spellcheck: 'true'
                },
                transformPastedHTML: sanitizePastedHtml,
                handleTextInput: (view, from, to, text) => this.handleMarkdownShortcut(view, from, to, text),
                handlePaste: (view, event) => this.handlePaste(view, event),
                handleDrop: (view, event) => this.handleDrop(view, event),
                nodeViews: {
                    table: (node, view, getPos) => new NativeTableNodeView(node, view, getPos),
                    code_block: (node, view, getPos) => new NativeCodeNodeView(node, view, getPos)
                },
                dispatchTransaction: transaction => {
                    if (this.editorView === null) return;
                    const next = this.editorView.state.apply(transaction);
                    this.editorView.updateState(next);
                    this.empty.set(this.isEmpty(next.doc));
                    this.updateFormattingState(next);
                    if (transaction.docChanged) this.emitDocument();
                },
                handleDOMEvents: {
                    focus: () => { this.focusChange.emit(true); return false; },
                    blur: () => { this.focusChange.emit(false); return false; },
                    mousedown: (_view, event) => {
                        const target = event.target;
                        if (target instanceof Element && target.closest('.mnema-rich-atom')) {
                            this.openMediaTools();
                        }
                        return false;
                    }
                }
            });
            this.updateFormattingState(this.editorView.state);
            this.syncTableDisabled(this.disabled());
        } catch (error) {
            this.editable.set(false);
            this.failure.set(error instanceof NativeEditorAdapterError
                ? 'Материал нельзя безопасно открыть для редактирования.'
                : 'Редактор не удалось запустить.');
        }
    }

    /** Whether the editor already shows `document`; a state that cannot be exported (empty code only) differs. */
    private sameAsEditor(document: NativeDocument): boolean {
        try {
            return JSON.stringify(exportNativeDocument(this.editorView!.state.doc)) === JSON.stringify(document);
        } catch {
            return false;
        }
    }

    private isEmpty(document: import('prosemirror-model').Node): boolean {
        return document.childCount === 1 && document.firstChild?.type === nativeEditorSchema.nodes['paragraph']
            && document.firstChild.content.size === 0;
    }

    private syncTableDisabled(disabled: boolean): void {
        this.editorHost().nativeElement.querySelectorAll<HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement>(
            '.mnema-table-node input, .mnema-table-node textarea, .mnema-table-node button, .mnema-code-node input, .mnema-code-node textarea')
            .forEach(control => { control.disabled = disabled; });
    }

    private createState(document: import('prosemirror-model').Node): EditorState {
        return EditorState.create({
            schema: nativeEditorSchema,
            doc: document,
            plugins: [
                history(),
                nativeTextIdentityPlugin,
                keymap({ 'Mod-z': undo, 'Shift-Mod-z': redo, 'Mod-y': redo }),
                keymap({ Enter: splitListItem(nativeEditorSchema.nodes['list_item']!),
                    'Mod-]': sinkListItem(nativeEditorSchema.nodes['list_item']!),
                    'Mod-[': liftListItem(nativeEditorSchema.nodes['list_item']!) }),
                keymap(baseKeymap)
            ]
        });
    }

    toggle(mark: 'strong' | 'em' | 'code'): void {
        const view = this.editorView;
        const type = nativeEditorSchema.marks[mark];
        if (view === null || type === undefined) return;
        toggleMark(type)(view.state, view.dispatch, view);
        view.focus();
    }

    paragraph(): void {
        this.run(setBlockType(nativeEditorSchema.nodes['paragraph']!));
    }

    heading(level: number): void {
        this.run(setBlockType(nativeEditorSchema.nodes['heading']!, { level }));
    }

    quote(): void {
        this.run(wrapIn(nativeEditorSchema.nodes['blockquote']!));
    }

    list(kind: 'bullet_list' | 'ordered_list'): void {
        this.run(wrapInList(nativeEditorSchema.nodes[kind]!));
    }

    divider(): void {
        const view = this.editorView;
        if (view === null) return;
        const node = nativeEditorSchema.nodes['divider']!.create({ id: crypto.randomUUID(), version: 1 });
        view.dispatch(view.state.tr.replaceSelectionWith(node).scrollIntoView());
        view.focus();
    }

    focusContent(): void { this.editorView?.focus(); }

    openMediaTools(): void {
        this.mediaInspectorOpen.set(true);
        requestAnimationFrame(() => this.richTools()?.nativeElement.scrollIntoView({ block: 'nearest' }));
    }

    closeMediaTools(): void {
        this.mediaInspectorOpen.set(false);
        this.editorView?.focus();
    }

    /** Inserts an empty code block and puts the caret in its textarea; an empty block is not part of the document. */
    insertCodeBlock(): void {
        const view = this.editorView;
        if (view === null || this.disabled() || !this.editable()) return;
        const node = nativeEditorSchema.nodes['code_block']!.create({ id: crypto.randomUUID(), version: 1 });
        // After the current block, so an empty paragraph is kept and the document never holds only a blank code block.
        const { selection } = view.state;
        const position = selection instanceof NodeSelection || selection.$to.depth === 0
            ? selection.to : selection.$to.after();
        view.dispatch(view.state.tr.insert(position, node).scrollIntoView());
        const textarea = this.editorHost().nativeElement.querySelectorAll<HTMLTextAreaElement>('.mnema-code-node textarea');
        const target = Array.from(textarea).find(element => element.value === '');
        if (target) target.focus(); else view.focus();
    }

    insertTable(): void {
        const view = this.editorView;
        if (view === null || this.disabled() || !this.editable()) return;
        const node = nativeEditorSchema.nodes['table']!.create({
            id: crypto.randomUUID(), version: 1, caption: 'Таблица',
            columns: ['Столбец 1', 'Столбец 2'], rows: [['', ''], ['', '']]
        });
        view.dispatch(view.state.tr.replaceSelectionWith(node).scrollIntoView());
        view.focus();
    }

    private handlePaste(view: EditorView, event: ClipboardEvent): boolean {
        if (this.disabled() || !this.editable()) return false;
        const files = Array.from(event.clipboardData?.files ?? []);
        if (files.length > 0) return this.acceptFiles(files);
        const text = event.clipboardData?.getData('text/plain').trim() ?? '';
        if (!text.startsWith('https://') || youtubeVideoId(text) === null) return false;
        this.insertYoutubeUrl(text);
        return true;
    }

    private handleDrop(view: EditorView, event: DragEvent): boolean {
        if (this.disabled() || !this.editable()) return false;
        const files = Array.from(event.dataTransfer?.files ?? []);
        if (files.length === 0) return false;
        const position = view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos;
        if (position !== undefined) view.dispatch(view.state.tr.setSelection(Selection.near(view.state.doc.resolve(position))));
        return this.acceptFiles(files);
    }

    private acceptFiles(files: readonly File[]): boolean {
        this.filesAdded.emit(files);
        this.openMediaTools();
        return true;
    }

    private insertYoutubeUrl(url: string): void {
        this.selectRichKind('youtube');
        this.richYoutubeUrl.set(url);
        this.richTitle.set('Видео YouTube');
        this.applyRichNode();
        this.openMediaTools();
    }

    richFieldKeydown(event: KeyboardEvent): void {
        if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229
            && (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement)) {
            event.preventDefault();
            this.applyRichNode();
        }
    }

    private handleMarkdownShortcut(view: EditorView, from: number, to: number, text: string): boolean {
        if (this.disabled() || !this.editable() || from !== to) return false;
        const $from = view.state.doc.resolve(from);
        if ($from.parent.type !== nativeEditorSchema.nodes['paragraph'] || $from.parentOffset !== $from.parent.content.size) return false;
        const marker = $from.parent.textContent;
        if (text === '-' && marker === '--') {
            const node = nativeEditorSchema.nodes['divider']!.create({ id: crypto.randomUUID(), version: 1 });
            const withinListItem = $from.depth > 1
                && $from.node($from.depth - 1).type === nativeEditorSchema.nodes['list_item'];
            const replacement = withinListItem
                ? [$from.parent.type.create($from.parent.attrs), node] : node;
            view.dispatch(view.state.tr.replaceWith($from.before(), $from.after(), replacement).scrollIntoView());
            return true;
        }
        if (text !== ' ') return false;
        const ordered = /^([1-9][0-9]{0,2})\.$/u.exec(marker);
        const type = marker === '-' ? nativeEditorSchema.nodes['bullet_list']
            : ordered ? nativeEditorSchema.nodes['ordered_list'] : null;
        if (type === null || type === undefined) return false;
        const attrs = ordered ? { order: Number(ordered[1]), orderPresent: true } : undefined;
        if (!wrapInList(type, attrs)(view.state)) return false;
        view.dispatch(view.state.tr.delete(from - marker.length, to));
        wrapInList(type, attrs)(view.state, view.dispatch, view);
        return true;
    }

    openLinkTools(): void {
        this.activePopover.set('link');
        this.selectionActions.set(false);
        if (this.editorView) this.positionPopover(this.editorView.state);
    }

    openRubyTools(): void {
        this.activePopover.set('ruby');
        this.selectionActions.set(false);
        if (this.editorView) this.positionPopover(this.editorView.state);
    }

    closePopover(): void {
        this.activePopover.set(null);
        this.editorView?.focus();
    }

    private positionPopover(state: EditorState): void {
        const view = this.editorView;
        if (view === null) return;
        try {
            const cursor = view.coordsAtPos(state.selection.from);
            const frame = this.editorHost().nativeElement.parentElement!.getBoundingClientRect();
            this.popoverLeft.set(Math.max(0, Math.min(cursor.left - frame.left, frame.width - 352)));
            const below = cursor.bottom + 248 <= window.innerHeight;
            this.popoverTop.set(Math.max(0, (below ? cursor.bottom + 8 : cursor.top - 248) - frame.top));
        } catch { this.popoverLeft.set(0); this.popoverTop.set(0); }
    }

    applyLink(): void {
        const view = this.editorView;
        if (view === null || this.disabled() || !this.editable()) return;
        const href = this.linkHref().trim();
        if (!isAllowedNativeHref(href)) { this.failure.set('Укажите полную ссылку HTTPS.'); return; }
        const selected = this.enclosingLink(view.state);
        if (selected !== null) {
            view.dispatch(view.state.tr.setNodeMarkup(selected.pos, undefined, { ...selected.node.attrs, href }));
        } else {
            const selection = view.state.selection;
            if (selection.empty || !selection.$from.sameParent(selection.$to)) {
                this.failure.set('Выделите текст внутри одного абзаца, чтобы сделать его ссылкой.');
                return;
            }
            const content = selection.$from.parent.content.cut(
                selection.$from.parentOffset, selection.$to.parentOffset);
            if (content.childCount === 0 || !content.firstChild?.isInline) {
                this.failure.set('Выделите текст, который хотите сделать ссылкой.');
                return;
            }
            const link = nativeEditorSchema.nodes['link_node']!.create({ id: crypto.randomUUID(), version: 1, href }, content);
            view.dispatch(view.state.tr.replaceSelectionWith(link).scrollIntoView());
        }
        this.failure.set(null);
        this.activePopover.set(null);
        view.focus();
    }

    removeLink(): void {
        const view = this.editorView;
        const selected = view === null ? null : this.enclosingLink(view.state);
        if (view === null || selected === null || this.disabled()) return;
        view.dispatch(view.state.tr.replaceWith(selected.pos, selected.pos + selected.node.nodeSize, selected.node.content));
        this.activePopover.set(null);
        view.focus();
    }

    private enclosingLink(state: EditorState): { pos: number; node: import('prosemirror-model').Node } | null {
        const selection = state.selection;
        if (selection instanceof NodeSelection && selection.node.type === nativeEditorSchema.nodes['link_node']) {
            return { pos: selection.from, node: selection.node };
        }
        for (let depth = selection.$from.depth; depth > 0; depth -= 1) {
            const node = selection.$from.node(depth);
            if (node.type === nativeEditorSchema.nodes['link_node']) return { pos: selection.$from.before(depth), node };
        }
        return null;
    }

    insertRuby(): void {
        const view = this.editorView;
        if (view === null || !this.canInsertRuby()) return;
        const selected = view.state.selection instanceof NodeSelection
            && view.state.selection.node.type === nativeEditorSchema.nodes['ruby']
            ? view.state.selection.node : null;
        const attrs = { id: selected?.attrs['id'] ?? crypto.randomUUID(), version: 1,
            base: this.rubyBase().trim(), reading: this.rubyReading().trim() };
        if (selected === null) {
            view.dispatch(view.state.tr.replaceSelectionWith(nativeEditorSchema.nodes['ruby']!.create(attrs)).scrollIntoView());
        } else {
            view.dispatch(view.state.tr.setNodeMarkup(view.state.selection.from, undefined, attrs).scrollIntoView());
        }
        if (selected === null) {
            this.rubyBase.set('');
            this.rubyReading.set('');
        }
        this.activePopover.set(null);
        view.focus();
    }

    selectRichKind(kind: 'image' | 'audio' | 'video' | 'youtube' | 'mermaid'): void {
        this.richKind.set(kind);
        this.richSelected.set(false);
        this.richAssetId.set('');
        this.richTitle.set('');
        this.richCaption.set('');
        this.richDescription.set('');
        this.richTranscript.set('');
        this.richSource.set('');
        this.richYoutubeUrl.set('');
        this.failure.set(null);
    }

    /** Allows the upload flow to hand an authorized asset reference to the editor. */
    prepareMedia(kind: 'image' | 'audio' | 'video', assetId: string): void {
        if (!this.richSelected() || this.richKind() !== kind) this.selectRichKind(kind);
        this.richAssetId.set(assetId);
        this.openMediaTools();
    }

    applyRichNode(): void {
        const view = this.editorView;
        if (view === null || this.disabled() || !this.editable()) return;
        const selected = view.state.selection instanceof NodeSelection
            && view.state.selection.node.type.name === this.richKind() ? view.state.selection.node : null;
        const attrs = this.richAttrs();
        const candidate: NativeNode = {
            id: selected === null ? crypto.randomUUID() : String(selected.attrs['id']),
            type: this.richKind(), version: 1, attrs, content: []
        };
        const document: NativeDocument = { formatVersion: 1, root: {
            id: crypto.randomUUID(), type: 'doc', version: 1, attrs: {}, content: [candidate]
        } };
        if (buildNativeRenderState(document).status !== 'ready') {
            this.failure.set('Проверьте обязательные поля и размер текста. Для изображения, аудио и видео сначала выберите файл.');
            return;
        }
        const type = nativeEditorSchema.nodes[this.richKind()]!;
        const pmAttrs = { id: candidate.id, version: 1, ...attrs };
        const transaction = selected === null
            ? view.state.tr.replaceSelectionWith(type.create(pmAttrs)).scrollIntoView()
            : view.state.tr.setNodeMarkup(view.state.selection.from, type, pmAttrs).scrollIntoView();
        view.dispatch(transaction);
        this.mediaInspectorOpen.set(false);
        view.focus();
    }

    private richAttrs(): Record<string, string> {
        const kind = this.richKind();
        if (kind === 'image') {
            const attrs: Record<string, string> = { assetId: this.richAssetId().trim(), alt: this.richTitle() };
            if (this.richCaption().length > 0) attrs['caption'] = this.richCaption();
            if (this.richDescription().length > 0) attrs['description'] = this.richDescription();
            return attrs;
        }
        if (kind === 'audio' || kind === 'video') {
            const attrs: Record<string, string> = { assetId: this.richAssetId().trim(), title: this.richTitle() };
            if (this.richTranscript().length > 0) attrs['transcript'] = this.richTranscript();
            return attrs;
        }
        if (kind === 'youtube') {
            const attrs: Record<string, string> = {
                videoId: youtubeVideoId(this.richYoutubeUrl()) ?? '', title: this.richTitle()
            };
            if (this.richTranscript().length > 0) attrs['transcript'] = this.richTranscript();
            return attrs;
        }
        return { source: this.richSource(), title: this.richTitle(), description: this.richDescription() };
    }

    undo(): void { this.run(undo); }
    redo(): void { this.run(redo); }

    private run(command: (state: EditorState, dispatch?: (transaction: import('prosemirror-state').Transaction) => void,
        view?: EditorView) => boolean): void {
        const view = this.editorView;
        if (view === null) return;
        command(view.state, view.dispatch, view);
        view.focus();
    }

    private emitDocument(): void {
        if (this.editorView === null) return;
        try {
            this.failure.set(null);
            this.documentChange.emit(exportNativeDocument(this.editorView.state.doc));
        } catch (error) {
            this.failure.set(error instanceof NativeEditorEmptyCodeError
                ? 'Блок кода пока пуст: впишите код или удалите блок.'
                : 'Правка вышла за безопасные границы формата. Отмените последнее действие.');
        }
    }

    private updateFormattingState(state: EditorState): void {
        const marks = state.storedMarks ?? state.selection.$from.marks();
        this.activeMarks.set(new Set(marks.map(mark => mark.type.name)));
        const link = this.enclosingLink(state);
        const wasLinkSelected = this.linkSelected();
        this.linkSelected.set(link !== null);
        if (link !== null) {
            this.linkHref.set(String(link.node.attrs['href']));
            if (!wasLinkSelected) this.activePopover.set('link');
        } else this.linkHref.set('');
        const selection = state.selection;
        this.positionPopover(state);
        if (!(selection instanceof NodeSelection)) {
            this.richSelected.set(false);
            this.rubySelected.set(false);
            this.selectionActions.set(!selection.empty && selection.$from.sameParent(selection.$to)
                && this.activePopover() === null);
            if (!selection.empty && selection.$from.sameParent(selection.$to)) {
                const selectedText = selection.$from.parent.textBetween(
                    selection.$from.parentOffset, selection.$to.parentOffset);
                if (selectedText.length > 0 && selectedText.length <= 256) this.rubyBase.set(selectedText);
            }
            if (selection.empty && link === null) this.activePopover.set(null);
            return;
        }
        this.selectionActions.set(false);
        const node = selection.node;
        if (node.type === nativeEditorSchema.nodes['ruby']) {
            if (!this.rubySelected()) this.activePopover.set('ruby');
            this.rubySelected.set(true);
            this.rubyBase.set(String(node.attrs['base']));
            this.rubyReading.set(String(node.attrs['reading']));
            this.richSelected.set(false);
            return;
        }
        this.rubySelected.set(false);
        if (node.type.name === 'table') {
            this.richSelected.set(false);
            this.mediaInspectorOpen.set(false);
            return;
        }
        if (!['image', 'audio', 'video', 'youtube', 'mermaid'].includes(node.type.name)) {
            this.richSelected.set(false);
            return;
        }
        const kind = node.type.name as 'image' | 'audio' | 'video' | 'youtube' | 'mermaid';
        this.richKind.set(kind);
        const wasSelected = this.richSelected();
        this.richSelected.set(true);
        if (!wasSelected) this.openMediaTools();
        this.richAssetId.set(String(node.attrs['assetId'] ?? ''));
        this.richTitle.set(String(node.attrs[kind === 'image' ? 'alt' : 'title'] ?? ''));
        this.richCaption.set(String(node.attrs['caption'] ?? ''));
        this.richDescription.set(String(node.attrs['description'] ?? ''));
        this.richTranscript.set(String(node.attrs['transcript'] ?? ''));
        this.richSource.set(String(node.attrs['source'] ?? ''));
        this.richYoutubeUrl.set(node.attrs['videoId'] ? `https://www.youtube.com/watch?v=${node.attrs['videoId']}` : '');
    }
}
