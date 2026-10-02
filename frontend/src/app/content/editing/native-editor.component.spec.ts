import { TestBed } from '@angular/core/testing';
import { NodeSelection, TextSelection } from 'prosemirror-state';

import codeDocumentJson from '../../../../../contracts/content/native-v1/valid/code.json';
import { mixedNativeDocumentFixture } from '../rendering/native-renderer.fixtures';
import { NativeDocument } from '../native-document';
import { createEmptyNativeDocument, nativeEditorSchema } from './native-editor-adapter';
import { NativeEditorComponent } from './native-editor.component';

describe('NativeEditorComponent', () => {
    it('renders an inert labelled editor and synchronizes the publishing lock', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', mixedNativeDocumentFixture());
        fixture.detectChanges();

        const surface = fixture.nativeElement.querySelector('[role="textbox"]') as HTMLElement;
        expect(surface.getAttribute('aria-label')).toBe('Содержание материала');
        expect(surface.getAttribute('contenteditable')).toBe('true');
        expect(surface.innerHTML).not.toContain('onerror');
        expect(surface.querySelector('script,svg,img[src],img[onerror]')).toBeNull();
        expect(fixture.nativeElement.querySelectorAll('.mnema-unsupported').length).toBeGreaterThan(0);

        fixture.componentRef.setInput('disabled', true);
        fixture.detectChanges();
        TestBed.flushEffects();
        expect(surface.getAttribute('contenteditable')).toBe('false');
        const controls = Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[];
        expect(controls.filter(control => !control.closest('[hidden]')).every(control => control.disabled)).toBe(true);
    });

    it('shows a pencil only while the document is empty', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const host = fixture.nativeElement.querySelector('.editor-host') as HTMLElement;
        expect(host.classList.contains('is-empty')).toBe(true);
        fixture.componentInstance['editorView']!.dispatch(fixture.componentInstance['editorView']!.state.tr.insertText('А'));
        fixture.detectChanges();
        expect(host.classList.contains('is-empty')).toBe(false);
    });

    it('replaces stale editor state when the acknowledged server document changes', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        const initial = mixedNativeDocumentFixture();
        fixture.componentRef.setInput('document', initial);
        fixture.detectChanges();

        const replacement = structuredClone(initial);
        const text = replacement.root.content[0]?.content[0];
        if (text === undefined)
            throw new Error('Fixture text is absent.');
        (text.attrs as {
            text: string;
        }).text = 'Свежая версия сервера';
        fixture.componentRef.setInput('document', replacement);
        fixture.detectChanges();
        TestBed.flushEffects();

        const surface = fixture.nativeElement.querySelector('[role="textbox"]') as HTMLElement;
        expect(surface.textContent).toContain('Свежая версия сервера');
    });

    it('inserts a validated Mermaid block and rejects incomplete media references', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const emitted: NativeDocument[] = [];
        fixture.componentInstance.documentChange.subscribe(document => emitted.push(document));

        fixture.componentInstance.selectRichKind('image');
        fixture.componentInstance.richTitle.set('Схема сервиса');
        fixture.componentInstance.applyRichNode();
        fixture.detectChanges();
        expect(emitted.length).toBe(0);
        expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('сначала выберите файл');

        fixture.componentInstance.selectRichKind('mermaid');
        fixture.componentInstance.richTitle.set('Путь запроса');
        fixture.componentInstance.richDescription.set('Клиент обращается к API.');
        fixture.componentInstance.richSource.set('flowchart LR\nClient --> API');
        fixture.componentInstance.applyRichNode();
        fixture.detectChanges();

        expect(emitted.length).toBe(1);
        expect(emitted[0]?.root.content.some(node => node.type === 'mermaid')).toBe(true);
        expect(fixture.nativeElement.querySelector('.mnema-rich-atom')?.textContent).toContain('Путь запроса');
        expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    });

    it('turns short Markdown markers into a list and a divider without leaving literal markers', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const editor = fixture.componentInstance;
        const view = editor['editorView']!;

        view.dispatch(view.state.tr.insertText('-'));
        expect(editor['handleMarkdownShortcut'](view, 2, 2, ' ')).toBe(true);
        expect(view.state.doc.firstChild?.type.name).toBe('bullet_list');
        expect(view.state.doc.textContent).toBe('');

        editor.undo();
        view.dispatch(view.state.tr.replaceWith(0, view.state.doc.content.size, nativeEditorSchema.nodes['paragraph']!.create(undefined, nativeEditorSchema.text('--'))));
        expect(editor['handleMarkdownShortcut'](view, 3, 3, '-')).toBe(true);
        expect(view.state.doc.firstChild?.type.name).toBe('divider');
        expect(view.state.doc.textContent).toBe('');

        const paragraph = nativeEditorSchema.nodes['paragraph']!.create(undefined, nativeEditorSchema.text('--'));
        const listItem = nativeEditorSchema.nodes['list_item']!.create(undefined, paragraph);
        const list = nativeEditorSchema.nodes['bullet_list']!.create(undefined, listItem);
        view.dispatch(view.state.tr.replaceWith(0, view.state.doc.content.size, list));
        let markerEnd = -1;
        view.state.doc.descendants((node, position) => { if (node.isText)
            markerEnd = position + node.nodeSize; });
        expect(editor['handleMarkdownShortcut'](view, markerEnd, markerEnd, '-')).toBe(true);
        expect(view.state.doc.firstChild?.firstChild?.child(1).type.name).toBe('divider');
        expect(view.state.doc.textContent).toBe('');
    });

    it('creates and edits a safe HTTPS link around selected text', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const editor = fixture.componentInstance;
        const view = editor['editorView']!;
        const insert = view.state.tr.insertText('Книга');
        view.dispatch(insert.setSelection(TextSelection.create(insert.doc, 1, 6)));
        editor.linkHref.set('https://example.org/book');
        editor.applyLink();
        expect(editor.failure()).toBeNull();
        expect(view.state.doc.firstChild?.firstChild?.type.name).toBe('link_node');
        expect(view.state.doc.firstChild?.firstChild?.attrs['href']).toBe('https://example.org/book');

        view.dispatch(view.state.tr.setSelection(TextSelection.create(view.state.doc, 3)));
        editor.linkHref.set('javascript:alert(1)');
        editor.applyLink();
        expect(view.state.doc.firstChild?.firstChild?.attrs['href']).toBe('https://example.org/book');
        editor.linkHref.set('https://example.org/new');
        editor.applyLink();
        expect(view.state.doc.firstChild?.firstChild?.attrs['href']).toBe('https://example.org/new');
        editor.removeLink();
        expect(view.state.doc.firstChild?.textContent).toBe('Книга');
        expect(view.state.doc.firstChild?.firstChild?.type.name).toBe('text');
    });

    it('builds a two-column table through cell controls', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const editor = fixture.componentInstance;
        const emitted: NativeDocument[] = [];
        editor.documentChange.subscribe(document => emitted.push(document));

        editor.insertTable();
        expect(editor.failure()).toBeNull();
        expect(emitted.length).toBe(1);
        fixture.detectChanges();
        const host = fixture.nativeElement as HTMLElement;
        expect(host.querySelectorAll('.mnema-table-node th').length).toBe(2);
        expect(host.querySelectorAll('.mnema-table-node tbody tr').length).toBe(2);
        expect(editor.mediaInspectorOpen()).toBe(false);
        const edit = (selector: string, value: string) => {
            const input = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
            input.value = value;
            input.dispatchEvent(new Event('input'));
        };
        edit('.mnema-table-node input[aria-label="Заголовок 1"]', 'Термин');
        edit('.mnema-table-node input[aria-label="Заголовок 2"]', 'Значение');
        edit('.mnema-table-node input[aria-label="Строка 1, столбец 1"]', 'Интервал');
        edit('.mnema-table-node input[aria-label="Строка 1, столбец 2"]', 'Повторение');
        edit('.mnema-table-caption input', 'План');
        edit('.mnema-table-summary textarea', 'Краткий план');
        const table = emitted.at(-1)?.root.content.find(node => node.type === 'table');
        const columns = table?.attrs['columns'] as string[] | undefined;
        const rows = table?.attrs['rows'] as string[][] | undefined;
        expect(columns).toEqual(['Термин', 'Значение']);
        expect(rows).toEqual([['Интервал', 'Повторение'], ['', '']]);
        const caption = table?.attrs['caption'] as string | undefined;
        const summary = table?.attrs['summary'] as string | undefined;
        expect(caption).toBe('План');
        expect(summary).toBe('Краткий план');
        const cell = host.querySelector<HTMLInputElement>('.mnema-table-node input[aria-label="Строка 2, столбец 1"]')!;
        cell.value = 'Пауза';
        cell.dispatchEvent(new Event('input'));
        expect((emitted.at(-1)?.root.content.find(node => node.type === 'table')?.attrs['rows'] as string[][])[1]?.[0])
            .toBe('Пауза');
        const remove = Array.from(host.querySelectorAll<HTMLButtonElement>('.mnema-table-actions button'))
            .find(button => button.textContent === 'Удалить последний столбец')!;
        remove.click();
        expect(host.querySelectorAll('.mnema-table-node th').length).toBe(1);
        expect((emitted.at(-1)?.root.content.find(node => node.type === 'table')?.attrs['columns'] as string[]).length)
            .toBe(1);
    });

    it('uses selected text as the base for a ruby reading', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const editor = fixture.componentInstance;
        const view = editor['editorView']!;
        const insert = view.state.tr.insertText('漢');
        view.dispatch(insert.setSelection(TextSelection.create(insert.doc, 1, 2)));
        expect(editor.rubyBase()).toBe('漢');
        editor.rubyReading.set('かん');
        editor.insertRuby();
        expect(view.state.doc.firstChild?.firstChild?.type.name).toBe('ruby');
        expect(view.state.doc.firstChild?.firstChild?.attrs['reading']).toBe('かん');
    });

    it('offers link and reading actions next to selected text instead of a permanent form', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const editor = fixture.componentInstance;
        const view = editor['editorView']!;
        expect(fixture.nativeElement.querySelector('.link-tools, .ruby-fields')).toBeNull();

        const insert = view.state.tr.insertText('Книга');
        view.dispatch(insert.setSelection(TextSelection.create(insert.doc, 1, 6)));
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('.selection-actions')).not.toBeNull();
        editor.openLinkTools();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('.link-tools')).not.toBeNull();
        expect(fixture.nativeElement.querySelector('.selection-actions')).toBeNull();
        editor.closePopover();
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('.link-tools')).toBeNull();
    });

    it('applies a rich block on Enter, keeps Shift+Enter in textarea, and closes the inspector', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const editor = fixture.componentInstance;
        expect(editor.mediaInspectorOpen()).toBe(false);
        editor.openMediaTools();
        editor.selectRichKind('mermaid');
        editor.richTitle.set('Схема');
        editor.richDescription.set('Описание');
        editor.richSource.set('flowchart LR\nA --> B');
        fixture.detectChanges();
        const tools = fixture.nativeElement.querySelector('.rich-tools') as HTMLElement;
        expect(tools.hidden).toBe(false);
        const source = tools.querySelector('textarea[spellcheck="false"]') as HTMLTextAreaElement;
        source.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true, bubbles: true, cancelable: true }));
        expect(tools.hidden).toBe(false);
        const plainEnter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
        source.dispatchEvent(plainEnter);
        fixture.detectChanges();
        expect(plainEnter.defaultPrevented).toBe(true);
        expect(tools.hidden).toBe(true);
        expect(editor['editorView']!.state.doc.firstChild?.type.name).toBe('mermaid');
    });

    it('accepts pasted files and turns a pasted YouTube URL into a video block', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const editor = fixture.componentInstance;
        const view = editor['editorView']!;
        const received: (readonly File[])[] = [];
        editor.filesAdded.subscribe(files => received.push(files));
        const file = new File(['image'], 'photo.png', { type: 'image/png' });
        const fileEvent = { clipboardData: { files: [file], getData: () => '' } } as unknown as ClipboardEvent;
        expect(editor['handlePaste'](view, fileEvent)).toBe(true);
        expect(received[0]).toEqual([file]);
        const dropEvent = { dataTransfer: { files: [file] }, clientX: 0, clientY: 0 } as unknown as DragEvent;
        expect(editor['handleDrop'](view, dropEvent)).toBe(true);
        expect(received[1]).toEqual([file]);

        const url = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ';
        const linkEvent = { clipboardData: { files: [], getData: () => url } } as unknown as ClipboardEvent;
        expect(editor['handlePaste'](view, linkEvent)).toBe(true);
        expect(view.state.doc.firstChild?.type.name).toBe('youtube');
        expect(view.state.doc.firstChild?.attrs['videoId']).toBe('dQw4w9WgXcQ');
        expect(editor.richKind()).toBe('youtube');
    });

    it('updates the reading of a selected ruby node in place', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        const editor = fixture.componentInstance;
        const view = editor['editorView']!;

        editor.rubyBase.set('漢');
        editor.rubyReading.set('かん');
        editor.insertRuby();
        const originalId = view.state.doc.firstChild?.firstChild?.attrs['id'];
        view.dispatch(view.state.tr.setSelection(NodeSelection.create(view.state.doc, 1)));
        expect(editor.rubySelected()).toBe(true);
        editor.rubyReading.set('ハン');
        editor.insertRuby();

        expect(view.state.doc.firstChild?.firstChild?.attrs['id']).toBe(originalId);
        expect(view.state.doc.firstChild?.firstChild?.attrs['reading']).toBe('ハン');
        expect(view.state.doc.firstChild?.childCount).toBe(1);
    });

    describe('code blocks', () => {
        function open(document: NativeDocument = createEmptyNativeDocument()): {
            fixture: ReturnType<typeof TestBed.createComponent<NativeEditorComponent>>;
            emitted: NativeDocument[];
        } {
            TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
            const fixture = TestBed.createComponent(NativeEditorComponent);
            fixture.componentRef.setInput('document', document);
            fixture.detectChanges();
            const emitted: NativeDocument[] = [];
            fixture.componentInstance.documentChange.subscribe(value => emitted.push(value));
            return { fixture, emitted };
        }

        function type(textarea: HTMLTextAreaElement, value: string): void {
            textarea.value = value;
            textarea.dispatchEvent(new Event('input', { bubbles: true }));
        }

        it('inserts an empty block that is not emitted until it has code, then keeps tabs and line breaks exactly', () => {
            const { fixture, emitted } = open();
            fixture.componentInstance.insertCodeBlock();
            fixture.detectChanges();
            const host = fixture.nativeElement as HTMLElement;
            const textarea = host.querySelector<HTMLTextAreaElement>('.mnema-code-node textarea')!;
            expect(textarea).not.toBeNull();
            expect(host.querySelector('[role="alert"]')).toBeNull();
            // the empty block is not part of the document; the empty paragraph still is
            expect(emitted.at(-1)?.root.content.map(node => node.type)).toEqual(['paragraph']);

            type(textarea, 'SELECT 1;\n\tFROM t   \n');
            const language = host.querySelector<HTMLInputElement>('.mnema-code-node input')!;
            language.value = 'sql';
            language.dispatchEvent(new Event('input', { bubbles: true }));
            const code = emitted.at(-1)!.root.content.find(node => node.type === 'code_block')!;
            expect(code.attrs).toEqual({ lang: 'sql', source: 'SELECT 1;\n\tFROM t   \n' });
        });

        it('inserts a tab on Tab, leaves on Escape and never traps Shift+Tab', () => {
            const { fixture, emitted } = open();
            fixture.componentInstance.insertCodeBlock();
            fixture.detectChanges();
            const host = fixture.nativeElement as HTMLElement;
            const textarea = host.querySelector<HTMLTextAreaElement>('.mnema-code-node textarea')!;
            type(textarea, 'ab');
            textarea.setSelectionRange(1, 1);
            const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
            textarea.dispatchEvent(tab);
            expect(tab.defaultPrevented).toBe(true);
            expect(textarea.value).toBe('a\tb');
            expect(emitted.at(-1)!.root.content.find(node => node.type === 'code_block')!.attrs['source']).toBe('a\tb');

            const back = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true });
            textarea.dispatchEvent(back);
            expect(back.defaultPrevented).toBe(false);
            expect(textarea.value).toBe('a\tb');

            const view = fixture.componentInstance['editorView']!;
            const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
            textarea.dispatchEvent(escape);
            expect(escape.defaultPrevented).toBe(true);
            expect(view.state.selection).toBeInstanceOf(NodeSelection);
            expect((view.state.selection as NodeSelection).node.type.name).toBe('code_block');
            expect(host.querySelector('.mnema-code-node p.mnema-code-hint')?.textContent).toContain('Esc');
        });

        it('keeps an invalid language out of the document and flags the field', () => {
            const { fixture, emitted } = open();
            fixture.componentInstance.insertCodeBlock();
            fixture.detectChanges();
            const host = fixture.nativeElement as HTMLElement;
            type(host.querySelector<HTMLTextAreaElement>('.mnema-code-node textarea')!, 'x');
            const language = host.querySelector<HTMLInputElement>('.mnema-code-node input')!;
            language.value = 'sql server';
            language.dispatchEvent(new Event('input', { bubbles: true }));
            expect(language.getAttribute('aria-invalid')).toBe('true');
            expect(emitted.at(-1)!.root.content.find(node => node.type === 'code_block')!.attrs).toEqual({ source: 'x' });
            // uppercase is normalized, not rejected
            language.value = 'SQL';
            language.dispatchEvent(new Event('input', { bubbles: true }));
            language.dispatchEvent(new Event('change', { bubbles: true }));
            expect(language.getAttribute('aria-invalid')).toBeNull();
            expect(language.value).toBe('sql');
            expect(emitted.at(-1)!.root.content.find(node => node.type === 'code_block')!.attrs['lang']).toBe('sql');
        });

        it('opens a document with a code block and disables its fields with the editor', () => {
            const { fixture } = open(codeDocumentJson as unknown as NativeDocument);
            const host = fixture.nativeElement as HTMLElement;
            expect(host.querySelectorAll('.mnema-code-node').length).toBe(4);
            const first = host.querySelector<HTMLTextAreaElement>('.mnema-code-node textarea')!;
            expect(first.value).toContain('EXPLAIN (ANALYZE, BUFFERS)');
            expect(first.dir).toBe('ltr');
            fixture.componentRef.setInput('disabled', true);
            fixture.detectChanges();
            TestBed.flushEffects();
            expect(first.disabled).toBe(true);
        });
    });
});
