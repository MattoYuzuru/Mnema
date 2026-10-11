import { TestBed } from '@angular/core/testing';

import { SegmentedChoiceComponent, SegmentedOption } from './segmented-choice.component';

describe('SegmentedChoiceComponent', () => {
    const options: readonly SegmentedOption[] = [
        { value: 'AUTO', label: 'Авто', hint: 'Мнема выберет объём по заметке.' },
        { value: 'BRIEF', label: 'Кратко', hint: 'Определение и один пример.' },
        { value: 'FULL', label: 'Подробно' }
    ];

    function create(value: string | null = 'AUTO', extra: Record<string, unknown> = {}) {
        const fixture = TestBed.createComponent(SegmentedChoiceComponent<string>);
        fixture.componentRef.setInput('legend', 'Подробность');
        fixture.componentRef.setInput('options', options);
        fixture.componentRef.setInput('value', value);
        for (const [key, input] of Object.entries(extra)) fixture.componentRef.setInput(key, input);
        fixture.detectChanges();
        return fixture;
    }

    const radios = (root: HTMLElement) => [...root.querySelectorAll<HTMLInputElement>('input[type="radio"]')];

    it('is a native radio group named by its legend, one labelled radio per option', () => {
        const root = create().nativeElement as HTMLElement;
        expect(root.querySelector('fieldset > legend')?.textContent?.trim()).toBe('Подробность');
        const inputs = radios(root);
        expect(inputs.map(input => input.labels?.[0]?.textContent?.trim())).toEqual(['Авто', 'Кратко', 'Подробно']);
        expect(inputs.map(input => input.value)).toEqual(['AUTO', 'BRIEF', 'FULL']);
        expect(new Set(inputs.map(input => input.name)).size).toBe(1);
        expect(inputs.map(input => input.checked)).toEqual([true, false, false]);
        expect(root.querySelectorAll('.mark[aria-hidden="true"]').length).toBe(3);
    });

    it('can keep the legend for assistive technology without drawing it', () => {
        const visible = create().nativeElement as HTMLElement;
        expect(visible.querySelector('legend')?.classList.contains('visually-hidden')).toBe(false);
        const hidden = create('AUTO', { legendHidden: true }).nativeElement as HTMLElement;
        expect(hidden.querySelector('legend')?.classList.contains('visually-hidden')).toBe(true);
        expect(hidden.querySelector('fieldset > legend')?.textContent?.trim()).toBe('Подробность');
    });

    it('describes the fieldset by a polite live hint that follows the selection', () => {
        const fixture = create();
        const root = fixture.nativeElement as HTMLElement;
        const fieldset = root.querySelector('fieldset')!;
        const hint = root.querySelector<HTMLElement>(`#${fieldset.getAttribute('aria-describedby')}`)!;
        expect(hint.getAttribute('aria-live')).toBe('polite');
        expect(hint.textContent).toBe('Мнема выберет объём по заметке.');

        radios(root)[1].click();
        fixture.detectChanges();
        expect(fixture.componentInstance.value()).toBe('BRIEF');
        expect(root.querySelector(`#${fieldset.getAttribute('aria-describedby')}`)).toBe(hint);
        expect(hint.textContent).toBe('Определение и один пример.');

        radios(root)[2].click();
        fixture.detectChanges();
        expect(fixture.componentInstance.value()).toBe('FULL');
        expect(hint.textContent).toBe('');
    });

    it('keeps an empty live region while nothing is selected and emits the new value', () => {
        const fixture = create(null);
        const root = fixture.nativeElement as HTMLElement;
        expect(radios(root).some(input => input.checked)).toBe(false);
        expect(root.querySelector('[aria-live="polite"]')?.textContent).toBe('');
        const emitted: (string | null)[] = [];
        fixture.componentInstance.value.subscribe(value => emitted.push(value));
        radios(root)[0].click();
        expect(emitted).toEqual(['AUTO']);
    });

    it('uses a supplied group name and generates distinct ones otherwise', () => {
        expect(radios(create('AUTO', { name: 'detail' }).nativeElement as HTMLElement).every(input => input.name === 'detail')).toBe(true);
        const first = radios(create().nativeElement as HTMLElement)[0].name;
        const second = radios(create().nativeElement as HTMLElement)[0].name;
        expect(first).not.toBe(second);
    });

    it('disables single options or the whole group', () => {
        const fixture = create('AUTO', { options: [options[0], { ...options[1], disabled: true }] });
        const root = fixture.nativeElement as HTMLElement;
        expect(radios(root).map(input => input.disabled)).toEqual([false, true]);
        expect(root.querySelector('fieldset')?.disabled).toBe(false);
        fixture.componentRef.setInput('disabled', true);
        fixture.detectChanges();
        expect(root.querySelector('fieldset')?.disabled).toBe(true);
    });

    it('renders a row next to the segments that hosts projected content such as a toggletip', () => {
        const fixture = TestBed.createComponent(SegmentedChoiceComponent<string>);
        fixture.componentRef.setInput('legend', 'Подробность');
        fixture.componentRef.setInput('options', options);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).querySelector('.row')).not.toBeNull();
    });

    it('can be busy without losing the focus: aria-disabled radios that refuse a change, not a disabled fieldset', () => {
        const fixture = create('AUTO', { busy: true });
        const root = fixture.nativeElement as HTMLElement;
        const group = root.querySelector('fieldset')!;
        expect(group.disabled).toBe(false);
        expect(group.getAttribute('aria-busy')).toBe('true');
        expect(radios(root).every(radio => radio.getAttribute('aria-disabled') === 'true' && !radio.disabled)).toBe(true);
        radios(root)[0].focus();
        radios(root)[1].click();
        fixture.detectChanges();
        expect(fixture.componentInstance.value()).toBe('AUTO');
        expect(radios(root)[0].checked).toBe(true);
        expect(document.activeElement).toBe(radios(root)[0]);
        fixture.componentRef.setInput('busy', false);
        fixture.detectChanges();
        radios(root)[1].click();
        expect(fixture.componentInstance.value()).toBe('BRIEF');
    });

    it('names an error that describes the group and marks it invalid, after its own live hint', () => {
        const fixture = create(null, { describedBy: 'reason-error', invalid: true });
        const group = (fixture.nativeElement as HTMLElement).querySelector('fieldset')!;
        expect(group.getAttribute('aria-describedby')).toMatch(/^mn-segmented-\d+-hint reason-error$/u);
        expect(group.getAttribute('aria-invalid')).toBe('true');
        fixture.componentRef.setInput('describedBy', null);
        fixture.componentRef.setInput('invalid', false);
        fixture.detectChanges();
        expect(group.getAttribute('aria-describedby')).toMatch(/^mn-segmented-\d+-hint$/u);
        expect(group.hasAttribute('aria-invalid')).toBe(false);
    });
});
