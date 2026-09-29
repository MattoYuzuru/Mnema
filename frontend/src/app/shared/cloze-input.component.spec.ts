import { TestBed } from '@angular/core/testing';

import { ClozeInputComponent } from './cloze-input.component';

describe('ClozeInputComponent', () => {
    it('keeps native typing, selection and deletion beyond the visible blank length', () => {
        TestBed.configureTestingModule({ imports: [ClozeInputComponent] });
        const fixture = TestBed.createComponent(ClozeInputComponent);
        fixture.componentRef.setInput('controlId', 'cloze-test');
        fixture.componentRef.setInput('blankLength', 5);
        fixture.detectChanges();
        const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
        const values: string[] = [];
        fixture.componentInstance.valueChange.subscribe(value => values.push(value));
        expect(fixture.componentInstance.slotCount()).toBe(5);

        input.value = 'длинный ответ';
        input.setSelectionRange(7, 7);
        input.dispatchEvent(new Event('input', { bubbles: true }));
        fixture.componentRef.setInput('value', values.at(-1));
        fixture.detectChanges();
        expect(values.at(-1)).toBe('длинный ответ');
        expect(fixture.componentInstance.slotCount()).toBeGreaterThan(5);
        expect(fixture.componentInstance.caretIndex()).toBe(7);

        input.value = 'длинный';
        input.setSelectionRange(7, 7);
        input.dispatchEvent(new Event('input', { bubbles: true }));
        fixture.componentRef.setInput('value', values.at(-1));
        fixture.detectChanges();
        expect(fixture.componentInstance.slotCount()).toBe(8);
        expect((fixture.nativeElement.querySelector('label') as HTMLLabelElement).htmlFor).toBe('cloze-test');
    });
});
