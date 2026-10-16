import { ComponentFixture, TestBed } from '@angular/core/testing';

import { InterestProfile } from '../community/recommendations.models';
import { FirstEntryComponent, daysLabel } from './first-entry.component';
import { OnboardingFlow } from './onboarding-flow.service';
import { Setup, setup } from './onboarding-test-data';

const PROFILE: InterestProfile = {
    rowVersion: '1', onboardingComplete: false, selectedTopics: [], inferredTopics: [], forYouEnabled: false, eventsEnabled: false,
    availableTopics: [{ id: 'italian', name: 'Итальянский', parentId: null }, { id: 'psychology', name: 'Психология', parentId: null }]
};

describe('FirstEntryComponent', () => {
    let fixture: ComponentFixture<FirstEntryComponent>;
    let root: HTMLElement;
    let s: Setup;
    let flow: OnboardingFlow;

    const render = async (options: Parameters<typeof setup>[0] = {}): Promise<void> => {
        s = setup(options);
        flow = s.flow;
        s.interests.profile.set(PROFILE);
        fixture = TestBed.createComponent(FirstEntryComponent);
        root = fixture.nativeElement;
        await settle();
    };
    const settle = async (): Promise<void> => { fixture.detectChanges(); await fixture.whenStable(); TestBed.tick(); fixture.detectChanges(); };
    const open = async (card: Parameters<OnboardingFlow['open']>[0]): Promise<void> => { flow.open(card); await settle(); };
    const dialog = (): HTMLDialogElement | null => root.querySelector('dialog');
    const heading = (): HTMLElement => root.querySelector('h1, h2')!;
    /** The approved texts hold no-break spaces here and there; a spec reads and writes them as plain spaces. */
    const plain = (text: string | null): string => (text ?? '').replace(/\u00a0/gu, ' ').trim();
    const button = (text: string): HTMLButtonElement => [...root.querySelectorAll<HTMLButtonElement>('button')].find(item => plain(item.textContent) === text)!;
    /** A click, and the asynchronous work it starts (the flow awaits the stores and the journey) to the end. */
    const click = async (text: string): Promise<void> => { button(text).click(); await new Promise(resolve => setTimeout(resolve, 0)); await settle(); await new Promise(resolve => setTimeout(resolve, 0)); await settle(); };
    const choose = async (label: string): Promise<void> => {
        const input = [...root.querySelectorAll<HTMLLabelElement>('label')].find(item => plain(item.textContent).includes(label))!.querySelector('input')!;
        input.click();
        await settle();
    };

    describe('while nothing is wanted', () => {
        it('draws nothing: no dialog in the page', async () => {
            await render();
            expect(dialog()).toBeNull();
        });
    });

    describe('the welcome', () => {
        beforeEach(async () => { await render(); await open('welcome'); });

        it('is a full-screen modal dialog named by its heading, with the stars of the night', () => {
            expect(dialog()!.open).toBe(true);
            expect(plain(dialog()!.getAttribute('aria-label'))).toBe('Mnema помогает надолго запомнить то, что вы учите');
            expect(root.querySelectorAll('app-night-sky .star').length).toBeGreaterThan(0);
            expect(root.querySelector('.sky')!.getAttribute('aria-hidden')).toBe('true');
        });

        it('puts the focus on the welcome heading', () => {
            expect(heading().tagName).toBe('H1');
            expect(document.activeElement).toBe(heading());
            expect(plain(heading().textContent)).toBe('Mnema помогает надолго запомнить то, что вы учите');
        });

        it('says the approved words and offers two buttons of the same kind of weight', () => {
            expect(plain(root.textContent)).toContain('Начало пути');
            expect(plain(root.textContent)).toContain('Покажем, как это работает. Это займёт две минуты.');
            const start = button('Познакомиться — 2 минуты');
            const skip = button('Мне это знакомо — сразу к делу');
            expect(start.classList.contains('primary')).toBe(true);
            expect(skip.classList.contains('quiet')).toBe(true);
            expect(start.closest('.actions')).toBe(skip.closest('.actions'));
        });

        it('does not close on Escape: the dialog\'s own cancel is refused', () => {
            const cancel = new Event('cancel', { cancelable: true });
            dialog()!.dispatchEvent(cancel);
            expect(cancel.defaultPrevented).toBe(true);
            expect(dialog()!.open).toBe(true);
        });

        it('starts the survey at the first question', async () => {
            await click('Познакомиться — 2 минуты');
            expect(s.store.calls[0]).toEqual(['START', { stepKey: 'survey.goal' }]);
            expect(plain(root.textContent)).toContain('Для чего вам Mnema?');
        });

        it('skips from the welcome', async () => {
            await click('Мне это знакомо — сразу к делу');
            expect(s.store.calls[0][0]).toBe('SKIP');
            expect(dialog()).toBeNull();
            expect(s.toasts.notify).toHaveBeenCalled();
        });
    });

    describe('a survey card', () => {
        beforeEach(async () => { await render(); await open('welcome'); await click('Познакомиться — 2 минуты'); });

        it('is the same night without stars, with a paper card, a progress bar and the privacy line', () => {
            expect(root.querySelector('app-night-sky .sky')).toBeNull();
            const bar = root.querySelector('[role=progressbar]')!;
            expect(bar.getAttribute('aria-valuemin')).toBe('0');
            expect(bar.getAttribute('aria-valuemax')).toBe('4');
            expect(bar.getAttribute('aria-valuenow')).toBe('0');
            expect(plain(bar.getAttribute('aria-valuetext'))).toBe('0 из 4');
            expect(bar.getAttribute('aria-label')).toBe('Пройдено вопросов');
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 1 из 4');
            expect(plain(root.textContent)).toContain('Ответы не видны другим пользователям');
        });

        it('takes the focus to the heading of the card', () => {
            expect(document.activeElement).toBe(heading());
            expect(plain(heading().textContent)).toBe('Для чего вам Mnema?');
        });

        it('has «Пропустить» on the card and no back button on the first one', () => {
            expect(button('Пропустить')).toBeDefined();
            expect(root.textContent).not.toContain('Назад');
        });

        it('keeps «Дальше» refused until an answer is chosen, then stores the goal and moves on', async () => {
            const next = button('Дальше');
            expect(next.getAttribute('aria-disabled')).toBe('true');
            next.click();
            await settle();
            expect(s.goal.answer).not.toHaveBeenCalled();
            await choose('Язык');
            expect(button('Дальше').getAttribute('aria-disabled')).toBeNull();
            await click('Дальше');
            expect(s.goal.answer).toHaveBeenCalledWith('LANGUAGE');
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 2 из 4');
        });

        it('skips the goal question and records the skip', async () => {
            await click('Пропустить');
            expect(s.goal.answer).toHaveBeenCalledWith(null);
        });
    });

    describe('the interests card', () => {
        beforeEach(async () => { await render({ goalAnswered: true }); await open('welcome'); await click('Познакомиться — 2 минуты'); });

        it('asks once, with the shared picker, and its own heading is the card\'s', () => {
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 2 из 4');
            expect(plain(heading().textContent)).toBe('Что хотите изучать?');
            expect(root.querySelector('app-interest-picker legend')!.classList.contains('visually-hidden')).toBe(true);
            expect(plain(root.textContent)).toContain('Выберите до 20 тем. По ним Mnema предложит колоды Сообщества.');
        });

        it('saves the chosen topics through the interest store and goes on once the save is answered', async () => {
            const picker = root.querySelector('app-interest-picker')!;
            picker.dispatchEvent(new Event('x'));
            const save = [...picker.querySelectorAll<HTMLButtonElement>('button')].find(item => item.textContent!.includes('Сохранить интересы'))!;
            save.click();
            await settle();
            expect(s.interests.save).toHaveBeenCalledWith([]);
            s.interests.busy.set(true);
            await settle();
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 2 из 4');
            s.interests.busy.set(false);
            await settle();
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 3 из 4');
        });

        it('stays on the card when the save failed', async () => {
            const save = [...root.querySelectorAll<HTMLButtonElement>('app-interest-picker button')].find(item => item.textContent!.includes('Сохранить интересы'))!;
            save.click();
            s.interests.error.set('Не удалось подтвердить сохранение. Повторите тот же запрос.');
            s.interests.busy.set(true);
            await settle();
            s.interests.busy.set(false);
            await settle();
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 2 из 4');
            expect(root.querySelector('[role=alert]')!.textContent).toContain('Не удалось подтвердить');
        });

        it('skips by recording the skip', async () => {
            // The first card of the survey has nothing to go back to (never back to the welcome).
            expect(root.textContent).not.toContain('Назад');
            await click('Пропустить');
            expect(s.interests.save).toHaveBeenCalledWith([]);
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 3 из 4');
        });
    });

    describe('experience and days', () => {
        beforeEach(async () => { await render({ goalAnswered: true, interestsOpen: false }); await open('welcome'); await click('Познакомиться — 2 минуты'); });

        it('asks about flashcards with the three approved answers and their hints', async () => {
            expect(plain(heading().textContent)).toBe('Учились ли вы по карточкам раньше?');
            expect([...root.querySelectorAll('.segment .text')].map(item => plain(item.textContent))).toEqual(['Впервые', 'Да, в похожих приложениях', 'Да, уверенно']);
            await choose('Да, в похожих приложениях');
            expect(plain(root.textContent)).toContain('Anki, Quizlet и другие. Покажем только главное.');
        });

        it('stores the experience through the journey and moves on to the days', async () => {
            await choose('Да, уверенно');
            await click('Дальше');
            expect(s.store.preferences).toEqual([{ experience: 'CONFIDENT' }]);
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 4 из 4');
        });

        it('skips a question without storing anything', async () => {
            await click('Пропустить');
            expect(s.store.preferences).toEqual([]);
            expect(plain(root.querySelector('.eyebrow')!.textContent)).toBe('Вопрос 4 из 4');
        });

        it('asks about the days with 2, 3 and 5 as plurals, 7 as «Каждый день», and ends with «Готово»', async () => {
            await click('Пропустить');
            expect([...root.querySelectorAll('.segment .text')].map(item => plain(item.textContent))).toEqual(['2 дня', '3 дня', '5 дней', 'Каждый день']);
            await choose('3 дня');
            await click('Готово');
            expect(s.store.preferences).toEqual([{ weeklyTarget: 3 }]);
            expect(plain(heading().textContent)).toBe('С чего начнём?');
        });
    });

    describe('reopening in the middle', () => {
        it.each([['survey.experience', 'Учились ли вы по карточкам раньше?'], ['survey.days', 'Сколько дней в неделю хотите заниматься?'], ['choose', 'С чего начнём?']] as const)(
            'shows %s again for a journey interrupted there', async (card, title) => {
                await render({ journey: { state: 'IN_PROGRESS', stepKey: card, autoOffer: false } });
                await open(card);
                expect(plain(heading().textContent)).toBe(title);
                expect(root.querySelector('app-night-sky .sky')).toBeNull();
            });
    });

    describe('the branch choice', () => {
        const atChoice = async (options: Parameters<typeof setup>[0] = {}): Promise<void> => {
            await render({ journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false, experience: 'NEW' }, ...options });
            await open('choose');
            await vi.waitFor(() => expect(s.community.facets).toHaveBeenCalled());
            await settle();
        };
        const labels = (): string[] => [...root.querySelectorAll('.choice strong')].map(item => plain(item.firstChild!.textContent));

        it('offers «Найти готовую колоду» and «Создать свою» when the catalogue has decks, and nothing for the sample without a template', async () => {
            await atChoice({ listed: 12 });
            expect(labels()).toEqual(['Найти готовую колоду', 'Создать свою']);
            expect(root.textContent).not.toContain('Посмотреть на примере');
        });

        it('hides the catalogue when it is thin, without a word about it', async () => {
            await atChoice({ listed: 2 });
            expect(labels()).toEqual(['Создать свою']);
            expect(root.textContent).not.toContain('Сообщества по вашим темам');
        });

        it('offers the sample with the stamp for a newcomer, and without it for a learner who knows flashcards', async () => {
            const sample = { sampleDeck: { available: true, deckId: 'e1b6bb58-3c0e-4f6a-8f11-4f2d66f6a1aa', updateAvailable: false } };
            await atChoice({ view: sample, listed: 12 });
            expect(labels()).toEqual(['Посмотреть на примере', 'Найти готовую колоду', 'Создать свою']);
            expect(root.querySelector('.choice .stamp')!.textContent).toBe('Советуем для начала');
            TestBed.resetTestingModule();
            await atChoice({ view: sample, listed: 12, journey: { state: 'IN_PROGRESS', stepKey: 'choose', autoOffer: false, experience: 'FAMILIAR' } });
            expect(root.querySelector('.choice .stamp')).toBeNull();
        });

        it('starts the chosen branch with «Начать»', async () => {
            await atChoice({ listed: 12 });
            await choose('Создать свою');
            await click('Начать');
            expect(s.store.calls.at(-1)).toEqual(['STEP', { stepKey: 'own.details', branch: 'OWN' }]);
            expect(dialog()).toBeNull();
            expect(s.engine.active()).toBe(true);
        });

        it('defaults to the first offer', async () => {
            await atChoice({ listed: 12 });
            await click('Начать');
            expect(s.store.calls.at(-1)).toEqual(['STEP', { stepKey: 'community.search', branch: 'COMMUNITY' }]);
        });

        it('names the radios by their group and shows the hint of each', async () => {
            await atChoice({ listed: 12 });
            expect(root.querySelector('fieldset legend')!.textContent).toBe('С чего начнём?');
            expect(plain(root.textContent)).toContain('Название, первый материал и упражнение — и можно заниматься.');
        });
    });

    describe('the choice of a deck for a replay of «Своя колода»', () => {
        it('offers a new deck and the decks the learner has, then starts at the material of the one chosen', async () => {
            await render({ journey: { state: 'COMPLETED', autoOffer: false }, listed: 1 });
            s.decks.list.mockReturnValue((await import('rxjs')).of({ items: [{ deckId: 'deck-1', metadata: { title: 'Испанский' }, fork: null }], nextCursor: null }));
            await flow.replay();
            await vi.waitFor(() => expect(flow.replayDecks()).toHaveLength(1));
            await settle();
            await click('Начать');
            expect(plain(heading().textContent)).toBe('С какой колоды начнём?');
            expect([...root.querySelectorAll('.choice strong')].map(item => item.textContent)).toEqual(['Новая колода', 'Испанский']);
            await choose('Испанский');
            await click('Начать');
            expect(s.store.calls.at(-1)).toEqual(['STEP', { stepKey: 'own.material', branch: 'OWN' }]);
        });

        it('goes back from there to the choice', async () => {
            await render({ journey: { state: 'COMPLETED', autoOffer: false }, listed: 1 });
            s.decks.list.mockReturnValue((await import('rxjs')).of({ items: [{ deckId: 'deck-1', metadata: { title: 'Испанский' }, fork: null }], nextCursor: null }));
            await flow.replay();
            await vi.waitFor(() => expect(flow.replayDecks()).toHaveLength(1));
            await settle();
            await click('Начать');
            await click('Назад');
            expect(plain(heading().textContent)).toBe('С чего начнём?');
        });
    });

    describe('the days of a week', () => {
        it('are written with the plural of «день» for every count a learner can choose', () => {
            expect([2, 3, 5, 7, 11, 21].map(daysLabel)).toEqual(['2\u00a0дня', '3\u00a0дня', '5\u00a0дней', '7\u00a0дней', '11\u00a0дней', '21\u00a0день']);
        });
    });

    it('closes its dialog when it is destroyed while open', async () => {
        await render();
        await open('welcome');
        const surface = dialog()!;
        fixture.destroy();
        expect(surface.open).toBe(false);
    });
});
