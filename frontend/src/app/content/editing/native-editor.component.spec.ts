import { TestBed } from '@angular/core/testing';

import { mixedNativeDocumentFixture } from '../rendering/native-renderer.fixtures';
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
});
