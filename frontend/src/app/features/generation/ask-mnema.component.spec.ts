import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import { PageTransition } from '../../shared/page-transition.service';
import { spyObj, type SpyObj } from '../../../testing/mocks';
import { AskMnemaComponent } from './ask-mnema.component';
import { ESTIMATE_DEBOUNCE_MS } from './estimate-schedule';
import { GenerationApiService } from './generation-api.service';
import { IntentContext, IntentResult, parseIntent } from './generation-intent';
import { clone, examples, ids, problemResponse, usageContract } from './generation-test-data';
import { parseEstimate, parseSessionDetail, serializeSpec } from './generation.models';

const NB = '\u00a0';
const memberKey = '44444444-4444-4444-8444-444444444444';
const material: IntentContext = { kind: 'MATERIAL', memberKey };
const exercise: IntentContext = { kind: 'EXERCISE', exerciseId: '66666666-6666-4666-8666-666666666666' };
const MECHANICS_OPTIONS = ['SELF_CHECK', 'FREE_RESPONSE', 'CLOZE', 'CHOICE', 'MATCH', 'ORDER', 'CATEGORIZE'];

const exercisesAnswer = (mechanics: string[] | 'AUTO' = 'AUTO', perTarget: number | null = 3, notes: unknown[] = []): IntentResult => parseIntent({
    ...clone(examples['intentExercises']), notes,
    spec: { ...clone(examples['intentExercises'].spec), settings: { mechanics, priority: 'UNCOVERED_FIRST',
        quantity: perTarget === null ? { mode: 'AUTO' } : { mode: 'EXACT', perTarget } } },
    chips: [{ kind: 'OPERATION', value: 'EXERCISES' }, { kind: 'MECHANICS', value: mechanics, options: MECHANICS_OPTIONS },
        { kind: 'PER_TARGET', value: perTarget, min: 1, max: 10 }] });
const reviseItemAnswer = (instruction = 'Сделай объяснение проще'): IntentResult => parseIntent({ operation: 'REVISE_ITEM',
    spec: { ...clone(examples['specReviseItem']), instruction },
    chips: [{ kind: 'OPERATION', value: 'REVISE_ITEM' }, { kind: 'INSTRUCTION', value: instruction, maxLength: 2000 }], notes: [] });
const voiceAnswer = (voice = 'male', instruction?: string): IntentResult => parseIntent({ operation: 'REVISE_EXERCISE',
    spec: { ...clone(examples['specReviseExercise']), media: { action: 'AUDIO_REGENERATE', voice }, ...(instruction === undefined ? {} : { instruction }) },
    chips: [{ kind: 'OPERATION', value: 'REVISE_EXERCISE' }, ...(instruction === undefined ? [] : [{ kind: 'INSTRUCTION', value: instruction, maxLength: 2000 }]),
        { kind: 'VOICE', value: voice, options: ['female', 'male'] }], notes: [] });

describe('AskMnemaComponent («Попросить Мнему…», AI-16)', () => {
    let fixture: ComponentFixture<AskMnemaComponent>;
    let api: SpyObj<GenerationApiService>;
    let transition: { navigate: ReturnType<typeof vi.fn> };

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const trigger = (): HTMLButtonElement => root().querySelector<HTMLButtonElement>('.ask-trigger')!;
    const field = (): HTMLTextAreaElement => root().querySelector<HTMLTextAreaElement>('textarea.ask-field')!;
    const labelled = (label: string): HTMLElement | undefined =>
        [...root().querySelectorAll<HTMLElement>('button, a, label')].find(element => element.textContent!.trim() === label);
    const box = (label: string): HTMLInputElement => labelled(label)!.querySelector('input')!;
    const start = (): HTMLButtonElement => root().querySelector<HTMLButtonElement>('.generate-cta')!;
    const estimateAnswer = () => parseEstimate(clone(usageContract['estimateResponse']));
    const created = (session = examples['sessionDetailCreated']) => of({ session: parseSessionDetail(session), replayed: false });
    const lastSpec = () => serializeSpec(api.estimate.mock.calls.at(-1)![1]) as any;
    const sentSpec = () => serializeSpec(api.createSession.mock.calls.at(-1)![1]) as any;

    async function settle(ms = 0): Promise<void> {
        await vi.advanceTimersByTimeAsync(ms);
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
    }

    function create(context: IntentContext = material, inputs: Record<string, unknown> = {}): void {
        TestBed.resetTestingModule();
        vi.useFakeTimers();
        api = spyObj<GenerationApiService>({ createIntent: vi.fn(), estimate: vi.fn(), createSession: vi.fn() });
        transition = { navigate: vi.fn().mockResolvedValue(true) };
        api.estimate.mockReturnValue(of(estimateAnswer()));
        TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: GenerationApiService, useValue: api }, { provide: PageTransition, useValue: transition }] });
        fixture = TestBed.createComponent(AskMnemaComponent);
        fixture.componentRef.setInput('deckId', ids.deckId);
        fixture.componentRef.setInput('context', context);
        for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
        fixture.detectChanges();
    }

    async function ask(answer: IntentResult | HttpErrorResponse, text = 'Сделай все типы упражнений по 3'): Promise<void> {
        api.createIntent.mockReturnValue('operation' in answer ? of(answer) : throwError(() => answer));
        trigger().click();
        fixture.detectChanges();
        field().value = text;
        field().dispatchEvent(new Event('input'));
        fixture.detectChanges();
        field().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
        await settle();
    }

    afterEach(() => vi.useRealTimers());

    describe('the collapsed composer and the sentence', () => {
        it('is one quiet button until it is opened; opening it moves focus to the field and offers the examples of the context', () => {
            create();
            expect(root().querySelector('textarea')).toBeNull();
            expect(trigger().textContent).toContain('Попросить Мнему…');
            expect(trigger().getAttribute('aria-expanded')).toBe('false');
            expect(trigger().getAttribute('aria-controls')).toBeNull();
            trigger().click();
            fixture.detectChanges();
            expect(trigger().getAttribute('aria-expanded')).toBe('true');
            expect(trigger().getAttribute('aria-controls')).toBe(root().querySelector('.ask-panel')!.id);
            expect(root().querySelector('.ask-help')!.textContent).toContain('Сделай все типы упражнений по 3');
            expect(field().getAttribute('enterkeyhint')).toBe('send');
            expect(root().querySelector(`label[for="${field().id}"]`)!.textContent).toContain('Что вы хотите?');
            create(exercise);
            trigger().click();
            fixture.detectChanges();
            expect(root().querySelector('.ask-help')!.textContent).toContain('Замени аудио на мужской голос');
        });

        it('says that nothing is charged until «Запустить», and that Мнема works with the saved version when the page has unsaved changes', () => {
            create(exercise, { unsaved: true });
            trigger().click();
            fixture.detectChanges();
            expect(root().textContent).toContain('Пока вы не нажмёте «Запустить», ничего не списывается');
            expect(root().textContent).toContain('сохранённой версией');
        });

        it('puts a chosen example into the field, and closes with the button taking focus back (Esc, or «Свернуть»)', async () => {
            create();
            trigger().click();
            fixture.detectChanges();
            root().querySelector<HTMLButtonElement>('.ask-example')!.click();
            fixture.detectChanges();
            expect(field().value).toBe('Сделай все типы упражнений по 3');
            field().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            await settle();
            expect(root().querySelector('textarea')).toBeNull();
            expect(document.activeElement === trigger() || document.activeElement === document.body).toBe(true);
            trigger().click();
            fixture.detectChanges();
            expect(field().value).toBe('Сделай все типы упражнений по 3');
            labelled('Свернуть')!.click();
            fixture.detectChanges();
            expect(root().querySelector('textarea')).toBeNull();
        });

        it('sends on Enter, not on Shift+Enter, never while an IME composes, and never an empty or blank sentence', async () => {
            create();
            api.createIntent.mockReturnValue(of(exercisesAnswer()));
            trigger().click();
            fixture.detectChanges();
            const press = (init: KeyboardEventInit): KeyboardEvent => {
                const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true, ...init });
                field().dispatchEvent(event);
                return event;
            };
            expect(press({}).defaultPrevented).toBe(true);
            expect(api.createIntent).not.toHaveBeenCalled();
            field().value = '   ';
            field().dispatchEvent(new Event('input'));
            fixture.detectChanges();
            press({});
            expect(api.createIntent).not.toHaveBeenCalled();
            field().value = 'Сделай все типы упражнений по 3';
            field().dispatchEvent(new Event('input'));
            fixture.detectChanges();
            expect(press({ shiftKey: true }).defaultPrevented).toBe(false);
            press({ isComposing: true });
            press({ keyCode: 229 });
            expect(api.createIntent).not.toHaveBeenCalled();
            field().dispatchEvent(new Event('compositionstart'));
            field().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
            fixture.detectChanges();
            expect(root().querySelector('textarea')).not.toBeNull();
            field().dispatchEvent(new Event('compositionend'));
            press({});
            await settle();
            expect(api.createIntent).toHaveBeenCalledTimes(1);
            expect(api.createIntent).toHaveBeenCalledWith(ids.deckId, material, 'Сделай все типы упражнений по 3');
        });

        it('says it is reading the sentence while the free call runs, and a second Enter does not send it twice', async () => {
            create();
            const pending = new Subject<IntentResult>();
            api.createIntent.mockReturnValue(pending);
            trigger().click();
            fixture.detectChanges();
            field().value = 'Сделай проще';
            field().dispatchEvent(new Event('input'));
            const enter = () => field().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
            enter();
            enter();
            fixture.detectChanges();
            expect(api.createIntent).toHaveBeenCalledTimes(1);
            expect(root().querySelector('[role="status"]')!.textContent).toContain('Мнема разбирает запрос');
            expect(root().querySelector('.ask-body')!.getAttribute('aria-busy')).toBe('true');
            pending.next(reviseItemAnswer());
            pending.complete();
            await settle();
            expect(root().querySelector('.ask-body')!.getAttribute('aria-busy')).toBe('false');
        });
    });

    describe('the chips: nothing is reserved until «Запустить»', () => {
        it('shows the exercises chips (the builder\'s own choices), the estimate, and starts nothing by itself', async () => {
            create();
            await ask(exercisesAnswer());
            expect(root().querySelector('h2')!.textContent).toBe('Мнема поняла так');
            expect(document.activeElement).toBe(root().querySelector('h2'));
            expect(root().querySelector('.ask-quote')!.textContent).toContain('Сделай все типы упражнений по 3');
            expect(root().querySelector('app-exercise-settings-fields')).not.toBeNull();
            expect(box('Авто').checked).toBe(true);
            expect(root().textContent).toContain(`3${NB}упражнения на${NB}материал`);
            // the estimate is the free preflight; it reserves nothing
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(api.estimate).toHaveBeenCalledTimes(1);
            expect(lastSpec()).toMatchObject({ kind: 'EXERCISES', settings: { mechanics: 'AUTO', quantity: { mode: 'EXACT', perTarget: 3 } } });
            expect(root().querySelector('.estimate')!.textContent).toBe(`≈${NB}6${NB}% лимита`);
            expect(api.createSession).not.toHaveBeenCalled();
            expect(root().querySelectorAll('.generate-cta')).toHaveLength(1);
            expect(start().textContent).toBe('Запустить');
        });

        it('lets the owner drop a mechanic and change the number before starting; the session gets the edited spec, once, with one command', async () => {
            create();
            await ask(exercisesAnswer(['CLOZE', 'CHOICE', 'MATCH']));
            expect(box('Заполнить пропуски').checked).toBe(true);
            box('Заполнить пропуски').click();
            fixture.detectChanges();
            const slider = root().querySelector<HTMLInputElement>('input[type=range]')!;
            slider.value = '5';
            slider.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings).toMatchObject({ mechanics: ['CHOICE', 'MATCH'], quantity: { mode: 'EXACT', perTarget: 5 } });
            expect(api.createSession).not.toHaveBeenCalled();
            api.createSession.mockReturnValue(created());
            start().click();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            const [deck, , command] = api.createSession.mock.calls[0]!;
            expect(deck).toBe(ids.deckId);
            expect(command).toMatch(/^[0-9a-f-]{36}$/u);
            expect(sentSpec()).toMatchObject({ kind: 'EXERCISES', targets: [{ memberKey }], settings: { mechanics: ['CHOICE', 'MATCH'], quantity: { mode: 'EXACT', perTarget: 5 } } });
            expect(transition.navigate).toHaveBeenCalledWith(['/decks', ids.deckId, 'workshop', examples['sessionDetailCreated'].sessionId]);
        });

        it('can leave the number to Мнема («Авто» quantity) and shows what the server did to the number as a note chip', async () => {
            create();
            await ask(exercisesAnswer('AUTO', 10, [{ code: 'PER_TARGET_CLAMPED', text: 'Не больше 10 на материал', limit: 10 }]), 'Сделай 1000 упражнений');
            expect(root().querySelector('[data-note="PER_TARGET_CLAMPED"]')!.textContent).toBe('Не больше 10 на материал');
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.quantity).toEqual({ mode: 'EXACT', perTarget: 10 });
            labelled('Авто')!.click();
            const auto = [...root().querySelectorAll<HTMLInputElement>('input[type=radio]')].find(input => input.value === 'AUTO')!;
            auto.click();
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.quantity).toEqual({ mode: 'AUTO' });
            expect(root().querySelectorAll('input[type=range]')).toHaveLength(0);
            // an AUTO answer from the server starts as AUTO
            create();
            await ask(exercisesAnswer('AUTO', null));
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.quantity).toEqual({ mode: 'AUTO' });
        });

        it('offers a share of the limit only when the server\'s spec already uses it', async () => {
            create();
            await ask(exercisesAnswer());
            expect(root().textContent).not.toContain('Не больше X% лимита');
            const answer = clone(examples['intentExercises']);
            answer.spec.settings.quantity = { mode: 'BUDGET_PERCENT', percent: 7 };
            create();
            await ask(parseIntent(answer));
            expect(root().textContent).toContain('Не больше X% лимита');
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().settings.quantity).toEqual({ mode: 'BUDGET_PERCENT', percent: 7 });
        });

        it('«Изменить запрос» goes back to the sentence, which is still there, with focus in the field and the first answer forgotten', async () => {
            create();
            await ask(exercisesAnswer());
            labelled('Изменить запрос')!.click();
            await settle();
            expect(root().querySelector('h2')).toBeNull();
            expect(field().value).toBe('Сделай все типы упражнений по 3');
            expect(document.activeElement).toBe(field());
            expect(api.createSession).not.toHaveBeenCalled();
        });
    });

    describe('revisions: the instruction and the voice are chips too', () => {
        it('edits the instruction of REVISE_ITEM; an empty one cannot start and a changed one is what the session is created with', async () => {
            create();
            await ask(reviseItemAnswer(), 'Сделай объяснение проще');
            const instruction = root().querySelector<HTMLTextAreaElement>('.ask-instruction textarea')!;
            expect(instruction.value).toBe('Сделай объяснение проще');
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec()).toMatchObject({ kind: 'REVISE_ITEM', instruction: 'Сделай объяснение проще' });
            instruction.value = '   ';
            instruction.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            // pressing says what is missing next to the field, linked to it, and sends nothing
            start().click();
            await settle();
            expect(api.createSession).not.toHaveBeenCalled();
            expect(instruction.getAttribute('aria-invalid')).toBe('true');
            expect(root().querySelector(`#${instruction.getAttribute('aria-describedby')}`)!.textContent).toContain('Напишите, что изменить');
            expect(document.activeElement).toBe(instruction);
            instruction.value = 'Сделай';
            instruction.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            expect(instruction.getAttribute('aria-invalid')).toBeNull();
            instruction.value = '   ';
            instruction.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            instruction.value = 'Сделай короче';
            instruction.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            api.createSession.mockReturnValue(created());
            start().click();
            await settle();
            expect(sentSpec()).toEqual({ kind: 'REVISE_ITEM', target: examples['specReviseItem'].target, instruction: 'Сделай короче' });
        });

        it('shows the voice as a chip, says the synthesis is not connected, changes the voice, and may leave it alone beside a request', async () => {
            create(exercise);
            await ask(voiceAnswer('male'), 'Замени аудио на мужской голос');
            expect(root().querySelector('.ask-instruction')).toBeNull();
            expect(root().querySelector('.ask-voice')!.textContent).toContain('Синтез речи пока не подключён');
            const radios = [...root().querySelectorAll<HTMLInputElement>('.ask-voice input')];
            expect(radios.map(input => input.value)).toEqual(['NONE', 'female', 'male']);
            expect(radios.find(input => input.checked)!.value).toBe('male');
            expect(root().querySelector('.ask-voice')!.textContent).toContain('Голос: мужской');
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec()).toEqual(examples['specReviseExercise']);
            radios[1]!.click();
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec().media).toEqual({ action: 'AUDIO_REGENERATE', voice: 'female' });
            // «Не менять» with no request would be an empty revision: the request field appears and nothing can start
            radios[0]!.click();
            fixture.detectChanges();
            expect(root().querySelector('.ask-instruction textarea')).not.toBeNull();
            start().click();
            await settle();
            expect(api.createSession).not.toHaveBeenCalled();
            expect(root().querySelector('.ask-instruction .ask-error')).not.toBeNull();
            const instruction = root().querySelector<HTMLTextAreaElement>('.ask-instruction textarea')!;
            instruction.value = 'Сделай вопрос короче';
            instruction.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec()).toEqual({ kind: 'REVISE_EXERCISE', target: examples['specReviseExercise'].target, instruction: 'Сделай вопрос короче' });
        });

        it('starts a revision of the exercise with a text request and a voice together, the request marked optional beside the voice', async () => {
            create(exercise);
            await ask(voiceAnswer('female', 'Короче'), 'Короче и женским голосом');
            expect(root().querySelector('.ask-instruction label')!.textContent).toContain('необязательно');
            api.createSession.mockReturnValue(created());
            start().click();
            await settle();
            expect(sentSpec()).toMatchObject({ kind: 'REVISE_EXERCISE', instruction: 'Короче', media: { action: 'AUDIO_REGENERATE', voice: 'female' } });
        });
    });

    describe('what Мнема cannot do', () => {
        it('says so, offers the builder and the editor, and has nothing to start', async () => {
            create(material, { builderLink: ['/decks', ids.deckId, 'exercises', 'generate'], builderQuery: { members: memberKey },
                editLink: ['/decks', ids.deckId, 'materials', memberKey, 'edit'], editQuery: { ordinal: '1' } });
            await ask(parseIntent(examples['intentUnsupported']), 'Нарисуй картинку');
            expect(root().querySelector('h2')!.textContent).toBe('Мнема пока не умеет это');
            expect(root().querySelector('.note-chip')!.textContent).toContain('пока не умею');
            expect(root().querySelector('.generate-cta')).toBeNull();
            expect(root().querySelector('a[data-ask-builder]')!.getAttribute('href')).toBe(`/decks/${ids.deckId}/exercises/generate?members=${memberKey}`);
            expect(root().querySelector('a[data-ask-edit]')!.getAttribute('href')).toBe(`/decks/${ids.deckId}/materials/${memberKey}/edit?ordinal=1`);
            expect(labelled('Открыть билдер упражнений')).toBeDefined();
            expect(labelled('Править самому')).toBeDefined();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(api.estimate).not.toHaveBeenCalled();
            expect(api.createSession).not.toHaveBeenCalled();
        });

        it('offers only «Изменить запрос» where there is no builder and no editor to open', async () => {
            create(exercise);
            await ask(parseIntent(examples['intentUnsupported']), 'Что-то странное');
            expect(root().querySelector('a')).toBeNull();
            expect(labelled('Изменить запрос')).toBeDefined();
        });
    });

    describe('when the free call fails', () => {
        it.each([
            [429, { code: 'RATE_LIMITED', retryAfter: 90 }, 'Подождите 2'],
            [409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'aiGeneration' }, 'Мнема сейчас недоступна'],
            [404, {}, 'больше недоступны'],
            [503, {}, 'бесплатный']
        ])('answers %i in words, keeps the sentence and the field, and reserves nothing', async (status, body, text) => {
            create();
            await ask(problemResponse(status as number, body as Record<string, unknown>));
            expect(root().querySelector('.ask-error')!.textContent).toContain(text);
            expect(root().querySelector('.ask-error')!.getAttribute('role')).toBe('alert');
            expect(field().value).toBe('Сделай все типы упражнений по 3');
            expect(field().getAttribute('aria-invalid')).toBe('true');
            expect(api.createSession).not.toHaveBeenCalled();
            expect(api.estimate).not.toHaveBeenCalled();
        });

        it('limits the sentence to 2000 characters in the field, as the server does', () => {
            create();
            trigger().click();
            fixture.detectChanges();
            expect(field().getAttribute('maxlength')).toBe('2000');
        });
    });

    describe('starting: the only step that reserves', () => {
        it('keeps the button busy until the Workshop is open, so a second press cannot start another session', async () => {
            create();
            await ask(exercisesAnswer());
            await settle(ESTIMATE_DEBOUNCE_MS);
            const navigating = new Subject<boolean>();
            transition.navigate.mockReturnValue(new Promise<boolean>(resolve => navigating.subscribe(resolve)));
            api.createSession.mockReturnValue(created());
            start().click();
            start().click();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            expect(start().textContent).toBe('Запускаем…');
            expect(start().getAttribute('aria-disabled')).toBe('true');
            navigating.next(true);
            await settle();
            expect(start().textContent).toBe('Запустить');
        });

        it('repeats the same command after an answer that never came, and takes a new one after a refusal', async () => {
            create();
            await ask(reviseItemAnswer());
            await settle(ESTIMATE_DEBOUNCE_MS);
            api.createSession.mockReturnValueOnce(throwError(() => problemResponse(503))).mockReturnValueOnce(throwError(() => problemResponse(409,
                { code: 'GENERATION_STATE_CONFLICT', reason: 'SOURCE_STALE' }))).mockReturnValue(created());
            start().click();
            await settle();
            expect(root().querySelector('.notice.error')!.textContent).toContain('Повторите');
            start().click();
            await settle();
            const [, , first] = api.createSession.mock.calls[0]!;
            expect(api.createSession.mock.calls[1]![2]).toBe(first);
            expect(root().querySelector('.notice.error')!.textContent).toContain('В колоде остался прежний текст');
            start().click();
            await settle();
            expect(api.createSession.mock.calls[2]![2]).not.toBe(first);
            expect(transition.navigate).toHaveBeenCalledTimes(1);
        });

        it.each([
            [400, { code: 'INVALID_REQUEST', reason: 'TARGET_UNSUPPORTED_BLOCK' }, 'не умеет переписывать'],
            [422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EDIT_TARGET_SIZE' }, 'слишком длинный'],
            [409, { code: 'SOURCE_UNAVAILABLE' }, 'Материал уже изменился']
        ])('explains a refused revision (%i) in words and starts no Workshop', async (status, body, text) => {
            create();
            await ask(reviseItemAnswer());
            await settle(ESTIMATE_DEBOUNCE_MS);
            api.createSession.mockReturnValue(throwError(() => problemResponse(status, body)));
            start().click();
            await settle();
            expect(root().querySelector('.notice.error')!.textContent).toContain(text);
            expect(transition.navigate).not.toHaveBeenCalled();
            expect(start().textContent).toBe('Запустить');
        });

        it('explains the exercise limits with the numbers, and the active sessions in words', async () => {
            create();
            await ask(exercisesAnswer());
            await settle(ESTIMATE_DEBOUNCE_MS);
            api.createSession.mockReturnValueOnce(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_SESSION',
                limits: { maxExercisesPerSession: 60 } })));
            start().click();
            await settle();
            expect(root().querySelector('.notice.error')!.textContent).toContain('не больше 60');
            api.createSession.mockReturnValueOnce(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'ACTIVE_SESSIONS' })));
            start().click();
            await settle();
            expect(root().querySelector('.notice.error')!.textContent).toContain('три мастерские');
        });

        it('explains the usage limit of the budget, before and after the server answers', async () => {
            create();
            await ask(exercisesAnswer());
            const blocked = clone(usageContract['estimateResponse']);
            blocked.canStart = false;
            api.estimate.mockReturnValue(of(parseEstimate(blocked)));
            labelled('Авто')!.click();
            box('Выбрать ответ').click();
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(root().querySelector('.estimate')!.textContent).toContain('Не хватит лимита');
            start().click();
            await settle();
            expect(root().querySelector('.notice.limit')).not.toBeNull();
            expect(api.createSession).not.toHaveBeenCalled();

            create();
            await ask(reviseItemAnswer());
            await settle(ESTIMATE_DEBOUNCE_MS);
            api.createSession.mockReturnValue(throwError(() => problemResponse(409, { code: 'USAGE_LIMIT_REACHED', bucket: 'ai_credits', window: 'DAY', unit: 'CREDITS',
                limit: 100, used: 98, required: 4, offered: true, renewsAt: '2026-10-05T00:00:00Z', fitsAfterRenewal: true, plan: 'FREE' })));
            start().click();
            await settle();
            expect(root().querySelector('.notice.limit')!.textContent).toContain('лимит');
        });

        it('explains an estimate that the server refuses (a revision of a block Мнема cannot rewrite) and does not start it', async () => {
            create();
            api.estimate.mockReturnValue(throwError(() => problemResponse(400, { code: 'INVALID_REQUEST', reason: 'TARGET_UNSUPPORTED_BLOCK' })));
            await ask(reviseItemAnswer());
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(root().querySelector('.estimate')!.textContent).toContain('не умеет переписывать');
            expect(root().querySelector('.estimate')!.classList.contains('is-problem')).toBe(true);
            start().click();
            await settle();
            expect(api.createSession).not.toHaveBeenCalled();
            expect(root().querySelector('.notice.error')!.textContent).toContain('не умеет переписывать');
        });

        it('says the budget cannot be estimated but a start is still possible, and describes a limit of the exercises estimate', async () => {
            create();
            api.estimate.mockReturnValue(throwError(() => problemResponse(503)));
            await ask(exercisesAnswer());
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(root().querySelector('.estimate')!.textContent).toContain('запустить можно');
            api.estimate.mockReturnValue(throwError(() => problemResponse(422, { code: 'RESOURCE_LIMIT_EXCEEDED', limit: 'EXERCISES_PER_TARGET' })));
            labelled('Авто')!.click();
            box('Выбрать ответ').click();
            fixture.detectChanges();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(root().querySelector('.estimate')!.textContent).toContain('На один материал');
            start().click();
            await settle();
            expect(root().querySelector('.notice.error')!.textContent).toContain('На один материал');
        });
    });
    describe('review fixes (AI-16)', () => {
        it('shows a created session when the page does not let the owner move to it, and never makes a second one for the same request', async () => {
            create(material, { unsaved: true });
            await ask(reviseItemAnswer());
            expect(root().textContent).toContain('несохранённые изменения');
            await settle(ESTIMATE_DEBOUNCE_MS);
            transition.navigate.mockResolvedValue(false);
            api.createSession.mockReturnValue(created());
            start().click();
            await settle();
            const launched = root().querySelector('[data-launched]')!;
            expect(launched.textContent).toContain('Правка запущена');
            expect(launched.querySelector('a')!.getAttribute('href')).toBe(`/decks/${ids.deckId}/workshop/${examples['sessionDetailCreated'].sessionId}`);
            expect(start().getAttribute('aria-disabled')).toBe('true');
            start().click();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(1);
            // another request is another session, with its own command
            const instruction = root().querySelector<HTMLTextAreaElement>('.ask-instruction textarea')!;
            instruction.value = 'Сделай короче';
            instruction.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            expect(start().getAttribute('aria-disabled')).toBeNull();
            start().click();
            await settle();
            expect(api.createSession).toHaveBeenCalledTimes(2);
            expect(api.createSession.mock.calls[1]![2]).not.toBe(api.createSession.mock.calls[0]![2]);
        });

        it('says «Мастерская запущена» for exercises, and names the material of an exercise in its own words', async () => {
            create(exercise);
            await ask(exercisesAnswer());
            expect(root().querySelector('.ask-for')!.textContent).toContain('Упражнения для материала этого упражнения.');
            await settle(ESTIMATE_DEBOUNCE_MS);
            transition.navigate.mockResolvedValue(false);
            api.createSession.mockReturnValue(created());
            start().click();
            await settle();
            expect(root().querySelector('[data-launched]')!.textContent).toContain('Мастерская запущена');
        });

        it('never sends or prices an instruction the field no longer shows (the voice hid it)', async () => {
            create(exercise);
            await ask(voiceAnswer('male'), 'Замени аудио на мужской голос');
            const radios = [...root().querySelectorAll<HTMLInputElement>('.ask-voice input')];
            radios[0]!.click();
            fixture.detectChanges();
            const instruction = root().querySelector<HTMLTextAreaElement>('.ask-instruction textarea')!;
            instruction.value = 'Сделай вопрос короче';
            instruction.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            radios[2]!.click();
            fixture.detectChanges();
            expect(root().querySelector('.ask-instruction')).toBeNull();
            await settle(ESTIMATE_DEBOUNCE_MS);
            expect(lastSpec()).toEqual(examples['specReviseExercise']);
            api.createSession.mockReturnValue(created());
            start().click();
            await settle();
            expect(sentSpec()).toEqual(examples['specReviseExercise']);
        });

        it('forgets the sentence, the answer and the commands when it is about another material or exercise', async () => {
            create(material);
            await ask(reviseItemAnswer());
            expect(root().querySelector('h2')).not.toBeNull();
            fixture.componentRef.setInput('context', { kind: 'MATERIAL', memberKey: '44444444-4444-4444-8444-444444444445' });
            fixture.detectChanges();
            await settle();
            expect(root().querySelector('textarea')).toBeNull();
            trigger().click();
            fixture.detectChanges();
            expect(field().value).toBe('');
            expect(root().querySelector('h2')).toBeNull();
            // the same context again changes nothing
            field().value = 'Сделай проще';
            field().dispatchEvent(new Event('input'));
            fixture.componentRef.setInput('context', { kind: 'MATERIAL', memberKey: '44444444-4444-4444-8444-444444444445' });
            fixture.detectChanges();
            expect(field().value).toBe('Сделай проще');
        });

        it('lets the note about a clamped number go once the owner changes the number', async () => {
            create();
            await ask(exercisesAnswer('AUTO', 10, [{ code: 'PER_TARGET_CLAMPED', text: 'Не больше 10 на материал', limit: 10 }]), 'Сделай 1000 упражнений');
            expect(root().querySelector('[data-note="PER_TARGET_CLAMPED"]')).not.toBeNull();
            const slider = root().querySelector<HTMLInputElement>('input[type=range]')!;
            slider.value = '4';
            slider.dispatchEvent(new Event('input'));
            fixture.detectChanges();
            expect(root().querySelector('[data-note="PER_TARGET_CLAMPED"]')).toBeNull();
        });

        it('keeps the status region out of the busy part of the composer', async () => {
            create();
            trigger().click();
            fixture.detectChanges();
            expect(root().querySelector('.ask-body [role="status"]')).toBeNull();
            expect(root().querySelector('[role="status"]')).not.toBeNull();
            expect(root().querySelector('section[aria-label]')).toBeNull();
        });
    });
});
