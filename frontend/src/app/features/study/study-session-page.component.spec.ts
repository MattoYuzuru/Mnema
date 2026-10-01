import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { OwnDeck } from '../own-decks/own-deck.models';
import { StudyApiService } from './study-api.service';
import { AttemptCommand, AttemptOutcome, ReadyStudySession, StudyPresentation } from './study.models';
import { StudyRecoveryService } from './study-recovery.service';
import { StudySessionPageComponent } from './study-session-page.component';
import { MEDIA_PLAYBACK_RESOLVER, MediaPlaybackResolver } from './media-playback-resolver';
import { clone, fakePlayback, ids, mechanics } from './study-test-data';

describe('StudySessionPageComponent', () => {
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const sessionId = ids.sessionId;
    const fixtures = mechanics['presentations'];
    let api: jasmine.SpyObj<StudyApiService>;
    let recovery: jasmine.SpyObj<StudyRecoveryService>;
    let playback: jasmine.SpyObj<MediaPlaybackResolver>;
    let fixture: ComponentFixture<StudySessionPageComponent>;
    let now = 1000;

    beforeEach(() => {
        now = 1000;
        const decks = jasmine.createSpyObj<OwnDecksApiService>('OwnDecksApiService', ['detail']);
        decks.detail.and.returnValue(of(deck));
        api = jasmine.createSpyObj<StudyApiService>('StudyApiService',
            ['start', 'read', 'refill', 'submit', 'progress', 'replaySources', 'restart', 'revealTranscript', 'checkPair', 'hint']);
        api.progress.and.returnValue(of({ asOf: '2026-10-01T10:00:00Z', items: [], nextCursor: null }));
        api.replaySources.and.returnValue(of({ asOf: '2026-10-01T10:00:00Z', localStudyDate: '2026-10-01', items: [] }));
        recovery = jasmine.createSpyObj<StudyRecoveryService>('StudyRecoveryService', ['restore', 'save', 'clear', 'now']);
        recovery.restore.and.returnValue(null); recovery.now.and.callFake(() => now);
        playback = jasmine.createSpyObj<MediaPlaybackResolver>('MediaPlaybackResolver', ['resolve']);
        playback.resolve.and.callFake(fakePlayback);
        const router = jasmine.createSpyObj<Router>('Router', ['navigate']); router.navigate.and.resolveTo(true);
        TestBed.configureTestingModule({ providers: [
            { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
            { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decks },
            { provide: StudyApiService, useValue: api }, { provide: StudyRecoveryService, useValue: recovery },
            { provide: MEDIA_PLAYBACK_RESOLVER, useValue: playback }
        ] });
    });

    it('keeps the self-check reference hidden until the explicit reveal, then submits the rating', () => {
        startWith('selfCheck');
        const root = page();
        expect(root.textContent).toContain('Назовите органеллы 1–3');
        expect(root.textContent).not.toContain('Ядро, митохондрии и рибосомы.');
        expect(root.textContent).not.toContain('Не вспомнил');
        click('[data-answer-control]');
        expect(root.textContent).toContain('Ядро, митохондрии и рибосомы.');
        expect(root.textContent).toContain('Вспомнил полностью');

        api.submit.and.returnValue(of({ value: outcome('selfCheck'), replayed: false }));
        ratingButton('Вспомнил частично').click(); fixture.detectChanges();
        const command = api.submit.calls.mostRecent().args[2];
        expect(command.response).toEqual({ kind: 'SELF_CHECK', rating: 'PARTIAL' });
        expect(Object.keys(command)).not.toContain('hintsUsed');
        expect(root.querySelector('#feedback-title')).not.toBeNull();
    });

    it('submits the exact free-response text and never starts the voice path from the disabled microphone', () => {
        const getUserMedia = spyOn(navigator.mediaDevices, 'getUserMedia').and.rejectWith(new Error('must not be called'));
        const recorder = jasmine.createSpy('MediaRecorder');
        const speech = jasmine.createSpy('SpeechRecognition');
        const globals = window as unknown as Record<string, unknown>;
        const saved = { MediaRecorder: globals['MediaRecorder'], SpeechRecognition: globals['SpeechRecognition'],
            webkitSpeechRecognition: globals['webkitSpeechRecognition'] };
        globals['MediaRecorder'] = recorder; globals['SpeechRecognition'] = speech; globals['webkitSpeechRecognition'] = speech;
        const xhr = spyOn(XMLHttpRequest.prototype, 'open').and.callThrough();
        const fetchSpy = spyOn(window, 'fetch').and.callThrough();
        try {
            startWith('freeResponse');
            const root = page();
            const mic = [...root.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.includes('Ответить голосом'))!;
            expect(mic.disabled).toBeTrue();
            const reason = root.querySelector<HTMLElement>(`#${mic.getAttribute('aria-describedby')}`)!;
            expect(reason.textContent).toContain('Голосовой ответ пока недоступен');
            mic.click(); mic.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' })); fixture.detectChanges();
            expect(getUserMedia).not.toHaveBeenCalled();
            expect(recorder).not.toHaveBeenCalled();
            expect(speech).not.toHaveBeenCalled();
            expect(xhr).not.toHaveBeenCalled();
            expect(fetchSpy).not.toHaveBeenCalled();
            expect(api.submit).not.toHaveBeenCalled();
            expect(api.hint).not.toHaveBeenCalled();
            expect(api.revealTranscript).not.toHaveBeenCalled();

            api.submit.and.returnValue(of({ value: outcome('freeResponse'), replayed: false }));
            type('textarea', ' erinnerung '); now = 5200;
            click('button[data-submit]');
            const command = api.submit.calls.mostRecent().args[2];
            expect(command.response).toEqual({ kind: 'TEXT', text: ' erinnerung ' });
            expect(command.durationMs).toBe(4200);
            expect(root.textContent).toContain('Ваш ответ');
            expect(root.textContent).toContain('Erinnerung');
        } finally {
            for (const key of Object.keys(saved) as (keyof typeof saved)[]) {
                if (saved[key] === undefined) delete globals[key]; else globals[key] = saved[key];
            }
        }
    });

    it('offers a transcript only when a block advertises one and shows it after the explicit reveal', () => {
        startWith('freeResponse');
        const root = page();
        expect(root.textContent).not.toContain('Erinnerung');
        expect(root.textContent).toContain('слабее влияет на прогресс');
        api.revealTranscript.and.returnValue(of({ presentationId: fixtures['freeResponse'].presentationId,
            content: { type: 'FREE_RESPONSE', content: mechanics['transcriptRevealResponse'].content } }));
        click('.transcript-offer button');
        expect(api.revealTranscript).toHaveBeenCalledWith(deck.deckId, sessionId, fixtures['freeResponse'].presentationId,
            fixtures['freeResponse'].nonce, 'FREE_RESPONSE');
        expect(root.textContent).toContain('Транскрипт: Erinnerung');
        expect(root.querySelector('.transcript-offer')).toBeNull();
        expect(fixture.componentInstance.current()?.transcriptRevealed).toBeTrue();
    });

    it('renders every cloze blank with its own input and a hint button only where the author allowed it', () => {
        startWith('cloze');
        const root = page();
        const inputs = root.querySelectorAll<HTMLInputElement>('app-cloze-passage input');
        expect(inputs.length).toBe(3);
        expect(inputs[0].getAttribute('aria-label')).toBe('Пропуск 1 из 3');
        expect(root.querySelector('app-cloze-passage')?.textContent).toContain('list.stream()');
        // Blank 1 may be hinted, blank 2 may not, blank 3 already has a server-recorded letter.
        const hintButtons = [...root.querySelectorAll<HTMLButtonElement>('.cloze-hint')];
        expect(hintButtons.map(button => button.getAttribute('aria-label'))).toEqual(['Первая буква, пропуск 1']);
        expect(root.querySelector('.cloze-letter')?.textContent).toContain('m');
        expect(root.querySelectorAll('.cloze-letter').length).toBe(1);

        api.hint.and.returnValue(of(mechanics['hintResponse']));
        hintButtons[0].click(); fixture.detectChanges();
        expect(api.hint).toHaveBeenCalledOnceWith(deck.deckId, sessionId, fixtures['cloze'].presentationId,
            fixtures['cloze'].nonce, mechanics['hintCommand'].blankId);
        expect(root.querySelectorAll('.cloze-letter').length).toBe(2);
        expect(root.querySelector('.cloze-hint')).toBeNull();
        expect(fixture.componentInstance.current()?.hints.map(hint => hint.blankId))
            .toContain(mechanics['hintCommand'].blankId);

        api.submit.and.returnValue(of({ value: outcome('cloze'), replayed: false }));
        const values = ['map', 'toSet', 'Map'];
        inputs.forEach((input, index) => { input.value = values[index]; input.dispatchEvent(new Event('input')); });
        click('button[data-submit]');
        expect(api.submit.calls.mostRecent().args[2].response).toEqual({ kind: 'CLOZE', blanks: [
            { blankId: 'b1a00000-0000-4000-8000-000000000001', text: 'map' },
            { blankId: 'b1a00000-0000-4000-8000-000000000002', text: 'toSet' },
            { blankId: 'b1a00000-0000-4000-8000-000000000003', text: 'Map' }
        ] });
        // Per-blank feedback with the reference and the hint disclosure.
        const verdicts = [...root.querySelectorAll('.cloze-verdict')].map(node => node.textContent!.replace(/\s+/g, ' ').trim());
        expect(verdicts).toEqual(['Верно', 'Неверно, ответ: toList', 'Верно, с подсказкой']);
    });

    it('does not record a failed hint and keeps the typed values', () => {
        startWith('cloze');
        const root = page();
        const first = root.querySelector<HTMLInputElement>('app-cloze-passage input')!;
        first.value = 'ma'; first.dispatchEvent(new Event('input'));
        api.hint.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
        click('.cloze-hint');
        expect(root.textContent).toContain('Не удалось получить подсказку');
        expect(root.querySelector<HTMLInputElement>('app-cloze-passage input')?.value).toBe('ma');
        expect(fixture.componentInstance.current()?.hints.length).toBe(1);
    });

    it('uses checkbox semantics for multiple choice and keeps players out of the selectable control', () => {
        startWith('choice');
        const root = page();
        const boxes = root.querySelectorAll<HTMLInputElement>('app-choice-list input[type="checkbox"]');
        expect(boxes.length).toBe(4);
        expect(root.querySelectorAll('input[type="radio"]').length).toBe(0);
        // No interactive element is nested in a label or a button, and every player is a sibling of its label.
        expect(root.querySelectorAll('label button, label audio, label video, button button, button audio, button video').length).toBe(0);
        const audioOption = root.querySelectorAll('app-choice-list li')[3];
        expect(audioOption.querySelector('app-native-media-player')).not.toBeNull();
        expect(audioOption.querySelector('label')?.textContent).toContain('Звук');
        expect(audioOption.querySelector('audio')?.getAttribute('aria-label')).toBe('Аудио, вариант 4');

        const play = audioOption.querySelector<HTMLButtonElement>('button[aria-label="Воспроизвести"]')!;
        const media = audioOption.querySelector('audio') as HTMLAudioElement;
        spyOn(media, 'play').and.resolveTo();
        play.click();
        audioOption.querySelector<HTMLInputElement>('input[type="range"]')!.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        expect([...boxes].some(box => box.checked)).toBeFalse();

        boxes[1].click(); boxes[0].click(); fixture.detectChanges();
        api.submit.and.returnValue(of({ value: outcome('choice'), replayed: false }));
        click('button[data-submit]');
        expect(api.submit.calls.mostRecent().args[2].response).toEqual({ kind: 'CHOICE', optionIds: [
            'dddddddd-dddd-4ddd-8ddd-ddddddddddd1', 'dddddddd-dddd-4ddd-8ddd-ddddddddddd2'] });
        const marked = [...root.querySelectorAll('app-choice-list .is-correct label')].map(label => label.textContent);
        expect(marked.length).toBe(2);
        expect(marked[0]).toContain('правильный ответ');
    });

    it('gives media options and the video in the question neutral names without any author title', () => {
        startWith('choice');
        const root = page();
        const names = [...root.querySelectorAll('audio, video')].map(media => media.getAttribute('aria-label'));
        expect(names).toContain('Видео в вопросе');
        expect(names).toContain('Аудио, вариант 4');
        expect(root.textContent).not.toContain('Опыт 3');
        expect(root.textContent).not.toContain('Шипение');
        const imageOption = root.querySelectorAll('app-choice-list li')[2];
        expect(imageOption.querySelector('label')?.textContent).toContain('Осадок на дне пробирки');
    });

    it('uses radios for single choice', () => {
        const single = clone(fixtures['choice']);
        single.content.selectionMode = 'SINGLE';
        startWithPresentations([single]);
        const radios = page().querySelectorAll<HTMLInputElement>('app-choice-list input[type="radio"]');
        expect(radios.length).toBe(4);
        expect(page().querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBeTrue();
        radios[2].click(); radios[3].click(); fixture.detectChanges();
        expect([...radios].map(radio => radio.checked)).toEqual([false, false, false, true]);
        expect(page().querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBeFalse();
    });

    it('pairs left then right through the server, shows wrong pairs and submits the full map', () => {
        spyOn(HTMLMediaElement.prototype, 'play').and.resolveTo();
        startWith('match');
        const root = page();
        const left = () => [...root.querySelectorAll<HTMLButtonElement>('button[data-side="left"]')];
        const right = () => [...root.querySelectorAll<HTMLButtonElement>('button[data-side="right"]')];
        expect(left().length).toBe(4);
        // Players are separate from the pairing buttons: using one never pairs or selects anything.
        const player = root.querySelector<HTMLButtonElement>('app-match-board app-native-media-player button[aria-label="Воспроизвести"]')!;
        player.click(); fixture.detectChanges();
        expect(api.checkPair).not.toHaveBeenCalled();
        expect(left().some(button => button.getAttribute('aria-pressed') === 'true')).toBeFalse();
        expect(root.querySelectorAll('app-match-board button button, app-match-board button audio').length).toBe(0);

        right()[0].click(); fixture.detectChanges();
        expect(root.textContent).toContain('Сначала выберите элемент слева');
        expect(api.checkPair).not.toHaveBeenCalled();

        // Left order: bird image, dog text, horse video, cat audio. Right order: cat, horse, dog, bird audio.
        api.checkPair.and.returnValue(of({ correct: false }));
        left()[1].click(); fixture.detectChanges();
        right()[0].click(); fixture.detectChanges();
        expect(api.checkPair).toHaveBeenCalledOnceWith(deck.deckId, sessionId, fixtures['match'].presentationId, fixtures['match'].nonce,
            '1e000000-0000-4000-8000-000000000001', '7e000000-0000-4000-8000-000000000002');
        expect(root.textContent).toContain('Эта пара не подходит');
        expect(root.querySelector('.is-wrong')).not.toBeNull();
        expect(root.querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBeTrue();

        api.checkPair.and.returnValue(of({ correct: true }));
        const mapping: Array<[number, number]> = [[1, 2], [3, 0], [0, 3], [2, 1]];
        for (const [leftIndex, rightIndex] of mapping) {
            // The dog stays selected after its wrong pair; pressing it again would deselect it.
            if (left()[leftIndex].getAttribute('aria-pressed') !== 'true') { left()[leftIndex].click(); fixture.detectChanges(); }
            right()[rightIndex].click(); fixture.detectChanges();
        }
        expect(api.checkPair).toHaveBeenCalledTimes(5);
        expect(left().every(button => button.disabled)).toBeTrue();
        expect(root.textContent).toContain('Пара 1 найдена');

        api.submit.and.returnValue(of({ value: outcome('match'), replayed: false }));
        click('button[data-submit]');
        expect(api.submit.calls.mostRecent().args[2].response).toEqual({ kind: 'MATCH', pairs: [
            { leftId: '1e000000-0000-4000-8000-000000000003', rightId: '7e000000-0000-4000-8000-000000000003' },
            { leftId: '1e000000-0000-4000-8000-000000000001', rightId: '7e000000-0000-4000-8000-000000000001' },
            { leftId: '1e000000-0000-4000-8000-000000000004', rightId: '7e000000-0000-4000-8000-000000000004' },
            { leftId: '1e000000-0000-4000-8000-000000000002', rightId: '7e000000-0000-4000-8000-000000000002' }
        ] });
        // Final feedback names every pair and notes the earlier wrong attempt.
        expect(root.querySelectorAll('.pair-feedback li').length).toBe(4);
        expect(root.textContent).toContain('Все пары найдены');
    });

    it('keeps one media element playing at a time inside the exercise', () => {
        startWith('match');
        const players = [...page().querySelectorAll<HTMLMediaElement>('app-match-board audio, app-match-board video')];
        expect(players.length).toBe(3);
        const pause = players.map(media => spyOn(media, 'pause').and.callThrough());
        spyOnProperty(players[0], 'paused').and.returnValue(false);
        players[1].dispatchEvent(new Event('play'));
        expect(pause[0]).toHaveBeenCalled();
        expect(pause[1]).not.toHaveBeenCalled();
    });

    it('does not let a late pair-check answer reach the next presentation', () => {
        const late = new Subject<{ readonly correct: boolean }>();
        api.checkPair.and.returnValue(late);
        startWithPresentations([fixtures['match'], fixtures['selfCheck']]);
        const root = page();
        root.querySelector<HTMLButtonElement>('button[data-side="left"]')!.click(); fixture.detectChanges();
        root.querySelector<HTMLButtonElement>('button[data-side="right"]')!.click(); fixture.detectChanges();
        expect(api.checkPair).toHaveBeenCalled();
        // The session moves on to the next presentation while the check is still pending.
        fixture.componentInstance.session.update(value => value === null ? null : { ...value, presentations: value.presentations.slice(1) });
        fixture.detectChanges();
        late.next({ correct: true }); fixture.detectChanges();
        expect(root.querySelector('app-match-board')).toBeNull();
        expect(root.textContent).toContain('Назовите органеллы');
    });

    it('moves focus after each rendered Study state change', async () => {
        api.start.and.returnValue(of({ value: sessionOf([fixtures['selfCheck']]), replayed: false }));
        api.submit.and.returnValue(of({ value: outcome('selfCheck'), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent);
        const host = fixture.nativeElement as HTMLElement;
        document.body.appendChild(host);
        try {
            fixture.detectChanges();
            fixture.componentInstance.startScheduled('STANDARD'); fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('[data-answer-control]'));
            (host.querySelector('[data-answer-control]') as HTMLElement).click(); fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('[data-first-rating]'));
            (host.querySelector('[data-first-rating]') as HTMLElement).click(); fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('#feedback-title'));
        } finally { host.remove(); }
    });

    it('retains and replays the exact command after an unknown network outcome', () => {
        startWith('freeResponse');
        api.submit.and.returnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
        type('textarea', 'erinnerung'); click('button[data-submit]');
        const first = api.submit.calls.mostRecent().args[2];
        expect(fixture.nativeElement.textContent).toContain('Повторите отправку того же ответа');

        api.submit.and.returnValue(of({ value: outcome('freeResponse', first), replayed: true }));
        fixture.componentInstance.retryPending();
        expect(api.submit.calls.mostRecent().args[2]).toBe(first);
        expect(recovery.save).toHaveBeenCalledWith({ deckId: deck.deckId, sessionId, pending: first });
        fixture.detectChanges();
        expect(page().textContent).toContain('erinnerung');
    });

    it('asks before leaving with input and not otherwise', () => {
        startWith('freeResponse');
        const confirm = spyOn(window, 'confirm').and.returnValue(false);
        expect(fixture.componentInstance.canLeave()).toBeTrue();
        type('textarea', 'x');
        expect(fixture.componentInstance.canLeave()).toBeFalse();
        expect(confirm).toHaveBeenCalled();
    });

    it('starts a fresh answer surface for the next presentation', () => {
        const second = { ...clone(fixtures['freeResponse']), presentationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb99', ordinal: 2 };
        startWithPresentations([fixtures['freeResponse'], second]);
        api.submit.and.returnValue(of({ value: outcome('freeResponse'), replayed: false }));
        type('textarea', 'old answer'); click('button[data-submit]');
        click('#feedback-title ~ button.primary');
        expect(page().querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('');
        expect(fixture.componentInstance.answerDirty()).toBeFalse();
    });

    it('clears old input when reconciliation has already advanced the presentation', () => {
        const pending: AttemptCommand = { attemptId: '018f1d98-5c10-7abc-8abc-0123456789aa', presentationId: fixtures['choice'].presentationId,
            nonce: fixtures['choice'].nonce, response: { kind: 'CHOICE', optionIds: ['dddddddd-dddd-4ddd-8ddd-ddddddddddd1'] },
            confidence: null, durationMs: 1000 };
        recovery.restore.and.returnValue({ deckId: deck.deckId, sessionId, pending });
        api.read.and.returnValue(of(sessionOf([fixtures['selfCheck']])));
        fixture = TestBed.createComponent(StudySessionPageComponent); fixture.detectChanges();
        expect(fixture.componentInstance.phase()).toBe('answering');
        expect(fixture.componentInstance.pending()).toBeNull();
        expect(fixture.componentInstance.submitted()).toBeNull();
    });

    it('shows unassessed outcomes without blaming the learner', () => {
        startWith('choice');
        const root = page();
        root.querySelector<HTMLInputElement>('app-choice-list input')!.click(); fixture.detectChanges();
        api.submit.and.returnValue(of({ value: { ...outcome('choice'), status: 'NOT_ASSESSED', transition: null,
            feedback: mechanics['feedback']['mediaNotReady'] }, replayed: false }));
        click('button[data-submit]');
        expect(root.textContent).toContain('Запись стала недоступна');
        expect(root.textContent).toContain('Без оценки');
    });

    it('connects completion replay, practice, explainable progress and confirmed restart', () => {
        const terminal: ReadyStudySession = { ...sessionOf([fixtures['selfCheck']]), status: 'COMPLETE', presentations: [] };
        api.start.and.returnValue(of({ value: terminal, replayed: false }));
        api.progress.and.returnValue(of({ asOf: '2026-10-01T10:00:00Z', nextCursor: null, items: [{
            memberKey: '44444444-4444-4444-8444-444444444444', itemRevisionId: '55555555-5555-4555-8555-555555555555',
            title: 'Столица Франции — Париж', state: 'DUE', objectiveCoverage: { enabled: 2, introduced: 1, assessed: 1 },
            lastAssessedAt: '2026-09-30T10:00:00Z', nextDue: '2026-10-01T09:00:00Z'
        }] }));
        api.replaySources.and.returnValue(of({ asOf: '2026-10-01T10:00:00Z', localStudyDate: '2026-10-01',
            items: [{ sessionId: '99999999-9999-4999-8999-999999999995', completedAt: '2026-10-01T09:00:00Z', presentationCount: 1 }] }));
        api.restart.and.returnValue(of({ value: { commandId: ids.commandId, restartedAt: '2026-10-01T10:00:00Z',
            objectiveCount: 2, learningEpochs: [{ objectiveId: '77777777-7777-4777-8777-777777777771', learningEpoch: '1' },
                { objectiveId: '77777777-7777-4777-8777-777777777772', learningEpoch: '1' }] }, replayed: false }));
        spyOn(window, 'confirm').and.returnValue(true);

        createStarted();
        const root = page();
        expect(root.textContent).toContain('Статус обучения');
        expect(root.textContent).toContain('Пора повторить');
        expect(root.querySelector('.material-title')?.textContent).toContain('Столица Франции');
        expect(root.textContent).not.toContain('%');

        fixture.componentInstance.startReplay();
        expect(api.start.calls.mostRecent().args[2]).toEqual({ mode: 'REPLAY', sourceSessionId: '99999999-9999-4999-8999-999999999995' });
        fixture.componentInstance.setIncludeNewPractice(true);
        fixture.componentInstance.setPracticeOrder('WEAKEST_FIRST');
        fixture.componentInstance.startPractice();
        expect(api.start.calls.mostRecent().args[2]).toEqual({ mode: 'PRACTICE', includeNew: true, order: 'WEAKEST_FIRST' });

        fixture.componentInstance.restartMaterial(fixture.componentInstance.progress()[0]);
        expect(window.confirm).toHaveBeenCalled();
        expect(api.restart).toHaveBeenCalledWith(deck.deckId, jasmine.any(String), ['44444444-4444-4444-8444-444444444444']);
    });

    it('renders long unbroken text without horizontal overflow at 320, 390 and 1440', () => {
        const long = 'x'.repeat(300);
        const wide = clone(fixtures['freeResponse']);
        wide.content.prompt[1].text = long;
        startWithPresentations([wide]);
        expectNoOverflow();
        const match = clone(fixtures['match']);
        match.content.left[1].blocks[0].text = long; match.content.right[0].blocks[0].text = long;
        startWithPresentations([match]);
        expectNoOverflow();
    });

    it('offers honest session presets before issuing a scheduled command', () => {
        api.start.and.returnValue(of({ value: sessionOf([fixtures['selfCheck']]), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent); fixture.detectChanges();
        expect(api.start).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).toContain('До 10 заданий');
        expect(fixture.nativeElement.textContent).toContain('Не торопитесь');
        fixture.componentInstance.startScheduled('QUICK');
        expect(api.start.calls.mostRecent().args[2]).toEqual({ mode: 'SCHEDULED', preset: 'QUICK' });
    });

    // ------------------------------------------------------------------------------------------

    function page(): HTMLElement { return fixture.nativeElement as HTMLElement; }

    function expectNoOverflow(): void {
        const root = page(); root.style.display = 'block';
        for (const width of [320, 390, 1440]) {
            root.style.width = `${width}px`; fixture.detectChanges();
            expect(root.scrollWidth).toBeLessThanOrEqual(width + 1);
        }
    }

    function click(selector: string): void {
        const element = page().querySelector<HTMLElement>(selector);
        if (element === null) throw new Error(`Missing ${selector}`);
        element.click(); fixture.detectChanges();
    }

    function type(selector: string, value: string): void {
        const field = page().querySelector<HTMLTextAreaElement | HTMLInputElement>(selector)!;
        field.value = value; field.dispatchEvent(new Event('input')); fixture.detectChanges();
    }

    function ratingButton(label: string): HTMLButtonElement {
        return [...page().querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === label)!;
    }

    function startWith(name: 'selfCheck' | 'freeResponse' | 'cloze' | 'choice' | 'match'): void {
        startWithPresentations([fixtures[name]]);
    }

    function startWithPresentations(presentations: readonly unknown[]): void {
        api.start.and.returnValue(of({ value: sessionOf(presentations), replayed: false }));
        createStarted();
    }

    function createStarted(): void {
        fixture = TestBed.createComponent(StudySessionPageComponent);
        fixture.detectChanges();
        fixture.componentInstance.startScheduled('STANDARD');
        fixture.detectChanges();
    }

    function sessionOf(presentations: readonly unknown[]): ReadyStudySession {
        return { sessionId, deckId: deck.deckId, mode: 'SCHEDULED', status: 'ACTIVE', timezone: 'Europe/Moscow',
            localStudyDate: '2026-10-01', deckRevisionId: '33333333-3333-4333-8333-333333333333',
            exerciseGenerationId: '99999999-9999-4999-8999-999999999993', selectionPolicyVersion: 'deck-due-new-v2',
            budget: { maxPresentations: 20, maxNewObjectives: 5 }, issuedCount: presentations.length,
            reducer: { id: 'mnema-baseline', version: '1', configId: ids.reducerConfigId, configHash: `sha256:${'a'.repeat(64)}` },
            seed: '42', nextCursor: null, expiresAt: '2026-10-02T10:00:00Z',
            presentations: clone(presentations) as StudyPresentation[] };
    }

    function outcome(name: 'selfCheck' | 'freeResponse' | 'cloze' | 'choice' | 'match', command?: AttemptCommand): AttemptOutcome {
        return { attemptId: command?.attemptId ?? ids.commandId, presentationId: fixtures[name].presentationId, mode: 'SCHEDULED',
            status: 'ASSESSED', feedback: clone(mechanics['feedback'][name]), canonicalEffects: true,
            transition: { beforeLevel: 0, afterLevel: 1, nextDue: '2026-10-02T10:00:00Z' } };
    }
});
