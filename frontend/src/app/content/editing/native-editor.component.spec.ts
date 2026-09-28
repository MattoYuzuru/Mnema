import { TestBed } from '@angular/core/testing';

import { mixedNativeDocumentFixture } from '../rendering/native-renderer.fixtures';
import { NativeDocument } from '../native-document';
import { createEmptyNativeDocument } from './native-editor-adapter';
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
        expect(controls.every(control => control.disabled)).toBeTrue();
    });

    it('replaces stale editor state when the acknowledged server document changes', () => {
        TestBed.configureTestingModule({ imports: [NativeEditorComponent] });
        const fixture = TestBed.createComponent(NativeEditorComponent);
        const initial = mixedNativeDocumentFixture();
        fixture.componentRef.setInput('document', initial);
        fixture.detectChanges();

        const replacement = structuredClone(initial);
        const text = replacement.root.content[0]?.content[0];
        if (text === undefined) throw new Error('Fixture text is absent.');
        (text.attrs as { text: string }).text = 'Свежая версия сервера';
        fixture.componentRef.setInput('document', replacement);
        fixture.detectChanges();
        TestBed.flushEffects();

        const surface = fixture.nativeElement.querySelector('[role="textbox"]') as HTMLElement;
        expect(surface.innerText).toContain('Свежая версия сервера');
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
        expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('ID файла');

        fixture.componentInstance.selectRichKind('mermaid');
        fixture.componentInstance.richTitle.set('Путь запроса');
        fixture.componentInstance.richDescription.set('Клиент обращается к API.');
        fixture.componentInstance.richSource.set('flowchart LR\nClient --> API');
        fixture.componentInstance.applyRichNode();
        fixture.detectChanges();

        expect(emitted.length).toBe(1);
        expect(emitted[0]?.root.content.some(node => node.type === 'mermaid')).toBeTrue();
        expect(fixture.nativeElement.querySelector('.mnema-rich-atom')?.textContent).toContain('Путь запроса');
        expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    });
});
