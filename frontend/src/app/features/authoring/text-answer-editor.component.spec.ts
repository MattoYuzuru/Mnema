import { TestBed } from '@angular/core/testing';

import { TextAnswerDraft } from './exercise-draft';
import { TextAnswerEditorComponent } from './text-answer-editor.component';

describe('TextAnswerEditorComponent', () => {
    const answer: TextAnswerDraft = { rows: [{ id: 'a', value: 'Erinnerung' }], normalization: ['UNICODE_NFC', 'TRIM', 'CASE_FOLD'], matchingMode: 'STRICT' };

    function create(value = answer, error: string | null = null) {
        const fixture = TestBed.createComponent(TextAnswerEditorComponent);
        fixture.componentRef.setInput('answer', value);
        fixture.componentRef.setInput('idPrefix', 'answer');
        fixture.componentRef.setInput('error', error);
        fixture.detectChanges();
        return fixture;
    }

    it('explains that one alternative is enough and that soft matching is not semantic checking', () => {
        const root = create().nativeElement as HTMLElement;
        expect(root.textContent).toContain('Достаточно одной альтернативы');
        expect(root.textContent).toContain('До 20 вариантов, каждый до 512 знаков');
        expect(root.textContent).toContain('Это не проверка смысла');
    });

    it('adds, edits and removes alternatives within the limit and never removes the last one', () => {
        const fixture = create();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('button[aria-label^="Убрать вариант"]')).toBeNull();
        buttonText(root, '+ Добавить альтернативу').click(); fixture.detectChanges();
        expect(fixture.componentInstance.answer().rows.length).toBe(2);
        const second = root.querySelectorAll<HTMLInputElement>('input[type="text"]')[1];
        second.value = 'die Erinnerung'; second.dispatchEvent(new Event('input'));
        expect(fixture.componentInstance.answer().rows[1].value).toBe('die Erinnerung');
        root.querySelector<HTMLButtonElement>('button[aria-label="Убрать вариант 2"]')!.click(); fixture.detectChanges();
        expect(fixture.componentInstance.answer().rows.length).toBe(1);
        fixture.componentInstance.remove('a');
        expect(fixture.componentInstance.answer().rows.length).toBe(1);

        fixture.componentRef.setInput('max', 1); fixture.detectChanges();
        expect(buttonText(root, '+ Добавить альтернативу').disabled).toBeTrue();
        fixture.componentInstance.add();
        expect(fixture.componentInstance.answer().rows.length).toBe(1);
    });

    it('toggles comparison rules keeping their canonical order and switches the matching mode', () => {
        const fixture = create();
        const root = fixture.nativeElement as HTMLElement;
        const boxes = root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]');
        boxes[1].click(); fixture.detectChanges();
        expect(fixture.componentInstance.answer().normalization).toEqual(['UNICODE_NFC', 'CASE_FOLD']);
        boxes[1].click(); fixture.detectChanges();
        expect(fixture.componentInstance.answer().normalization).toEqual(['UNICODE_NFC', 'TRIM', 'CASE_FOLD']);
        const radios = root.querySelectorAll<HTMLInputElement>('input[type="radio"]');
        radios[1].click(); fixture.detectChanges();
        expect(fixture.componentInstance.answer().matchingMode).toBe('SOFT');
        radios[0].click(); fixture.detectChanges();
        expect(fixture.componentInstance.answer().matchingMode).toBe('STRICT');
    });

    it('announces its error and marks the inputs invalid', () => {
        const root = create(answer, 'Заполните ответы').nativeElement as HTMLElement;
        expect(root.querySelector('[role="alert"]')?.textContent).toContain('Заполните ответы');
        expect(root.querySelector('input[type="text"]')?.getAttribute('aria-invalid')).toBe('true');
        expect(root.querySelector('fieldset')?.getAttribute('aria-describedby')).toBe('answer-error');
    });

    function buttonText(root: HTMLElement, label: string): HTMLButtonElement {
        return [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === label)!;
    }
});
