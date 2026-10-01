import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DEMO_ASSETS } from '../../content/exercise/demo/demo-media';
import { Mechanic, PreviewExercise } from '../../content/exercise/exercise-content.models';
import { catalogEntry } from '../../content/exercise/mechanic-catalog';
import { MEDIA_PLAYBACK_RESOLVER, MediaPlaybackResolver } from '../study/media-playback-resolver';
import { fakePlayback } from '../study/study-test-data';
import { learnerContent } from './exercise-draft';
import { ExercisePreviewHostComponent } from './exercise-preview-host.component';
import { PreviewMode, PreviewPresentation } from './exercise-preview.models';

describe('ExercisePreviewHostComponent', () => {
    let fixture: ComponentFixture<ExercisePreviewHostComponent>;
    let http: HttpTestingController;
    let real: jasmine.SpyObj<MediaPlaybackResolver>;

    function presentationOf(exercise: PreviewExercise, mode: PreviewMode = 'DEMO', key = `${mode}:${exercise.type}`,
                            blockedReason: string | null = null): PreviewPresentation {
        return { mode, key, exercise: blockedReason === null ? exercise : null, blockedReason,
            learner: revealed => learnerContent(exercise, { context: null, revealed, placeholders: mode === 'AUTHOR_DRAFT' }) };
    }
    const demo = (mechanic: Mechanic) => presentationOf(catalogEntry(mechanic).demo.exercise);

    beforeEach(() => {
        real = jasmine.createSpyObj<MediaPlaybackResolver>('real', ['resolve']);
        real.resolve.and.callFake(fakePlayback);
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
            { provide: MEDIA_PLAYBACK_RESOLVER, useValue: real }] });
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());

    function show(presentation: PreviewPresentation): void {
        fixture = TestBed.createComponent(ExercisePreviewHostComponent);
        fixture.componentRef.setInput('presentation', presentation);
        refresh();
    }
    const root = () => fixture.nativeElement as HTMLElement;
    const refresh = () => { fixture.detectChanges(); fixture.detectChanges(); };
    function set(presentation: PreviewPresentation): void { fixture.componentRef.setInput('presentation', presentation); refresh(); }
    function click(selector: string, index = 0): void { root().querySelectorAll<HTMLElement>(selector)[index].click(); refresh(); }
    function request(): TestRequest {
        const open = http.match(() => true);
        expect(open.length).withContext('expected exactly one request').toBe(1);
        expect(open[0].request.url).toBe('/api/exercise-previews');
        expect(open[0].request.method).toBe('POST');
        return open[0];
    }
    const action = (call: TestRequest) => call.request.body.action;

    it('shows the demo badge, the explanation and a playable surface', () => {
        show(demo('SELF_CHECK'));
        expect(root().querySelector('.badge')?.textContent?.trim()).toBe('Пример');
        expect(root().querySelector('[data-preview-caption]')?.textContent)
            .toBe('Это пример упражнения. Заполните шаги ниже — здесь появится ваше задание.');
        expect(root().querySelector('h2')?.textContent).toBe('Попробуйте, как это работает');
        expect(root().querySelector('app-learner-exercise')).not.toBeNull();
        expect(root().querySelector('[role="status"].visually-hidden')?.textContent).toContain('пример');
    });

    describe('plays every demo through the author preview endpoint and nothing else', () => {
        it('SELF_CHECK: reveal, rating, completion, restart', () => {
            show(demo('SELF_CHECK'));
            click('[data-answer-control]');
            expect(root().textContent).toContain('Канберра');
            click('[data-first-rating]', 0);
            const call = request();
            expect(call.request.body.exercise.type).toBe('SELF_CHECK');
            expect(action(call)).toEqual({ kind: 'SUBMIT', response: { kind: 'SELF_CHECK', rating: 'NOT_RECALLED' },
                hintedBlankIds: [], pairMistakes: false, transcriptRevealed: false });
            call.flush({ feedback: { result: 'INCORRECT', appliedRules: ['SELF_REPORT'] } }, { headers: { 'Cache-Control': 'private, no-store' } });
            refresh();
            expect(root().querySelector('#preview-result-title')?.textContent).toBe('Нужно повторить');
            expect(root().textContent).toContain('Пример завершён');
            expect(root().textContent).toContain('Не вспомнил');
            expect(document.activeElement?.id).toBe('preview-result-title');
            click('[data-restart]');
            expect(root().querySelector('#preview-result-title')).toBeNull();
            expect(root().querySelector('app-learner-exercise')).not.toBeNull();
            expect(root().textContent).not.toContain('Канберра');
        });

        it('FREE_RESPONSE: right and wrong answers get the server verdict and the reference', () => {
            show(demo('FREE_RESPONSE'));
            const area = root().querySelector<HTMLTextAreaElement>('textarea')!;
            area.value = 'Dienstag'; area.dispatchEvent(new Event('input')); refresh();
            click('button[data-submit]');
            const call = request();
            expect(action(call).response).toEqual({ kind: 'TEXT', text: 'Dienstag' });
            call.flush({ feedback: { result: 'INCORRECT', appliedRules: ['UNICODE_NFC'], reference: 'Mittwoch', referenceContent: [] } },
                { headers: { 'Cache-Control': 'private, no-store' } });
            refresh();
            expect(root().querySelector('.comparison')?.textContent).toContain('Dienstag');
            expect(root().querySelector('.comparison')?.textContent).toContain('Mittwoch');
            expect(root().textContent).toContain('Нужно повторить');
        });

        it('CLOZE: the hint is the server\'s, is reported with the answer and a wrong blank is marked', () => {
            show(demo('CLOZE'));
            const blanks = [...root().querySelectorAll<HTMLInputElement>('app-cloze-passage input')];
            expect(blanks.length).toBe(2);
            click('.cloze-hint');
            const hint = request();
            expect(action(hint).kind).toBe('HINT');
            hint.flush({ blankId: action(hint).blankId, firstLetter: 'м' }, { headers: { 'Cache-Control': 'private, no-store' } });
            refresh();
            expect(root().querySelector('.cloze-letter')?.textContent).toContain('м');
            blanks[0].value = 'математика'; blanks[0].dispatchEvent(new Event('input'));
            blanks[1].value = 'химия'; blanks[1].dispatchEvent(new Event('input'));
            refresh();
            click('button[data-submit]');
            const submit = request();
            expect(action(submit).hintedBlankIds).toEqual([action(hint).blankId]);
            const ids = submit.request.body.exercise.content.passage.filter((segment: { kind: string }) => segment.kind === 'BLANK')
                .map((segment: { blankId: string }) => segment.blankId);
            submit.flush({ feedback: { result: 'PARTIAL', appliedRules: ['PER_BLANK'], blanks: [
                { blankId: ids[0], correct: true, hinted: true, reference: 'математика' },
                { blankId: ids[1], correct: false, hinted: false, reference: 'физика' }] } }, { headers: { 'Cache-Control': 'private, no-store' } });
            refresh();
            expect(root().textContent).toContain('Частично');
            expect(root().querySelector('.cloze-verdict')?.textContent).toContain('с подсказкой');
        });

        it('CHOICE: a wrong and a right option, correct options shown after the verdict', () => {
            show(demo('CHOICE'));
            const options = root().querySelectorAll<HTMLInputElement>('input[type="radio"]');
            options[0].click(); refresh();
            click('button[data-submit]');
            const call = request();
            const correct = call.request.body.exercise.answerKey.correctOptionIds;
            expect(action(call).response.optionIds).not.toEqual(correct);
            call.flush({ feedback: { result: 'INCORRECT', appliedRules: ['EXACT_OPTION_SET'], correctOptionIds: correct } },
                { headers: { 'Cache-Control': 'private, no-store' } });
            refresh();
            expect(root().textContent).toContain('(правильный ответ)');
            click('[data-restart]');
            root().querySelectorAll<HTMLInputElement>('input[type="radio"]')[1].click(); refresh();
            click('button[data-submit]');
            const second = request();
            expect(action(second).response.optionIds).toEqual(correct);
            second.flush({ feedback: { result: 'CORRECT', appliedRules: ['EXACT_OPTION_SET'], correctOptionIds: correct } },
                { headers: { 'Cache-Control': 'private, no-store' } });
            refresh();
            expect(root().querySelector('#preview-result-title')?.textContent).toBe('Верно');
        });

        it('MATCH: pairs are checked one by one on the server, a mistake is remembered for the final answer', () => {
            show(demo('MATCH'));
            const key = new Map<string, string>((catalogEntry('MATCH').demo.exercise as unknown as { answerKey: { pairs: { leftId: string; rightId: string }[] } })
                .answerKey.pairs.map(pair => [pair.leftId, pair.rightId]));
            const left = () => [...root().querySelectorAll<HTMLButtonElement>('button[data-side="left"]:not(:disabled)')];
            const right = () => [...root().querySelectorAll<HTMLButtonElement>('button[data-side="right"]:not(:disabled)')];
            let wrong = 0;
            while (left().length > 0) {
                left()[0].click(); refresh();
                for (let candidate = 0; candidate < right().length; candidate++) {
                    right()[candidate].click(); refresh();
                    const call = request();
                    const { leftId, rightId } = action(call);
                    const correct = key.get(leftId) === rightId;
                    if (!correct) wrong++;
                    call.flush({ correct }, { headers: { 'Cache-Control': 'private, no-store' } });
                    refresh();
                    if (correct) break;
                }
            }
            expect(wrong).toBeGreaterThan(0);
            click('button[data-submit]');
            const submit = request();
            expect(action(submit).pairMistakes).toBeTrue();
            expect(action(submit).response.pairs.length).toBe(4);
            submit.flush({ feedback: { result: 'PARTIAL', appliedRules: ['SERVER_ISSUED_PAIR_MAP', 'PAIR_RETRY'],
                pairs: action(submit).response.pairs.map((pair: { leftId: string; rightId: string }) => ({
                    leftId: pair.leftId, selectedRightId: pair.rightId, correctRightId: pair.rightId, correct: true })) } },
            { headers: { 'Cache-Control': 'private, no-store' } });
            refresh();
            expect(root().querySelectorAll('.pair-feedback li').length).toBe(4);
            expect(root().textContent).toContain('Пример завершён');
            click('[data-restart]');
            expect(root().querySelectorAll('button[data-side="left"]:not(:disabled)').length).toBe(4);
        });
    });

    it('tells the author when checking failed, keeps the answer and lets them try again', () => {
        show(demo('FREE_RESPONSE'));
        const area = root().querySelector<HTMLTextAreaElement>('textarea')!;
        area.value = 'Mittwoch'; area.dispatchEvent(new Event('input')); refresh();
        click('button[data-submit]');
        request().flush(null, { status: 500, statusText: 'x' });
        refresh();
        expect(root().querySelector('.notice.error')?.getAttribute('role')).toBe('alert');
        expect(root().querySelector('.notice.error')?.textContent).toContain('Не удалось проверить ответ');
        expect(root().querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('Mittwoch');
        expect(root().querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBeFalse();
    });

    it('drops a late answer when the shown exercise changed meanwhile and restarts the trial', () => {
        show(demo('FREE_RESPONSE'));
        const area = root().querySelector<HTMLTextAreaElement>('textarea')!;
        area.value = 'Mittwoch'; area.dispatchEvent(new Event('input')); refresh();
        click('button[data-submit]');
        const late = request();
        set(presentationOf(catalogEntry('FREE_RESPONSE').demo.exercise, 'DEMO', 'another-key'));
        expect(late.cancelled).toBeTrue();
        expect(root().querySelector('#preview-result-title')).toBeNull();
        expect(root().querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('');
        expect(root().querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBeFalse();
        http.expectNone('/api/exercise-previews');
    });

    it('keeps the trial when the same key is shown again and resets hints with a new key', () => {
        const first = demo('CLOZE');
        show(first);
        click('.cloze-hint');
        const hint = request();
        hint.flush({ blankId: action(hint).blankId, firstLetter: 'м' }, { headers: { 'Cache-Control': 'private, no-store' } });
        refresh();
        set({ ...first });
        expect(root().querySelector('.cloze-letter')).not.toBeNull();
        set({ ...first, key: 'changed' });
        expect(root().querySelector('.cloze-letter')).toBeNull();
    });

    it('never checks an unfinished draft: submit is disabled with the reason and no request is sent', () => {
        const exercise = catalogEntry('FREE_RESPONSE').demo.exercise;
        show(presentationOf(exercise, 'AUTHOR_DRAFT', 'draft', 'Проверить ответ пока нельзя: Добавьте вопрос.'));
        expect(root().querySelector('.badge')?.textContent?.trim()).toBe('Ваше задание');
        expect(root().querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBeTrue();
        expect(root().querySelector('.blocked')?.textContent).toContain('Добавьте вопрос');
        expect(root().querySelector('[data-preview-caption]')?.textContent).toContain('Проверить ответ можно, когда оно будет заполнено');
        root().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
        http.expectNone('/api/exercise-previews');
    });

    it('does not ask the server for a pair or a hint while the draft is unfinished', () => {
        show(presentationOf(catalogEntry('CLOZE').demo.exercise, 'AUTHOR_DRAFT', 'draft', 'Проверить ответ пока нельзя: нет.'));
        click('.cloze-hint');
        http.expectNone('/api/exercise-previews');
        expect(root().querySelector('.cloze-letter')).toBeNull();
    });

    it('reveals a transcript locally and reports it with the answer', () => {
        const exercise: PreviewExercise = { ...catalogEntry('FREE_RESPONSE').demo.exercise,
            content: { prompt: [{ kind: 'AUDIO', assetId: 'aaaaaaaa-0000-4000-8000-000000000002', title: 'Запись', transcript: 'Mittwoch' }],
                reference: [], responseInput: 'TEXT' } } as PreviewExercise;
        show(presentationOf(exercise, 'AUTHOR_READY', 'ready'));
        expect(root().textContent).not.toContain('Транскрипт: Mittwoch');
        click('.transcript-offer button');
        expect(root().textContent).toContain('Mittwoch');
        http.expectNone('/api/exercise-previews');
        click('button[data-submit]');
        const call = request();
        expect(action(call).transcriptRevealed).toBeTrue();
        call.flush({ feedback: { result: 'CORRECT', appliedRules: [], reference: 'Mittwoch', referenceContent: [] } },
            { headers: { 'Cache-Control': 'private, no-store' } });
    });

    it('plays demo assets from the bundle without the media API and resolves author assets with the real resolver', () => {
        show(demo('CHOICE'));
        expect(real.resolve).not.toHaveBeenCalledWith(DEMO_ASSETS.toneLow);
        expect(real.resolve).not.toHaveBeenCalledWith(DEMO_ASSETS.waveDense);
        expect(root().querySelector('app-native-media-image img')?.getAttribute('src')).toBe('/assets/demo/wave-dense.svg');
        expect(root().querySelectorAll('audio').length).toBe(2);
        expect([...root().querySelectorAll('audio')].map(audio => audio.getAttribute('src')))
            .toEqual(['/assets/demo/tone-low.mp3', '/assets/demo/tone-high.mp3']);

        const authored: PreviewExercise = { ...catalogEntry('FREE_RESPONSE').demo.exercise,
            content: { prompt: [{ kind: 'AUDIO', assetId: 'aaaaaaaa-0000-4000-8000-000000000002', title: 'Моя запись' }],
                reference: [], responseInput: 'TEXT' } } as PreviewExercise;
        set(presentationOf(authored, 'AUTHOR_READY', 'authored'));
        expect(real.resolve).toHaveBeenCalledWith('aaaaaaaa-0000-4000-8000-000000000002');
    });

    it('never calls a learner session, attempt, pair-check, hint or transcript endpoint', () => {
        show(demo('CLOZE'));
        click('.cloze-hint');
        const call = request();
        expect(call.request.url).not.toMatch(/study-sessions|attempts|pair-checks|hints|transcript/);
        call.flush({ blankId: action(call).blankId, firstLetter: 'м' }, { headers: { 'Cache-Control': 'private, no-store' } });
    });
});
