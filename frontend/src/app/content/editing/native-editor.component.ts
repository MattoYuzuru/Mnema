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
import { wrapInList } from 'prosemirror-schema-list';
import { EditorState, NodeSelection } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';

import { NativeDocument, NativeNode } from '../native-document';
import { buildNativeRenderState } from '../rendering/native-render-state';
import {
    NativeEditorAdapterError,
    exportNativeDocument,
    importNativeDocument,
    nativeEditorSchema,
    nativeTextIdentityPlugin,
    sanitizePastedHtml
} from './native-editor-adapter';

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

    readonly editable = signal(true);
    readonly failure = signal<string | null>(null);
    readonly rubyBase = signal('');
    readonly rubyReading = signal('');
    readonly activeMarks = signal<ReadonlySet<string>>(new Set());
    readonly canInsertRuby = computed(() => this.rubyBase().trim().length > 0 && this.rubyReading().trim().length > 0);
    readonly richKind = signal<'image' | 'audio' | 'video' | 'mermaid' | 'table'>('image');
    readonly richAssetId = signal('');
    readonly richTitle = signal('');
    readonly richCaption = signal('');
    readonly richDescription = signal('');
    readonly richTranscript = signal('');
    readonly richSource = signal('');
    readonly richColumns = signal('');
    readonly richRows = signal('');
    readonly richSummary = signal('');
    readonly richSelected = signal(false);

    private readonly editorHost = viewChild.required<ElementRef<HTMLElement>>('editorHost');
    private editorView: EditorView | null = null;
    private viewInitialized = false;
    private readonly disabledSync = effect(() => {
        const disabled = this.disabled();
        this.editorView?.setProps({ editable: () => !disabled });
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
            if (this.editorView !== null
                && JSON.stringify(exportNativeDocument(this.editorView.state.doc)) === JSON.stringify(document)) return;
            const imported = importNativeDocument(document);
            this.editable.set(imported.editable);
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
                dispatchTransaction: transaction => {
                    if (this.editorView === null) return;
                    const next = this.editorView.state.apply(transaction);
                    this.editorView.updateState(next);
                    this.updateFormattingState(next);
                    if (transaction.docChanged) this.emitDocument();
                },
                handleDOMEvents: {
                    focus: () => { this.focusChange.emit(true); return false; },
                    blur: () => { this.focusChange.emit(false); return false; }
                }
            });
            this.updateFormattingState(this.editorView.state);
        } catch (error) {
            this.editable.set(false);
            this.failure.set(error instanceof NativeEditorAdapterError
                ? 'Материал нельзя безопасно открыть для редактирования.'
                : 'Редактор не удалось запустить.');
        }
    }

    private createState(document: import('prosemirror-model').Node): EditorState {
        return EditorState.create({
            schema: nativeEditorSchema,
            doc: document,
            plugins: [
                history(),
                nativeTextIdentityPlugin,
                keymap({ 'Mod-z': undo, 'Shift-Mod-z': redo, 'Mod-y': redo }),
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

    insertRuby(): void {
        const view = this.editorView;
        if (view === null || !this.canInsertRuby()) return;
        const node = nativeEditorSchema.nodes['ruby']!.create({
            id: crypto.randomUUID(), version: 1,
            base: this.rubyBase().trim(), reading: this.rubyReading().trim()
        });
        view.dispatch(view.state.tr.replaceSelectionWith(node).scrollIntoView());
        this.rubyBase.set('');
        this.rubyReading.set('');
        view.focus();
    }

    selectRichKind(kind: 'image' | 'audio' | 'video' | 'mermaid' | 'table'): void {
        this.richKind.set(kind);
        this.richSelected.set(false);
        this.richAssetId.set('');
        this.richTitle.set('');
        this.richCaption.set('');
        this.richDescription.set('');
        this.richTranscript.set('');
        this.richSource.set('');
        this.richColumns.set('');
        this.richRows.set('');
        this.richSummary.set('');
        this.failure.set(null);
    }

    /** Allows the upload flow to hand an authorized asset reference to the editor. */
    prepareMedia(kind: 'image' | 'audio' | 'video', assetId: string): void {
        this.selectRichKind(kind);
        this.richAssetId.set(assetId);
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
            this.failure.set('Проверьте обязательные поля, размер текста, формат ID файла и строки таблицы.');
            return;
        }
        const type = nativeEditorSchema.nodes[this.richKind()]!;
        const pmAttrs = { id: candidate.id, version: 1, ...attrs };
        const transaction = selected === null
            ? view.state.tr.replaceSelectionWith(type.create(pmAttrs)).scrollIntoView()
            : view.state.tr.setNodeMarkup(view.state.selection.from, type, pmAttrs).scrollIntoView();
        view.dispatch(transaction);
        this.richSelected.set(true);
        view.focus();
    }

    private richAttrs(): Record<string, string | string[] | string[][]> {
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
        if (kind === 'mermaid') return { source: this.richSource(), title: this.richTitle(),
            description: this.richDescription() };
        const columns = this.richColumns().split('\n').map(value => value.trimEnd());
        const rows = this.richRows().trim().length === 0 ? []
            : this.richRows().split('\n').map(row => row.split('\t'));
        const attrs: Record<string, string | string[] | string[][]> = {
            caption: this.richCaption(), columns, rows
        };
        if (this.richSummary().length > 0) attrs['summary'] = this.richSummary();
        return attrs;
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
        } catch {
            this.failure.set('Правка вышла за безопасные границы формата. Отмените последнее действие.');
        }
    }

    private updateFormattingState(state: EditorState): void {
        const marks = state.storedMarks ?? state.selection.$from.marks();
        this.activeMarks.set(new Set(marks.map(mark => mark.type.name)));
        const selection = state.selection;
        if (!(selection instanceof NodeSelection)) { this.richSelected.set(false); return; }
        const node = selection.node;
        if (!['image', 'audio', 'video', 'mermaid', 'table'].includes(node.type.name)) {
            this.richSelected.set(false);
            return;
        }
        const kind = node.type.name as 'image' | 'audio' | 'video' | 'mermaid' | 'table';
        this.richKind.set(kind);
        this.richSelected.set(true);
        this.richAssetId.set(String(node.attrs['assetId'] ?? ''));
        this.richTitle.set(String(node.attrs[kind === 'image' ? 'alt' : 'title'] ?? ''));
        this.richCaption.set(String(node.attrs['caption'] ?? ''));
        this.richDescription.set(String(node.attrs['description'] ?? ''));
        this.richTranscript.set(String(node.attrs['transcript'] ?? ''));
        this.richSource.set(String(node.attrs['source'] ?? ''));
        this.richColumns.set(Array.isArray(node.attrs['columns']) ? node.attrs['columns'].join('\n') : '');
        this.richRows.set(Array.isArray(node.attrs['rows'])
            ? node.attrs['rows'].map((row: string[]) => row.join('\t')).join('\n') : '');
        this.richSummary.set(String(node.attrs['summary'] ?? ''));
    }
}
