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
import { EditorState } from 'prosemirror-state';
import { EditorView } from 'prosemirror-view';

import { NativeDocument } from '../native-document';
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
    }
}
