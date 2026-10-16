import { ComponentFixture, TestBed } from '@angular/core/testing';

import { paragraphs, slides } from '../core/onboarding/tour-rich-text';
import { TourBody, TourGate } from '../core/onboarding/tour.models';
import { TourCardComponent } from './tour-card.component';

const GATE: TourGate = { action: 'deck.copied', label: 'Добавить к себе' };

describe('TourCardComponent', () => {
    let fixture: ComponentFixture<TourCardComponent>;
    let host: HTMLElement;

    beforeEach(() => TestBed.configureTestingModule({ imports: [TourCardComponent] }));

    function render(inputs: Record<string, unknown> = {}): TourCardComponent {
        fixture = TestBed.createComponent(TourCardComponent);
        const values: Record<string, unknown> = {
            position: 2, total: 4, title: 'Материал — то, что вы учите', body: paragraphs('Здесь <span class="key">материалы</span> колоды.'), gate: 'next', ...inputs
        };
        for (const [name, value] of Object.entries(values)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
        host = fixture.nativeElement;
        return fixture.componentInstance;
    }
    const dialog = (): HTMLDialogElement => host.querySelector('dialog')!;
    const buttons = (): string[] => [...host.querySelectorAll('button')].filter(button => !button.closest('.hold-button') || button.classList.contains('hold-button'))
        .map(button => button.textContent!.trim());
    const text = (): string => host.querySelector('.tour-body')!.textContent!.replace(/\s+/gu, ' ').trim();

    describe('accessibility', () => {
        it('is a dialog named by its heading and described by its body, with «Шаг N из M» as a rubric', () => {
            render();
            const heading = host.querySelector('h2')!;
            expect(dialog().getAttribute('role')).toBe('dialog');
            expect(dialog().getAttribute('aria-labelledby')).toBe(heading.id);
            expect(heading.textContent).toBe('Материал — то, что вы учите');
            const counter = host.querySelector('.eyebrow')!;
            expect(counter.textContent).toBe('Шаг 2 из 4');
            // The counter is read with the body when the dialog opens (the page outside a modal is inert, so a live region there is silent).
            expect(dialog().getAttribute('aria-describedby')).toBe(`${counter.id} ${host.querySelector('.tour-body')!.id}`);
        });

        it('announces «Шаг N из M» with the title from a live region inside the card, which stays live when the page behind is inert', () => {
            render();
            const region = host.querySelector<HTMLElement>('dialog [role=status]')!;
            expect(region.getAttribute('aria-live')).toBe('polite');
            expect(region.textContent!.trim()).toBe('Шаг 2 из 4. Материал — то, что вы учите');
            expect(region.closest('dialog')).toBe(dialog());
        });

        it('gives every card its own ids', () => {
            render();
            const first = host.querySelector('h2')!.id;
            const second = TestBed.createComponent(TourCardComponent);
            for (const [name, value] of Object.entries({ position: 1, total: 2, title: 'Другой', body: paragraphs('x') })) second.componentRef.setInput(name, value);
            second.detectChanges();
            expect(second.nativeElement.querySelector('h2').id).not.toBe(first);
        });

        it('draws the step dots for the eye only and fills those up to the current step', () => {
            render();
            const dots = host.querySelector('.dots')!;
            expect(dots.getAttribute('aria-hidden')).toBe('true');
            expect([...dots.children].map(dot => dot.classList.contains('on'))).toEqual([true, true, false, false]);
        });

        it('moves focus to the heading, which is programmatically focusable and not a tab stop', () => {
            const card = render();
            const heading = host.querySelector('h2')!;
            expect(heading.getAttribute('tabindex')).toBe('-1');
            card.focusHeading();
            expect(document.activeElement).toBe(heading);
        });

        it('shows key words as text nodes in elements, never as markup', () => {
            render({ body: paragraphs('<img src=x onerror=alert(1)> и <span class="key">слово</span>') });
            expect(host.querySelector('.tour-body img')).toBeNull();
            expect(text()).toBe('<img src=x onerror=alert(1)> и слово');
            expect([...host.querySelectorAll('.tour-body .key')].map(element => element.textContent)).toEqual(['слово']);
        });

        it('names the controls with words, the slider arrows included', () => {
            render({ body: slides('Один.', 'Два.') });
            const arrows = [...host.querySelectorAll('.slider-controls button')];
            expect(arrows.map(button => button.getAttribute('aria-label'))).toEqual(['Предыдущее пояснение', 'Следующее пояснение']);
            expect(host.querySelector('.slider')!.getAttribute('role')).toBe('group');
            expect(host.querySelector('.slider')!.getAttribute('aria-label')).toBe('Пояснения к шагу');
        });
    });

    describe('opening', () => {
        it('opens an informational step with showModal() and a step that waits for the target with show()', () => {
            const modal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
            const modeless = vi.spyOn(HTMLDialogElement.prototype, 'show');
            render({ modal: true });
            expect(modal).toHaveBeenCalledTimes(1);
            expect(modeless).not.toHaveBeenCalled();
            expect(dialog().open).toBe(true);
            fixture.destroy();
            render({ modal: false, gate: GATE });
            expect(modeless).toHaveBeenCalledTimes(1);
        });

        it('reopens the dialog the other way when the step becomes modal or not, keeping the focus in the card', () => {
            const card = render({ modal: true });
            const modeless = vi.spyOn(HTMLDialogElement.prototype, 'show');
            const modal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
            card.focusHeading();
            fixture.componentRef.setInput('modal', false);
            fixture.detectChanges();
            expect(modeless).toHaveBeenCalledTimes(1);
            expect(document.activeElement).toBe(host.querySelector('h2'));
            fixture.componentRef.setInput('modal', true);
            fixture.detectChanges();
            expect(modal).toHaveBeenCalledTimes(1);
        });

        it('does not reopen for an unrelated change', () => {
            render();
            const modal = vi.spyOn(HTMLDialogElement.prototype, 'showModal');
            fixture.componentRef.setInput('title', 'Другой заголовок');
            fixture.detectChanges();
            expect(modal).not.toHaveBeenCalled();
        });

        it('closes the dialog when the card is destroyed', () => {
            render();
            const surface = dialog();
            fixture.destroy();
            expect(surface.open).toBe(false);
        });

        it('draws a specimen in the flow, not as an open dialog (an open dialog holds back every toast of the page)', () => {
            render({ inline: true });
            expect(host.querySelector('dialog')).toBeNull();
            expect(document.querySelectorAll('dialog[open]')).toHaveLength(0);
            const card = host.querySelector('.tour-card')!;
            expect(card.getAttribute('role')).toBe('group');
            expect(card.getAttribute('aria-labelledby')).toBe(host.querySelector('h2')!.id);
        });
    });

    describe('Escape', () => {
        it('asks to pause and never lets the browser close the card', () => {
            const card = render();
            let paused = 0;
            card.pauseRequest.subscribe(() => paused++);
            const event = new Event('cancel', { cancelable: true });
            dialog().dispatchEvent(event);
            expect(paused).toBe(1);
            expect(event.defaultPrevented).toBe(true);
            expect(dialog().open).toBe(true);
        });
    });

    describe('buttons', () => {
        it('has «Назад», «Дальше» and the finish gesture on an informational step', () => {
            render();
            expect(buttons()).toEqual(['Назад', 'Дальше', 'Завершить знакомствоЗавершить знакомство']);
        });

        it('leaves «Назад» out on the first step', () => {
            render({ canBack: false });
            expect(buttons()).toEqual(['Дальше', 'Завершить знакомствоЗавершить знакомство']);
        });

        it('has no «Дальше» while the step waits for an action, and names the control to press', () => {
            render({ gate: GATE });
            expect(buttons()).toEqual(['Назад', 'Завершить знакомствоЗавершить знакомство']);
            expect(host.querySelector('.gate')!.textContent).toContain('Нажмите «Добавить к себе», чтобы продолжить');
        });

        it('emits back and next, with no hidden second path', () => {
            const card = render();
            const seen: string[] = [];
            card.back.subscribe(() => seen.push('back'));
            card.next.subscribe(() => seen.push('next'));
            const [back, next] = [...host.querySelectorAll<HTMLButtonElement>('.steps button')];
            back.click();
            next.click();
            expect(seen).toEqual(['back', 'next']);
        });

        it('finishes only through the hold gesture: a press arms it, two seconds of holding emit finish', async () => {
            vi.useFakeTimers();
            try {
                const card = render();
                let finished = 0;
                card.finish.subscribe(() => finished++);
                const hold = host.querySelector<HTMLButtonElement>('.hold-button')!;
                hold.click();
                fixture.detectChanges();
                expect(hold.getAttribute('aria-label')).toBe('Удерживайте 2\u00a0секунды, чтобы завершить знакомство');
                expect(finished).toBe(0);
                hold.dispatchEvent(new PointerEvent('pointerdown', { button: 0, clientX: 3, clientY: 3, bubbles: true }));
                await vi.advanceTimersByTimeAsync(1990);
                expect(finished).toBe(0);
                await vi.advanceTimersByTimeAsync(10);
                expect(finished).toBe(1);
            } finally {
                vi.useRealTimers();
            }
        });

        it('passes the calm fill on to the gesture', () => {
            render({ calm: true });
            expect(host.querySelector('.hold-button')!.classList.contains('is-stepped')).toBe(true);
        });
    });

    describe('modes', () => {
        it('replaces the text when the anchor is missing, still lets the learner go on, even from an action step', () => {
            render({ mode: 'missing', gate: GATE });
            expect(text()).toBe('Этого места сейчас нет на экране. Продолжим со следующего шага.');
            expect(buttons()).toContain('Дальше');
            expect(host.querySelector('.gate')).toBeNull();
        });

        it('says the page is being prepared and offers no «Дальше»', () => {
            render({ mode: 'preparing', gate: GATE });
            expect(text()).toBe('Готовим колоду…');
            expect(buttons()).not.toContain('Дальше');
            expect(host.querySelector('.gate')).toBeNull();
        });
    });

    describe('mini-slider', () => {
        const body = (): TourBody => slides('Первое <span class="key">пояснение</span>.', 'Второе.', 'Третье.');
        const arrows = (): HTMLButtonElement[] => [...host.querySelectorAll<HTMLButtonElement>('.slider-controls button')];
        const counter = (): string => host.querySelector('.slider-controls .hint')!.textContent!.trim();
        const slide = (): string => host.querySelector('.slider p')!.textContent!.trim();

        it('shows the first slide with its count and a polite live paragraph', () => {
            render({ body: body() });
            expect(slide()).toBe('Первое пояснение.');
            expect(counter()).toBe('1 / 3');
            expect(host.querySelector('.slider p')!.getAttribute('aria-live')).toBe('polite');
            expect(host.querySelectorAll('.tour-body > p')).toHaveLength(0);
        });

        it('moves between slides and wraps around at both ends', () => {
            render({ body: body() });
            arrows()[1].click();
            fixture.detectChanges();
            expect(slide()).toBe('Второе.');
            arrows()[1].click();
            arrows()[1].click();
            fixture.detectChanges();
            expect(slide()).toBe('Первое пояснение.');
            arrows()[0].click();
            fixture.detectChanges();
            expect(slide()).toBe('Третье.');
            expect(counter()).toBe('3 / 3');
        });

        it('does not change the step counter', () => {
            render({ body: body() });
            arrows()[1].click();
            fixture.detectChanges();
            expect(host.querySelector('.eyebrow')!.textContent).toBe('Шаг 2 из 4');
        });

        it('starts at the first slide again for another step', () => {
            render({ body: body() });
            arrows()[1].click();
            fixture.detectChanges();
            fixture.componentRef.setInput('body', slides('Другой шаг.', 'И ещё.'));
            fixture.detectChanges();
            expect(slide()).toBe('Другой шаг.');
        });
    });

    describe('placement', () => {
        it('puts a floating card where the host says and ignores the position on a sheet or in a specimen', () => {
            render({ at: { left: 120, top: 240 } });
            expect(dialog().style.left).toBe('120px');
            expect(dialog().style.top).toBe('240px');
            fixture.componentRef.setInput('sheet', true);
            fixture.detectChanges();
            expect(dialog().classList.contains('sheet')).toBe(true);
            expect(dialog().style.left).toBe('');
            expect(dialog().style.top).toBe('');
        });

        it('measures itself for the host: the dialog, or its box in a specimen', () => {
            const card = render();
            expect(card.element).toBe(dialog());
            fixture.destroy();
            const specimen = render({ inline: true });
            expect(specimen.element).toBe(host);
        });
    });
});
