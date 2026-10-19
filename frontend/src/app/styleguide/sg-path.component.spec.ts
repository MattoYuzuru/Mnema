import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ToastService } from '../core/notifications/toast.service';
import { PathHint } from '../core/onboarding/path-hint.service';
import { SgPathComponent } from './sg-path.component';

/** The «Путь Mnema» section of the styleguide, rendered alone: the page spec checks that it is in the map and in the page. */
describe('SgPathComponent', () => {
    let fixture: ComponentFixture<SgPathComponent>;
    let root: HTMLElement;

    beforeEach(async () => {
        await TestBed.configureTestingModule({ providers: [provideRouter([])] }).compileComponents();
        fixture = TestBed.createComponent(SgPathComponent);
        root = fixture.nativeElement as HTMLElement;
        await fixture.whenStable();
        fixture.detectChanges();
    });

    const section = (): HTMLElement => root.querySelector<HTMLElement>('section#path')!;
    const specimen = (title: string): HTMLElement =>
        [...section().querySelectorAll<HTMLElement>('.sg-specimen')].find(item => item.querySelector('h3')!.textContent === title)!;

    it('has every specimen with a «когда использовать» note', () => {
        for (const title of ['Веха: состояния', 'Веха: наведение и фокус', 'Веха на 320 px', 'Глава и общая полоса', 'Подсказка на месте', 'Выбор колоды для задания', 'Экран о микрофоне', 'Пауза задания', 'Тост вехи', 'Неделя ритма: состояния', 'Неделя ритма на 320 px', 'Ритм на итогах занятия', 'Тост выполненной цели недели',
            'Награда за «Путь»: карточка', 'Награда за «Путь»: на 320 px', 'Промокод награды', 'Поздравление с наградой', 'Тост: награда готова']) {
            expect(specimen(title), title).toBeDefined();
            expect(specimen(title).querySelector('.sg-when')!.textContent, title).toContain('Когда использовать');
        }
    });

    it('shows the milestone in every state with a word each: done, partial with a counter, the stage of «На потом», not started', () => {
        const rows = [...specimen('Веха: состояния').querySelectorAll('app-path-milestone')];
        expect(rows.map(row => row.querySelector('.word')!.textContent!.trim())).toEqual(['Сделано', 'Начато', 'Начато', 'Не начато']);
        expect(rows[1].querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('2');
        expect(rows[2].querySelector('.benefit')?.textContent).toBe('Заметка сохранена, осталось её разобрать.');
        expect(rows[0].querySelector('a.go')).toBeNull();
        expect(rows[3].querySelector('a.go')?.getAttribute('aria-label')).toBe('Перейти: Добавьте картинку или звук');
    });

    it('shows the hover look without a pointer, the 320 px frame, the chapters and the bar', () => {
        expect(specimen('Веха: наведение и фокус').querySelector('.milestone.preview-hover')).not.toBeNull();
        expect(specimen('Веха на 320 px').querySelector('.sg-path-narrow app-path-milestone')).not.toBeNull();
        const chapters = [...specimen('Глава и общая полоса').querySelectorAll<HTMLDetailsElement>('details.chapter')];
        expect(chapters.map(chapter => chapter.open)).toEqual([false, true]);
        expect(specimen('Глава и общая полоса').querySelector('[role="progressbar"]')?.getAttribute('aria-valuetext')).toBe('5 из 12');
    });

    it('draws the hint card in the flow, not as an open dialog, and shows the live hint at the mock button', () => {
        expect(section().querySelectorAll('dialog')).toHaveLength(0);
        const card = specimen('Подсказка на месте').querySelector('app-path-hint-card')!;
        expect(card.querySelector('[role="dialog"]')?.getAttribute('aria-modal')).toBe('false');
        expect(card.querySelector('h2')?.textContent).toBe('Сохраните заметку «На потом»');
        const hint = TestBed.inject(PathHint);
        const buttons = [...specimen('Подсказка на месте').querySelectorAll<HTMLButtonElement>('.sg-path-hint-live button')];
        buttons.find(button => button.textContent === 'Показать подсказку у кнопки')!.click();
        fixture.detectChanges();
        expect(hint.request()?.anchor).toBe('hub.capture');
        expect(hint.phase()).toBe('ready');
        [...specimen('Подсказка на месте').querySelectorAll<HTMLButtonElement>('.sg-path-hint-live button')].find(button => button.textContent === 'Убрать подсказку')!.click();
        fixture.detectChanges();
        expect(hint.active()).toBe(false);
    });

    it('shows the chooser of a quest deck and its refusal, the microphone screen with a leave button, and the pause of a quest', () => {
        const chooser = specimen('Выбор колоды для задания');
        const [choose, blocked] = [...chooser.querySelectorAll('app-quest-chooser')];
        expect([...choose.querySelectorAll('input[type=radio]')].map(radio => (radio as HTMLInputElement).value)).toEqual(['sg-1', 'sg-2', 'sg-3']);
        expect(choose.querySelector('.stamp')?.textContent).toBe('Учебная');
        expect(blocked.querySelector('p')?.textContent).toContain('бесплатные проверки: 2');
        expect(specimen('Экран о микрофоне').querySelector('app-tour-card button.leave')?.textContent).toBe('Выйти из задания');
        expect(specimen('Экран о микрофоне').textContent).toContain('Браузер сам спросит разрешение на микрофон');
        expect(specimen('Пауза задания').querySelector('app-tour-pause button')?.textContent).toBe('Продолжить задание');
        expect(section().querySelectorAll('dialog')).toHaveLength(0);
    });

    it('raises the toast of a milestone through the real presenter', () => {
        const notify = vi.spyOn(TestBed.inject(ToastService), 'notify');
        [...specimen('Тост вехи').querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Показать тост вехи')!.click();
        expect(notify).toHaveBeenCalledWith(expect.any(String), 'Путь: вы добавили в материал картинку или звук · 6 из 12', 'INFO', { label: 'Открыть «Путь»', commands: ['/profile', 'path'] });
    });
    it('shows the week of the rhythm in its states: empty, partial, met, and today at the first and the last day', () => {
        const figures = [...specimen('Неделя ритма: состояния').querySelectorAll('figure')];
        expect(figures).toHaveLength(5);
        const studied = figures.map(figure => [...figure.querySelectorAll('ol.week > li')].filter(item => item.classList.contains('done')).length);
        expect(studied).toEqual([0, 3, 5, 1, 2]);
        const today = figures.map(figure => [...figure.querySelectorAll('ol.week > li')].findIndex(item => item.classList.contains('today')));
        expect(today).toEqual([2, 4, 4, 0, 6]);
        expect(figures[0].querySelector('p.visually-hidden')?.textContent).toBe('Эта неделя: занятия ещё не отмечены.');
        expect(figures[1].querySelector('p.visually-hidden')?.textContent).toBe('Эта неделя: занятия во вторник, в четверг и в пятницу.');
        expect(specimen('Неделя ритма на 320 px').querySelector('.sg-path-narrow app-rhythm-week')).not.toBeNull();
    });

    it('shows the rhythm block of a summary, and raises the quiet toast of the met goal through the real text', () => {
        expect(specimen('Ритм на итогах занятия').querySelector('app-rhythm-summary h3')?.textContent).toBe('Третий день на этой неделе');
        expect(specimen('Ритм на итогах занятия').querySelector('.hint')?.textContent).toBe('Цель — 5\u00a0дней в неделю');
        const notify = vi.spyOn(TestBed.inject(ToastService), 'notify');
        [...specimen('Тост выполненной цели недели').querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Показать тост цели недели')!.click();
        expect(notify).toHaveBeenCalledWith(expect.any(String), 'Цель недели выполнена: вы занимались 5\u00a0дней.', 'INFO', null);
    });

    describe('the reward of the finished path', () => {
        const plain = (text: string | null): string => (text ?? '').replace(/\u00a0/gu, ' ').trim();

        it('shows the card in its states: three kinds of gift, the profile form, the redeemed line, and nothing for an expired reward', () => {
            const figures = [...specimen('Награда за «Путь»: карточка').querySelectorAll('figure')];
            expect(figures).toHaveLength(6);
            expect(figures.map(figure => plain(figure.querySelector('.gift')?.textContent ?? null))).toEqual([
                'Ваш подарок: −10 % на подписку до 29 октября, 21:41 МСК.', 'Ваш подарок: −15 % на подписку Pro до 29 октября, 21:41 МСК.',
                'Ваш подарок: Plus на 30 дней. Активировать до 29 октября, 21:41 МСК.', 'Ваш подарок: −10 % на подписку до 29 октября, 21:41 МСК.', '', ''
            ]);
            expect(figures[3].querySelector('h3')?.textContent).toBe('Вы освоили Mnema полностью');
            expect(figures[0].querySelector('h2')?.textContent).toBe('Вы освоили Mnema полностью');
            expect(plain(figures[4].querySelector('.redeemed')?.textContent ?? null)).toBe('Подарок за «Путь» использован.');
            expect(figures[5].querySelector('app-reward-card')!.children).toHaveLength(0);
            expect(figures[0].querySelector('a.button.primary')?.getAttribute('href')).toBe('/plans?promo=K7PM-W2QX-H9RT');
        });

        it('shows the code on paper and on the night, and the 320 px frame', () => {
            const codes = [...specimen('Промокод награды').querySelectorAll('app-reward-code')];
            expect(codes.map(code => code.classList.contains('night'))).toEqual([false, true]);
            expect(codes.every(code => code.querySelector<HTMLInputElement>('input')!.value === 'K7PM-W2QX-H9RT')).toBe(true);
            expect(specimen('Награда за «Путь»: на 320 px').querySelector('.sg-path-narrow app-reward-card .gift')).not.toBeNull();
        });

        it('shows the congratulation on the shared night with its stars, not as an open dialog', () => {
            const night = specimen('Поздравление с наградой').querySelector('app-night-sky')!;
            expect(night.querySelector('.sky')).not.toBeNull();
            expect(plain(night.querySelector('.lead')!.textContent)).toBe('Ваш подарок: −10 % на подписку до 29 октября, 21:41 МСК.');
            expect(night.querySelector('app-reward-code.night')).not.toBeNull();
            expect([...night.querySelectorAll('.actions > *')].map(item => item.textContent)).toEqual(['Выбрать тариф', 'Позже']);
            expect(section().querySelectorAll('dialog')).toHaveLength(0);
        });

        it('raises the toast of the ready reward through the real presenter', () => {
            const notify = vi.spyOn(TestBed.inject(ToastService), 'notify');
            [...specimen('Тост: награда готова').querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent === 'Показать тост награды')!.click();
            expect(notify).toHaveBeenCalledWith(expect.any(String), 'Вы освоили Mnema полностью. Подарок за «Путь» действует до 29 октября, 21:41\u00a0МСК.', 'INFO',
                { label: 'Открыть «Путь»', commands: ['/profile', 'path'] });
        });
    });
});
