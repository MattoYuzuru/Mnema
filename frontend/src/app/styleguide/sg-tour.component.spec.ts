import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { TourEngine } from '../core/onboarding/tour-engine.service';
import { SgTourComponent } from './sg-tour.component';

/** The «Знакомство» section of the styleguide, rendered alone: the page spec checks that it is in the map and in the page. */
describe('SgTourComponent', () => {
    let fixture: ComponentFixture<SgTourComponent>;
    let root: HTMLElement;

    beforeEach(async () => {
        await TestBed.configureTestingModule({ providers: [provideRouter([])] }).compileComponents();
        fixture = TestBed.createComponent(SgTourComponent);
        root = fixture.nativeElement as HTMLElement;
        await fixture.whenStable();
        fixture.detectChanges();
    });


    const section = (): HTMLElement => root.querySelector<HTMLElement>('section#tour')!;
    const specimen = (title: string): HTMLElement =>
        [...section().querySelectorAll<HTMLElement>('.sg-specimen')].find(item => item.querySelector('h3')!.textContent === title)!;

    it('shows the card of a step in all its states, each with a «когда использовать» note', () => {
        expect(section().querySelectorAll('.sg-specimen').length).toBeGreaterThanOrEqual(9);
        for (const title of ['Карточка шага: информационный шаг', 'Карточка шага: шаг с действием', 'Карточка шага: мини-слайдер', 'Карточка шага: нет места на экране и подготовка',
            'Нижний лист на телефоне', 'Вуаль с вырезом', 'Ночная поверхность', 'Пауза', 'Завершение удержанием', 'Живой тур']) {
            expect(specimen(title), title).toBeDefined();
            expect(specimen(title).querySelector('.sg-when')!.textContent, title).toContain('Когда использовать');
        }
        expect(specimen('Карточка шага: нет места на экране и подготовка').querySelectorAll('app-tour-card')).toHaveLength(2);
        const missing = specimen('Карточка шага: нет места на экране и подготовка').querySelector('app-tour-card .tour-body')!.textContent;
        expect(missing).toContain('Этого места сейчас нет на экране');
        expect(specimen('Карточка шага: шаг с действием').querySelector('.gate')?.textContent).toContain('Нажмите «Начать занятие»');
        expect(specimen('Карточка шага: мини-слайдер').querySelector('.slider')).not.toBeNull();
        expect(specimen('Нижний лист на телефоне').querySelector('.tour-card.sheet')).not.toBeNull();
    });

    it('draws specimens in the flow, not as open dialogs, so that toasts of the page are not held back', () => {
        expect(section().querySelectorAll('dialog')).toHaveLength(0);
        expect(root.querySelectorAll('dialog[open]')).toHaveLength(0);
    });

    it('shows the veil with its cut-out, the night surface with and without stars, and the pause plaque', () => {
        expect(specimen('Вуаль с вырезом').querySelector('app-tour-scrim path')!.getAttribute('fill-rule')).toBe('evenodd');
        expect(specimen('Вуаль с вырезом').querySelector('app-tour-scrim .frame')).not.toBeNull();
        const skies = [...specimen('Ночная поверхность').querySelectorAll('app-night-sky')];
        expect(skies).toHaveLength(2);
        expect(skies[0].querySelectorAll('.star').length).toBeGreaterThan(0);
        expect(skies[1].querySelector('.sky')).toBeNull();
        expect(specimen('Пауза').querySelector('app-tour-pause button')!.textContent).toBe('Продолжить знакомство');
    });

    it('shows the finish gesture idle, armed, holding and in the calm fill, and the live one works', async () => {
        const buttons = [...specimen('Завершение удержанием').querySelectorAll<HTMLButtonElement>('.hold-button')];
        expect(buttons).toHaveLength(4);
        await fixture.whenStable();
        fixture.detectChanges();
        expect(buttons.map(button => button.getAttribute('aria-pressed'))).toEqual(['false', 'true', 'true', 'true']);
        expect(buttons[1].getAttribute('aria-label')).toBe('Удерживайте 2\u00a0секунды, чтобы завершить знакомство');
        expect(buttons[1].querySelector('app-glyph[data-glyph=finger-hold]')).not.toBeNull();
        expect(buttons[2].classList.contains('holding')).toBe(true);
        expect(buttons[3].classList.contains('is-stepped')).toBe(true);
        expect(buttons[3].style.getPropertyValue('--hold-step')).toBe('50%');
        expect(buttons[2].classList.contains('is-stepped')).toBe(false);
    });

    it('restores the static states of the gesture on request', async () => {
        await fixture.whenStable();
        const buttons = [...specimen('Завершение удержанием').querySelectorAll<HTMLButtonElement>('.hold-button')];
        document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
        fixture.detectChanges();
        expect(buttons.map(button => button.getAttribute('aria-pressed'))).toEqual(['false', 'false', 'false', 'false']);
        [...specimen('Завершение удержанием').querySelectorAll<HTMLButtonElement>('button.button')].find(button => button.textContent === 'Показать состояния')!.click();
        fixture.detectChanges();
        expect(buttons.map(button => button.getAttribute('aria-pressed'))).toEqual(['false', 'true', 'true', 'true']);
    });

    it('previews the calm fill with the page switch', () => {
        fixture.componentRef.setInput('calm', true);
        fixture.detectChanges();
        const live = specimen('Завершение удержанием').querySelector('.sg-hold-states figure:first-child .hold-button')!;
        expect(live.classList.contains('is-stepped')).toBe(true);
    });

    it('runs a live tour over the mock hub: a step with the veil, «Дальше», and the journal of what it would record', async () => {
        const live = specimen('Живой тур');
        const engine = TestBed.inject(TourEngine);
        [...live.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Запустить демонстрацию')!.click();
        fixture.detectChanges();
        await fixture.whenStable();
        expect(engine.active()).toBe(true);
        expect(engine.definition()?.id).toBe('styleguide-demo');
        expect(live.querySelector('[data-tour-anchor="hub.header"]')).not.toBeNull();
        engine.next();
        fixture.detectChanges();
        expect(live.querySelector('[role=status]')!.textContent).toContain('STEP sample.material');
        // «Начать занятие» tells the tour; the gate is the event, and the last step ends the run.
        engine.next();
        engine.next();
        expect(engine.step()?.key).toBe('sample.study');
        live.querySelector<HTMLButtonElement>('button[data-tour-anchor="hub.start"]')!.click();
        fixture.detectChanges();
        expect(engine.active()).toBe(false);
        expect(live.querySelector('[role=status]')!.textContent).toContain('COMPLETE');
    });

    it('can stop the demonstration, recording nothing', () => {
        const live = specimen('Живой тур');
        const engine = TestBed.inject(TourEngine);
        [...live.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Запустить демонстрацию')!.click();
        [...live.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Остановить')!.click();
        expect(engine.active()).toBe(false);
        fixture.detectChanges();
        expect(live.querySelector('[role=status]')!.textContent).toContain('пока пусто');
    });

    it('ends the demonstration with the page, which it points at, and leaves another tour alone', () => {
        const live = specimen('Живой тур');
        const engine = TestBed.inject(TourEngine);
        [...live.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Запустить демонстрацию')!.click();
        expect(engine.active()).toBe(true);
        fixture.destroy();
        expect(engine.active()).toBe(false);
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        const other = TestBed.createComponent(SgTourComponent);
        const real = TestBed.inject(TourEngine);
        real.start({ id: 'real', branch: null, steps: [{ key: 'finale.ai', anchor: 'hub.start', title: 'Т', body: { kind: 'paragraphs', paragraphs: [] }, gate: 'next' }] });
        other.destroy();
        expect(real.active()).toBe(true);
    });
});
