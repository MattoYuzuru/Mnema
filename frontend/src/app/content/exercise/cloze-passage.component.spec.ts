import { TestBed } from '@angular/core/testing';

import { ClozePassageComponent } from './cloze-passage.component';
import { LearnerClozeSegment } from './exercise-content.models';

describe('ClozePassageComponent', () => {
    const passage: LearnerClozeSegment[] = [
        { kind: 'TEXT', text: 'list.stream()\n    .' },
        { kind: 'BLANK', blankId: 'b1a00000-0000-4000-8000-000000000001', size: { mode: 'ANSWER_LENGTH', length: 3 }, firstLetterHint: true },
        { kind: 'TEXT', text: '(x)\n    .' },
        { kind: 'BLANK', blankId: 'b1a00000-0000-4000-8000-000000000002', size: { mode: 'FIXED', length: 20 }, firstLetterHint: false }
    ];

    function create() {
        const fixture = TestBed.createComponent(ClozePassageComponent);
        fixture.componentRef.setInput('passage', passage);
        fixture.detectChanges();
        return fixture;
    }

    it('keeps code line breaks, sizes each blank by its own length and numbers the inputs', () => {
        const fixture = create();
        const root = fixture.nativeElement as HTMLElement;
        const texts = [...root.querySelectorAll('.cloze-text')].map(node => node.textContent);
        expect(texts).toEqual(['list.stream()\n    .', '(x)\n    .']);
        const inputs = root.querySelectorAll<HTMLInputElement>('input');
        expect(inputs.length).toBe(2);
        expect(inputs[0].style.inlineSize).toContain('3ch');
        expect(inputs[1].style.inlineSize).toContain('20ch');
        expect(inputs[1].getAttribute('aria-label')).toBe('Пропуск 2 из 2');
        expect(inputs[0].getAttribute('data-answer-control')).toBe('');
    });

    it('emits per-blank values and hint requests, and shows only the server letter', () => {
        const fixture = create();
        const root = fixture.nativeElement as HTMLElement;
        const events: unknown[] = [];
        fixture.componentInstance.valueChange.subscribe(value => events.push(value));
        fixture.componentInstance.hintRequested.subscribe(value => events.push(value));
        const input = root.querySelector<HTMLInputElement>('input')!;
        input.value = 'map';
        input.dispatchEvent(new Event('input'));
        root.querySelector<HTMLButtonElement>('.cloze-hint')!.click();
        expect(events).toEqual([{ blankId: 'b1a00000-0000-4000-8000-000000000001', text: 'map' },
            'b1a00000-0000-4000-8000-000000000001']);
        expect(root.querySelectorAll('.cloze-hint').length).toBe(1);

        fixture.componentRef.setInput('hints', { 'b1a00000-0000-4000-8000-000000000001': 'm' });
        fixture.componentRef.setInput('hintPending', 'b1a00000-0000-4000-8000-000000000001');
        fixture.detectChanges();
        expect(root.querySelector('.cloze-letter')?.textContent).toContain('m');
        expect(root.querySelector('.cloze-hint')).toBeNull();
    });

    it('disables the hint button while its request is pending and is read-only after the answer', () => {
        const fixture = create();
        fixture.componentRef.setInput('hintPending', 'b1a00000-0000-4000-8000-000000000001');
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector<HTMLButtonElement>('.cloze-hint')?.disabled).toBe(true);
        fixture.componentRef.setInput('readOnly', true);
        fixture.componentRef.setInput('values', { 'b1a00000-0000-4000-8000-000000000001': 'map' });
        fixture.componentRef.setInput('verdicts', { 'b1a00000-0000-4000-8000-000000000001': { correct: false, hinted: true, reference: 'map' } });
        fixture.detectChanges();
        expect(root.querySelector('.cloze-hint')).toBeNull();
        expect(root.querySelector<HTMLInputElement>('input')?.readOnly).toBe(true);
        expect(root.querySelector('.is-wrong .cloze-verdict')?.textContent?.replace(/\s+/g, ' ')).toContain('Неверно, ответ: map, с подсказкой');
    });
});
