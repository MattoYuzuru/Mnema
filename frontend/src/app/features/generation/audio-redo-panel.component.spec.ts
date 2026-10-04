import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SpeechVoice } from './generation.models';
import { AudioRedoPanelComponent } from './audio-redo-panel.component';

describe('AudioRedoPanelComponent', () => {
    let fixture: ComponentFixture<AudioRedoPanelComponent>;
    let submitted: (SpeechVoice | null)[];
    let cancelled: number;
    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const radios = (): HTMLInputElement[] => [...root().querySelectorAll<HTMLInputElement>('input[type="radio"]')];
    const button = (label: string): HTMLButtonElement => [...root().querySelectorAll('button')].find(held => held.textContent!.trim() === label)!;

    async function create(inputs: Record<string, unknown> = {}): Promise<void> {
        TestBed.resetTestingModule();
        fixture = TestBed.createComponent(AudioRedoPanelComponent);
        submitted = [];
        cancelled = 0;
        fixture.componentInstance.submitted.subscribe(value => submitted.push(value));
        fixture.componentInstance.cancelled.subscribe(() => cancelled++);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        window.document.body.appendChild(root());
        fixture.detectChanges();
        await fixture.whenStable();
    }
    afterEach(() => root().remove());

    it('is a group, not a dialog: a native radio pair «Женский голос / Мужской голос» with the voice of the clip checked and focused', async () => {
        await create({ voice: 'male' });
        expect(root().querySelector('[role="group"]')!.getAttribute('aria-label')).toBe('Озвучить заново');
        expect(root().querySelector('dialog, [role="dialog"], [popover]')).toBeNull();
        expect(root().querySelector('legend')!.textContent).toBe('Голос');
        expect(radios().map(radio => radio.closest('label')!.textContent!.trim())).toEqual(['Женский голос', 'Мужской голос']);
        expect(radios().map(radio => radio.checked)).toEqual([false, true]);
        expect(new Set(radios().map(radio => radio.name)).size).toBe(1);
        expect(window.document.activeElement).toBe(radios()[1]);
        expect([...root().querySelectorAll('button')].map(held => held.textContent!.trim())).toEqual(['Озвучить', 'Отмена']);
    });

    it('says what a redo costs, calmly, and ties the hint to the voices', async () => {
        await create();
        const hint = root().querySelector('.panel-hint')!;
        expect(hint.textContent).toBe('Тем же голосом — новая запись (до 10 кредитов). Другим голосом — бесплатно, если такой текст уже озвучивали.');
        expect(root().querySelector('fieldset')!.getAttribute('aria-describedby')).toContain(hint.id);
    });

    it('sends no voice when the voice is the one the clip has, and the voice when it is another one', async () => {
        await create({ voice: 'female' });
        button('Озвучить').click();
        radios()[1]!.click();
        fixture.detectChanges();
        expect(radios().map(radio => radio.checked)).toEqual([false, true]);
        button('Озвучить').click();
        radios()[0]!.click();
        button('Озвучить').click();
        expect(submitted).toEqual([null, 'male', null]);
    });

    it('closes with «Отмена» and Esc, but Esc during an IME composition does not', async () => {
        await create();
        button('Отмена').click();
        const group = root().querySelector('[role="group"]')!;
        group.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', isComposing: true, bubbles: true, cancelable: true }));
        expect(cancelled).toBe(1);
        const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
        radios()[0]!.dispatchEvent(escape);
        expect(escape.defaultPrevented).toBe(true);
        expect(cancelled).toBe(2);
    });

    it('while the clip is made: says «Озвучиваю…» in a status, aria-disabled controls (never disabled), focus kept, nothing sent', async () => {
        await create({ voice: 'female' });
        const status = root().querySelector('[role="status"]')!;
        expect(status.textContent).toBe('');
        fixture.componentRef.setInput('pending', true);
        fixture.detectChanges();
        expect(status.textContent).toBe('Озвучиваю…');
        expect(root().querySelector('button[disabled], input[disabled], fieldset[disabled]')).toBeNull();
        expect([...root().querySelectorAll('button')].every(held => held.getAttribute('aria-disabled') === 'true')).toBe(true);
        expect(radios().every(radio => radio.getAttribute('aria-disabled') === 'true')).toBe(true);
        expect(window.document.activeElement).toBe(radios()[0]);
        button('Озвучить').click();
        button('Отмена').click();
        radios()[1]!.click();
        expect(radios()[1]!.checked).toBe(false);
        expect(submitted).toEqual([]);
        expect(cancelled).toBe(0);
    });

    it('shows a refusal in a polite live region that is there before it speaks', async () => {
        await create();
        const error = root().querySelector<HTMLElement>('.panel-error')!;
        expect(error.getAttribute('aria-live')).toBe('polite');
        expect(error.textContent).toBe('');
        fixture.componentRef.setInput('error', 'Не хватает лимита ИИ.');
        fixture.detectChanges();
        expect(error.textContent).toBe('Не хватает лимита ИИ.');
        expect(root().querySelector('fieldset')!.getAttribute('aria-describedby')).toContain(error.id);
    });
});
