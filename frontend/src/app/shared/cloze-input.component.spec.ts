import { TestBed } from '@angular/core/testing';

import { ClozeInputComponent } from './cloze-input.component';

describe('ClozeInputComponent', () => {
    it('keeps the first slot and native caret reachable beyond a mobile viewport', async () => {
        const fixture = TestBed.createComponent(ClozeInputComponent);
        fixture.componentRef.setInput('controlId', 'mobile-blank'); fixture.componentRef.setInput('blankLength', 24);
        const host = fixture.nativeElement as HTMLElement; host.style.width = '280px'; document.body.appendChild(host);
        try {
            fixture.detectChanges();
            const viewport = host.querySelector('.cloze-viewport') as HTMLElement;
            const line = host.querySelector('.cloze-line') as HTMLElement;
            expect(line.getBoundingClientRect().left).toBeGreaterThanOrEqual(viewport.getBoundingClientRect().left);
            expect(viewport.scrollWidth).toBeGreaterThan(viewport.clientWidth);
            const input = host.querySelector('input') as HTMLInputElement;
            input.focus();
            expect(getComputedStyle(input).outlineStyle).toBe('none');
            expect(getComputedStyle(input).borderWidth).toBe('0px');
            input.value = 'Длинный учебный ответ'; input.setSelectionRange(input.value.length, input.value.length);
            input.dispatchEvent(new KeyboardEvent('keyup')); fixture.detectChanges(); await fixture.whenStable();
            const caret = host.querySelector('.cloze-current') as HTMLElement;
            expect(caret.getBoundingClientRect().right).toBeLessThanOrEqual(viewport.getBoundingClientRect().right + 1);
            expect(viewport.scrollLeft).toBeGreaterThan(0);
        } finally { host.remove(); fixture.destroy(); }
    });

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
