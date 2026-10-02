import { TestBed } from '@angular/core/testing';

import { UsageMeterComponent } from './usage-meter.component';

const NBSP = '\u00a0';

describe('UsageMeterComponent', () => {
    function create(inputs: Record<string, unknown>) {
        const fixture = TestBed.createComponent(UsageMeterComponent);
        fixture.componentRef.setInput('label', 'ИИ в октябре');
        fixture.componentRef.setInput('total', 100);
        fixture.componentRef.setInput('used', 0);
        for (const [key, value] of Object.entries(inputs)) fixture.componentRef.setInput(key, value);
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        return {
            fixture, root,
            summary: () => root.querySelector('.summary')!.textContent,
            bar: () => root.querySelector<HTMLElement>('.bar')!
        };
    }

    it('states usage, what is left and when the bar renews in text, and hides the bar', () => {
        const { root, summary, bar } = create({
            used: 38, remaining: { count: 12, forms: ['материал', 'материала', 'материалов'] }, resetsOn: '6 октября'
        });
        expect(root.querySelector('.label')?.textContent).toBe('ИИ в октябре');
        expect(summary()).toBe(`Использовано 38${NBSP}%, хватит на ≈${NBSP}12${NBSP}материалов, обновится 6 октября.`);
        expect(bar().getAttribute('aria-hidden')).toBe('true');
        expect(bar().querySelector<HTMLElement>('.used')?.style.inlineSize).toBe('38%');
        expect(bar().querySelector('.reserved, .locked, .tick')).toBeNull();
    });

    it('counts reserved work in the percentage, like percentUsed, and draws it after the used part', () => {
        const { summary, bar } = create({ total: 360, used: 42, reserved: 10 });
        expect(summary()).toBe(`Использовано 14${NBSP}%, из них 3${NBSP}% зарезервировано.`);
        expect(bar().querySelector<HTMLElement>('.used')?.style.inlineSize).toBe(`${42 / 360 * 100}%`);
        const reserved = bar().querySelector<HTMLElement>('.reserved')!;
        expect(reserved.style.insetInlineStart).toBe(`${42 / 360 * 100}%`);
        expect(reserved.style.inlineSize).toBe(`${10 / 360 * 100}%`);
    });

    it('maps the Free weekly unlock: locked remainder, next unlock date and tick marks', () => {
        const { summary, bar } = create({
            total: 50, used: 8, unlocked: 13, ticks: [13 / 50, 26 / 50, 38 / 50, 1], nextUnlock: '4 октября',
            remaining: { count: 5, forms: ['материал', 'материала', 'материалов'] }, resetsOn: '1 ноября'
        });
        expect(summary()).toBe(`Использовано 16${NBSP}%, хватит на ≈${NBSP}5${NBSP}материалов, ещё 74${NBSP}% откроется 4 октября, обновится 1 ноября.`);
        expect(bar().querySelector<HTMLElement>('.locked')?.style.insetInlineStart).toBe('26%');
        expect([...bar().querySelectorAll<HTMLElement>('.tick')].map(tick => tick.style.insetInlineStart))
            .toEqual(['26%', '52%', '76%']);
    });

    it('says «позже» when a locked part has no known date and omits the locked remainder when all is unlocked', () => {
        expect(create({ unlocked: 40 }).summary()).toContain(`ещё 60${NBSP}% откроется позже`);
        const full = create({ unlocked: 100 });
        expect(full.summary()).not.toContain('откроется');
        expect(full.bar().querySelector('.locked')).toBeNull();
    });

    it('chooses the Russian plural form of the remaining count', () => {
        const forms = ['материал', 'материала', 'материалов'] as const;
        const text = (count: number) => create({ remaining: { count, forms } }).summary();
        expect(text(1)).toContain(`≈${NBSP}1${NBSP}материал.`);
        expect(text(2)).toContain(`${NBSP}материала`);
        expect(text(5)).toContain(`${NBSP}материалов`);
        expect(text(11)).toContain(`${NBSP}материалов`);
        expect(text(21)).toContain(`21${NBSP}материал.`);
        expect(text(112)).toContain(`${NBSP}материалов`);
        expect(text(-3)).toContain(`≈${NBSP}0${NBSP}материалов`);
    });

    it('clamps out-of-range values and drops invalid ticks', () => {
        const over = create({ used: 150, reserved: 20, unlocked: 500, ticks: [-0.1, 0, 0.5, 0.5, 1.2, Number.NaN] });
        expect(over.summary()).toBe(`Использовано 100${NBSP}%, из них 0${NBSP}% зарезервировано.`.replace(', из них 0 % зарезервировано', ''));
        expect(over.bar().querySelector<HTMLElement>('.used')?.style.inlineSize).toBe('100%');
        expect(over.bar().querySelector('.reserved')).toBeNull();
        expect(over.bar().querySelector('.locked')).toBeNull();
        expect([...over.bar().querySelectorAll<HTMLElement>('.tick')].map(tick => tick.style.insetInlineStart)).toEqual(['50%']);

        const negative = create({ used: -5, reserved: -1, unlocked: -3 });
        expect(negative.summary()).toBe(`Использовано 0${NBSP}%, ещё 100${NBSP}% откроется позже.`);
        expect(negative.bar().querySelector<HTMLElement>('.used')?.style.inlineSize).toBe('0%');
        expect(negative.bar().querySelector<HTMLElement>('.locked')?.style.insetInlineStart).toBe('0%');
    });

    it('caps reserved work at what is left of the bar and rounds half up', () => {
        const capped = create({ used: 90, reserved: 30 });
        expect(capped.bar().querySelector<HTMLElement>('.reserved')?.style.inlineSize).toBe('10%');
        expect(capped.summary()).toContain(`Использовано 100${NBSP}%`);
        expect(create({ total: 8, used: 1 }).summary()).toContain(`Использовано 13${NBSP}%`);
    });

    it('survives an empty or invalid bar size without NaN', () => {
        expect(create({ total: 0, used: 5 }).summary()).toBe(`Использовано 0${NBSP}%.`);
        const broken = create({ total: Number.NaN, used: Number.POSITIVE_INFINITY });
        expect(broken.summary()).toBe(`Использовано 0${NBSP}%.`);
        expect(broken.bar().querySelector('.locked')).toBeNull();
    });
});
