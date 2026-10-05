import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';

import metadataFixture from '../../../../../contracts/decks/metadata.json';
import { QuietZone } from '../../core/notifications/quiet-zone';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { SpeechInputService } from '../speech/speech-input.service';
import { UsageApiService } from '../usage/usage-api.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { OwnDeck } from '../own-decks/own-deck.models';
import { StudyApiService } from './study-api.service';
import { AttemptCommand, AttemptOutcome, ReadyStudySession, StudyPresentation } from './study.models';
import { StudyRecoveryService } from './study-recovery.service';
import { StudySessionPageComponent } from './study-session-page.component';
import { MEDIA_PLAYBACK_RESOLVER, MediaPlaybackResolver } from './media-playback-resolver';
import { clone, fakePlayback, ids, mechanics } from './study-test-data';
import { spyObj, type SpyObj, lastCall } from '../../../testing/mocks';

describe('StudySessionPageComponent', () => {
    const deck = metadataFixture.detail as unknown as OwnDeck;
    const sessionId = ids.sessionId;
    const fixtures = mechanics['presentations'];
    let api: SpyObj<StudyApiService>;
    let recovery: SpyObj<StudyRecoveryService>;
    let playback: SpyObj<MediaPlaybackResolver>;
    let fixture: ComponentFixture<StudySessionPageComponent>;
    let now = 1000;
    let capabilities: LearningCapabilities;

    beforeEach(() => {
        now = 1000;
        capabilities = CAPABILITIES_UNAVAILABLE;
        const decks = {
            detail: vi.fn().mockName("OwnDecksApiService.detail")
        };
        decks.detail.mockReturnValue(of(deck));
        api = spyObj<StudyApiService>({
            start: vi.fn().mockName("StudyApiService.start"),
            read: vi.fn().mockName("StudyApiService.read"),
            refill: vi.fn().mockName("StudyApiService.refill"),
            submit: vi.fn().mockName("StudyApiService.submit"),
            progress: vi.fn().mockName("StudyApiService.progress"),
            replaySources: vi.fn().mockName("StudyApiService.replaySources"),
            restart: vi.fn().mockName("StudyApiService.restart"),
            revealTranscript: vi.fn().mockName("StudyApiService.revealTranscript"),
            checkPair: vi.fn().mockName("StudyApiService.checkPair"),
            hint: vi.fn().mockName("StudyApiService.hint")
        });
        api.progress.mockReturnValue(of({ asOf: '2026-10-01T10:00:00Z', items: [], nextCursor: null }));
        api.replaySources.mockReturnValue(of({ asOf: '2026-10-01T10:00:00Z', localStudyDate: '2026-10-01', items: [] }));
        recovery = spyObj<StudyRecoveryService>({
            restore: vi.fn().mockName("StudyRecoveryService.restore"),
            save: vi.fn().mockName("StudyRecoveryService.save"),
            clear: vi.fn().mockName("StudyRecoveryService.clear"),
            now: vi.fn().mockName("StudyRecoveryService.now")
        });
        recovery.restore.mockReturnValue(null);
        recovery.now.mockImplementation(() => now);
        playback = {
            resolve: vi.fn().mockName("MediaPlaybackResolver.resolve")
        };
        playback.resolve.mockImplementation(fakePlayback);
        const router = {
            navigate: vi.fn().mockName("Router.navigate")
        };
        router.navigate.mockResolvedValue(true);
        TestBed.configureTestingModule({ providers: [
                { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ deckId: deck.deckId }) } } },
                { provide: Router, useValue: router }, { provide: OwnDecksApiService, useValue: decks },
                { provide: StudyApiService, useValue: api }, { provide: StudyRecoveryService, useValue: recovery },
                { provide: MEDIA_PLAYBACK_RESOLVER, useValue: playback },
                { provide: CapabilitiesApiService, useValue: { read: () => of(capabilities) } },
                { provide: UsageApiService, useValue: { load: () => throwError(() => new Error('no usage')) } },
                { provide: SpeechInputService, useValue: { checkConsent: vi.fn().mockResolvedValue({ ok: true }) } }
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

        api.submit.mockReturnValue(of({ value: outcome('selfCheck'), replayed: false }));
        ratingButton('Вспомнил частично').click();
        fixture.detectChanges();
        const command = lastCall(api.submit)[2];
        expect(command.response).toEqual({ kind: 'SELF_CHECK', rating: 'PARTIAL' });
        expect(Object.keys(command)).not.toContain('hintsUsed');
        expect(root.querySelector('#feedback-title')).not.toBeNull();
    });

    it('offers «Ответить голосом» for a TEXT_OR_SPEECH answer and submits a typed answer as TYPED', () => {
        capabilities = { ...CAPABILITIES_UNAVAILABLE, speechToText: { available: true, reason: null } };
        const speechPresentation = clone(fixtures['freeResponse']);
        speechPresentation.content.responseInput = 'TEXT_OR_SPEECH';
        startWithPresentations([speechPresentation]);
        expect(page().querySelector('app-mic-button')).not.toBeNull();
        expect(page().textContent).toContain('Ответить голосом');
        api.submit.mockReturnValue(of({ value: outcome('freeResponse'), replayed: false }));
        type('textarea', 'набрано руками');
        click('button[data-submit]');
        expect(lastCall(api.submit)[2].response).toEqual({ kind: 'TEXT', text: 'набрано руками', answerSource: 'TYPED' });
    });

    it('marks a quiet zone while a task is open and releases it at the feedback pause and on leaving', () => {
        const quiet = TestBed.inject(QuietZone);
        startWith('selfCheck');
        fixture.detectChanges();
        expect(quiet.active()).toBe(true);
        click('[data-answer-control]');
        expect(quiet.active()).toBe(true);

        api.submit.mockReturnValue(of({ value: outcome('selfCheck'), replayed: false }));
        ratingButton('Вспомнил частично').click();
        fixture.detectChanges();
        fixture.detectChanges();
        expect(page().querySelector('#feedback-title')).not.toBeNull();
        expect(quiet.active()).toBe(false);

        // Leaving while a task is open must never leave the rest of the app muted.
        startWith('freeResponse');
        fixture.detectChanges();
        expect(quiet.active()).toBe(true);
        fixture.destroy();
        expect(quiet.active()).toBe(false);
    });

    it('submits the exact free-response text and never starts the voice path without a microphone button', () => {
        const getUserMedia = vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockRejectedValue(new Error('must not be called'));
        const recorder = vi.fn().mockName('MediaRecorder');
        const speech = vi.fn().mockName('SpeechRecognition');
        const globals = window as unknown as Record<string, unknown>;
        const saved = { MediaRecorder: globals['MediaRecorder'], SpeechRecognition: globals['SpeechRecognition'],
            webkitSpeechRecognition: globals['webkitSpeechRecognition'] };
        globals['MediaRecorder'] = recorder;
        globals['SpeechRecognition'] = speech;
        globals['webkitSpeechRecognition'] = speech;
        const xhr = vi.spyOn(XMLHttpRequest.prototype, 'open');
        const fetchSpy = vi.spyOn(window, 'fetch');
        try {
            startWith('freeResponse');
            const root = page();
            // A TEXT exercise (and an unavailable capability) offers no microphone at all: nothing to press, nothing to explain.
            expect([...root.querySelectorAll('button')].some(button => button.textContent?.includes('голос'))).toBe(false);
            expect(root.querySelector('app-mic-button')).toBeNull();
            fixture.detectChanges();
            expect(getUserMedia).not.toHaveBeenCalled();
            expect(recorder).not.toHaveBeenCalled();
            expect(speech).not.toHaveBeenCalled();
            expect(xhr).not.toHaveBeenCalled();
            expect(fetchSpy).not.toHaveBeenCalled();
            expect(api.submit).not.toHaveBeenCalled();
            expect(api.hint).not.toHaveBeenCalled();
            expect(api.revealTranscript).not.toHaveBeenCalled();

            api.submit.mockReturnValue(of({ value: outcome('freeResponse'), replayed: false }));
            type('textarea', ' erinnerung ');
            now = 5200;
            click('button[data-submit]');
            const command = lastCall(api.submit)[2];
            expect(command.response).toEqual({ kind: 'TEXT', text: ' erinnerung ' });
            expect(command.durationMs).toBe(4200);
            expect(root.textContent).toContain('Ваш ответ');
            expect(root.textContent).toContain('Erinnerung');
        }
        finally {
            for (const key of Object.keys(saved) as (keyof typeof saved)[]) {
                if (saved[key] === undefined)
                    delete globals[key];
                else
                    globals[key] = saved[key];
            }
        }
    });

    it('offers a transcript only when a block advertises one and shows it after the explicit reveal', () => {
        startWith('freeResponse');
        const root = page();
        expect(root.textContent).not.toContain('Erinnerung');
        expect(root.textContent).toContain('слабее влияет на прогресс');
        api.revealTranscript.mockReturnValue(of({ presentationId: fixtures['freeResponse'].presentationId,
            content: { type: 'FREE_RESPONSE', content: mechanics['transcriptRevealResponse'].content } }));
        click('.transcript-offer button');
        expect(api.revealTranscript).toHaveBeenCalledWith(deck.deckId, sessionId, fixtures['freeResponse'].presentationId, fixtures['freeResponse'].nonce, 'FREE_RESPONSE');
        expect(root.textContent).toContain('Транскрипт: Erinnerung');
        expect(root.querySelector('.transcript-offer')).toBeNull();
        expect(fixture.componentInstance.current()?.transcriptRevealed).toBe(true);
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

        api.hint.mockReturnValue(of(mechanics['hintResponse']));
        hintButtons[0].click();
        fixture.detectChanges();
        expect(api.hint).toHaveBeenCalledTimes(1);
        expect(api.hint).toHaveBeenCalledWith(deck.deckId, sessionId, fixtures['cloze'].presentationId, fixtures['cloze'].nonce, mechanics['hintCommand'].blankId);
        expect(root.querySelectorAll('.cloze-letter').length).toBe(2);
        expect(root.querySelector('.cloze-hint')).toBeNull();
        expect(fixture.componentInstance.current()?.hints.map(hint => hint.blankId))
            .toContain(mechanics['hintCommand'].blankId);

        api.submit.mockReturnValue(of({ value: outcome('cloze'), replayed: false }));
        const values = ['map', 'toSet', 'Map'];
        inputs.forEach((input, index) => { input.value = values[index]; input.dispatchEvent(new Event('input')); });
        click('button[data-submit]');
        expect(lastCall(api.submit)[2].response).toEqual({ kind: 'CLOZE', blanks: [
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
        first.value = 'ma';
        first.dispatchEvent(new Event('input'));
        api.hint.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
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
        vi.spyOn(media, 'play').mockResolvedValue();
        play.click();
        audioOption.querySelector<HTMLInputElement>('input[type="range"]')!.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        expect([...boxes].some(box => box.checked)).toBe(false);

        boxes[1].click();
        boxes[0].click();
        fixture.detectChanges();
        api.submit.mockReturnValue(of({ value: outcome('choice'), replayed: false }));
        click('button[data-submit]');
        expect(lastCall(api.submit)[2].response).toEqual({ kind: 'CHOICE', optionIds: [
                'dddddddd-dddd-4ddd-8ddd-ddddddddddd1', 'dddddddd-dddd-4ddd-8ddd-ddddddddddd2'
            ] });
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
        expect(page().querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBe(true);
        radios[2].click();
        radios[3].click();
        fixture.detectChanges();
        expect([...radios].map(radio => radio.checked)).toEqual([false, false, false, true]);
        expect(page().querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBe(false);
    });

    it('pairs left then right through the server, shows wrong pairs and submits the full map', () => {
        vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
        startWith('match');
        const root = page();
        const left = () => [...root.querySelectorAll<HTMLButtonElement>('button[data-side="left"]')];
        const right = () => [...root.querySelectorAll<HTMLButtonElement>('button[data-side="right"]')];
        expect(left().length).toBe(4);
        // Players are separate from the pairing buttons: using one never pairs or selects anything.
        const player = root.querySelector<HTMLButtonElement>('app-match-board app-native-media-player button[aria-label="Воспроизвести"]')!;
        player.click();
        fixture.detectChanges();
        expect(api.checkPair).not.toHaveBeenCalled();
        expect(left().some(button => button.getAttribute('aria-pressed') === 'true')).toBe(false);
        expect(root.querySelectorAll('app-match-board button button, app-match-board button audio').length).toBe(0);

        right()[0].click();
        fixture.detectChanges();
        expect(root.textContent).toContain('Сначала выберите элемент слева');
        expect(api.checkPair).not.toHaveBeenCalled();

        // Left order: bird image, dog text, horse video, cat audio. Right order: cat, horse, dog, bird audio.
        api.checkPair.mockReturnValue(of({ correct: false }));
        left()[1].click();
        fixture.detectChanges();
        right()[0].click();
        fixture.detectChanges();
        expect(api.checkPair).toHaveBeenCalledTimes(1);
        expect(api.checkPair).toHaveBeenCalledWith(deck.deckId, sessionId, fixtures['match'].presentationId, fixtures['match'].nonce, '1e000000-0000-4000-8000-000000000001', '7e000000-0000-4000-8000-000000000002');
        expect(root.textContent).toContain('Эта пара не подходит');
        expect(root.querySelector('.is-wrong')).not.toBeNull();
        expect(root.querySelector<HTMLButtonElement>('button[data-submit]')?.disabled).toBe(true);

        api.checkPair.mockReturnValue(of({ correct: true }));
        const mapping: Array<[
            number,
            number
        ]> = [[1, 2], [3, 0], [0, 3], [2, 1]];
        for (const [leftIndex, rightIndex] of mapping) {
            // The dog stays selected after its wrong pair; pressing it again would deselect it.
            if (left()[leftIndex].getAttribute('aria-pressed') !== 'true') {
                left()[leftIndex].click();
                fixture.detectChanges();
            }
            right()[rightIndex].click();
            fixture.detectChanges();
        }
        expect(api.checkPair).toHaveBeenCalledTimes(5);
        expect(left().every(button => button.disabled)).toBe(true);
        expect(root.textContent).toContain('Пара 1 найдена');

        api.submit.mockReturnValue(of({ value: outcome('match'), replayed: false }));
        click('button[data-submit]');
        expect(lastCall(api.submit)[2].response).toEqual({ kind: 'MATCH', pairs: [
                { leftId: '1e000000-0000-4000-8000-000000000003', rightId: '7e000000-0000-4000-8000-000000000003' },
                { leftId: '1e000000-0000-4000-8000-000000000001', rightId: '7e000000-0000-4000-8000-000000000001' },
                { leftId: '1e000000-0000-4000-8000-000000000004', rightId: '7e000000-0000-4000-8000-000000000004' },
                { leftId: '1e000000-0000-4000-8000-000000000002', rightId: '7e000000-0000-4000-8000-000000000002' }
            ] });
        // Final feedback names every pair and notes the earlier wrong attempt.
        expect(root.querySelectorAll('.pair-feedback li').length).toBe(4);
        expect(root.textContent).toContain('Все пары найдены');
    });

    it('restores a sequence with the move controls only: the issued order is the start, media never moves an item, one explicit submit', () => {
        vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
        startWith('order');
        const root = page();
        const rows = () => [...root.querySelectorAll('app-order-board li.order-item')].map(row => row.getAttribute('data-item-id')!);
        const issued = fixtures['order'].content.items.map((entry: {
            itemId: string;
        }) => entry.itemId);
        expect(rows()).toEqual(issued);
        expect(root.querySelector('h2')?.textContent).toBe('Восстановите порядок');
        expect(root.querySelector('app-order-board .order-content app-native-media-player')).toBeNull(); // the issued set has an image, no player
        root.querySelectorAll<HTMLElement>('app-order-board .order-content').forEach(content => content.click());
        fixture.detectChanges();
        expect(rows()).toEqual(issued);
        expect(api.submit).not.toHaveBeenCalled();

        const down = (itemId: string) => root.querySelector<HTMLButtonElement>(`[data-item-id="${itemId}"] [data-move="down"]`)!.click();
        // Move the first issued item ("важно,") to the end with the arrow buttons.
        for (let step = 0; step < 5; step++) {
            down(issued[0]);
            fixture.detectChanges();
        }
        expect(rows()).toEqual([...issued.slice(1), issued[0]]);
        expect(root.querySelector('.order-status')?.textContent).toContain('перемещён на позицию 6 из 6');
        api.submit.mockReturnValue(of({ value: outcome('order'), replayed: false }));
        click('button[data-submit]');
        const command = lastCall(api.submit)[2];
        expect(command.response).toEqual({ kind: 'ORDER', sequence: [...issued.slice(1), issued[0]] });
        expect(Object.keys(command)).toEqual(['attemptId', 'presentationId', 'nonce', 'response', 'confidence', 'durationMs']);
        expect(root.querySelector('#feedback-title')?.textContent).toBe('Нужно повторить');
        expect(root.querySelectorAll('.positions li').length).toBe(6);
        expect(root.querySelector('.correct-sequence')?.textContent).toContain('for (int i = 0; i < n; i++) {');
    });

    it('assigns every item to a group by select-then-group, keeps changing possible until submit and shows the right group', () => {
        vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue();
        startWith('categorize');
        const root = page();
        const item = (n: number) => `9a000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
        const group = (n: number) => `ca000000-0000-4000-8000-${n.toString().padStart(12, '0')}`;
        const put = (itemId: string, groupId: string) => {
            root.querySelector<HTMLButtonElement>(`[data-item-id="${itemId}"] [data-select]`)!.click();
            fixture.detectChanges();
            root.querySelector<HTMLButtonElement>(`[data-category="${groupId}"] [data-place]`)!.click();
            fixture.detectChanges();
        };
        // A player is a sibling of the selection button: pressing it neither selects nor assigns.
        root.querySelector<HTMLButtonElement>(`[data-item-id="${item(4)}"] app-native-media-player button[aria-label="Воспроизвести"]`)!.click();
        fixture.detectChanges();
        expect(root.querySelectorAll('[data-select][aria-pressed="true"]').length).toBe(0);
        expect(root.querySelector<HTMLButtonElement>('button[data-submit]')!.disabled).toBe(true);

        put(item(1), group(1));
        put(item(2), group(2));
        put(item(3), group(3));
        put(item(4), group(2));
        put(item(3), group(1)); // changed their mind: "река" belongs with the nouns
        expect(root.querySelector(`[data-category="${group(1)}"]`)!.textContent).toContain('Элементов: 2');
        api.submit.mockReturnValue(of({ value: outcome('categorize'), replayed: false }));
        click('button[data-submit]');
        const command = lastCall(api.submit)[2];
        expect(command.response).toEqual({ kind: 'CATEGORIZE', assignments: [
                { itemId: item(3), categoryId: group(1) }, { itemId: item(4), categoryId: group(2) },
                { itemId: item(1), categoryId: group(1) }, { itemId: item(2), categoryId: group(2) }
            ] });
        expect(root.querySelector('#feedback-title')?.textContent).toBe('Частично');
        expect(root.querySelectorAll('.pair-feedback li').length).toBe(4);
        expect(root.querySelector('.pair-feedback li.is-wrong')?.textContent).toContain('Правильная группа: Существительное');
    });

    it('keeps one media element playing at a time inside the exercise', () => {
        startWith('match');
        const players = [...page().querySelectorAll<HTMLMediaElement>('app-match-board audio, app-match-board video')];
        expect(players.length).toBe(3);
        const pause = players.map(media => vi.spyOn(media, 'pause'));
        vi.spyOn(players[0], 'paused', 'get').mockReturnValue(false);
        players[1].dispatchEvent(new Event('play'));
        expect(pause[0]).toHaveBeenCalled();
        expect(pause[1]).not.toHaveBeenCalled();
    });

    it('does not let a late pair-check answer reach the next presentation', () => {
        const late = new Subject<{
            readonly correct: boolean;
        }>();
        api.checkPair.mockReturnValue(late);
        startWithPresentations([fixtures['match'], fixtures['selfCheck']]);
        const root = page();
        root.querySelector<HTMLButtonElement>('button[data-side="left"]')!.click();
        fixture.detectChanges();
        root.querySelector<HTMLButtonElement>('button[data-side="right"]')!.click();
        fixture.detectChanges();
        expect(api.checkPair).toHaveBeenCalled();
        // The session moves on to the next presentation while the check is still pending.
        fixture.componentInstance.session.update(value => value === null ? null : { ...value, presentations: value.presentations.slice(1) });
        fixture.detectChanges();
        late.next({ correct: true });
        fixture.detectChanges();
        expect(root.querySelector('app-match-board')).toBeNull();
        expect(root.textContent).toContain('Назовите органеллы');
    });

    it('moves focus after each rendered Study state change', async () => {
        api.start.mockReturnValue(of({ value: sessionOf([fixtures['selfCheck']]), replayed: false }));
        api.submit.mockReturnValue(of({ value: outcome('selfCheck'), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent);
        const host = fixture.nativeElement as HTMLElement;
        document.body.appendChild(host);
        try {
            fixture.detectChanges();
            fixture.componentInstance.startScheduled('STANDARD');
            fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('[data-answer-control]'));
            (host.querySelector('[data-answer-control]') as HTMLElement).click();
            fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('[data-first-rating]'));
            (host.querySelector('[data-first-rating]') as HTMLElement).click();
            fixture.detectChanges();
            await fixture.whenStable();
            expect(document.activeElement).toBe(host.querySelector('#feedback-title'));
        }
        finally {
            host.remove();
        }
    });

    it('retains and replays the exact command after an unknown network outcome', () => {
        startWith('freeResponse');
        api.submit.mockReturnValue(throwError(() => new HttpErrorResponse({ status: 0 })));
        type('textarea', 'erinnerung');
        click('button[data-submit]');
        const first = lastCall(api.submit)[2];
        expect(fixture.nativeElement.textContent).toContain('Повторите отправку того же ответа');

        api.submit.mockReturnValue(of({ value: outcome('freeResponse', first), replayed: true }));
        fixture.componentInstance.retryPending();
        expect(lastCall(api.submit)[2]).toBe(first);
        expect(recovery.save).toHaveBeenCalledWith({ deckId: deck.deckId, sessionId, pending: first });
        fixture.detectChanges();
        expect(page().textContent).toContain('erinnerung');
    });

    it('asks before leaving with input and not otherwise', () => {
        startWith('freeResponse');
        const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
        expect(fixture.componentInstance.canLeave()).toBe(true);
        type('textarea', 'x');
        expect(fixture.componentInstance.canLeave()).toBe(false);
        expect(confirm).toHaveBeenCalled();
    });

    it('starts a fresh answer surface for the next presentation', () => {
        const second = { ...clone(fixtures['freeResponse']), presentationId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb99', ordinal: 2 };
        startWithPresentations([fixtures['freeResponse'], second]);
        api.submit.mockReturnValue(of({ value: outcome('freeResponse'), replayed: false }));
        type('textarea', 'old answer');
        click('button[data-submit]');
        click('#feedback-title ~ button.primary');
        expect(page().querySelector<HTMLTextAreaElement>('textarea')?.value).toBe('');
        expect(fixture.componentInstance.answerDirty()).toBe(false);
    });

    it('clears old input when reconciliation has already advanced the presentation', () => {
        const pending: AttemptCommand = { attemptId: '018f1d98-5c10-7abc-8abc-0123456789aa', presentationId: fixtures['choice'].presentationId,
            nonce: fixtures['choice'].nonce, response: { kind: 'CHOICE', optionIds: ['dddddddd-dddd-4ddd-8ddd-ddddddddddd1'] },
            confidence: null, durationMs: 1000 };
        recovery.restore.mockReturnValue({ deckId: deck.deckId, sessionId, pending });
        api.read.mockReturnValue(of(sessionOf([fixtures['selfCheck']])));
        fixture = TestBed.createComponent(StudySessionPageComponent);
        fixture.detectChanges();
        expect(fixture.componentInstance.phase()).toBe('answering');
        expect(fixture.componentInstance.pending()).toBeNull();
        expect(fixture.componentInstance.submitted()).toBeNull();
    });

    it('shows unassessed outcomes without blaming the learner', () => {
        startWith('choice');
        const root = page();
        root.querySelector<HTMLInputElement>('app-choice-list input')!.click();
        fixture.detectChanges();
        api.submit.mockReturnValue(of({ value: { ...outcome('choice'), status: 'NOT_ASSESSED', transition: null,
                feedback: mechanics['feedback']['mediaNotReady'] }, replayed: false }));
        click('button[data-submit]');
        expect(root.textContent).toContain('Запись стала недоступна');
        expect(root.textContent).toContain('Без оценки');
    });

    it('connects completion replay, practice, explainable progress and confirmed restart', () => {
        const terminal: ReadyStudySession = { ...sessionOf([fixtures['selfCheck']]), status: 'COMPLETE', presentations: [] };
        api.start.mockReturnValue(of({ value: terminal, replayed: false }));
        api.progress.mockReturnValue(of({ asOf: '2026-10-01T10:00:00Z', nextCursor: null, items: [{
                    memberKey: '44444444-4444-4444-8444-444444444444', itemRevisionId: '55555555-5555-4555-8555-555555555555',
                    title: 'Столица Франции — Париж', state: 'DUE', objectiveCoverage: { enabled: 2, introduced: 1, assessed: 1 },
                    lastAssessedAt: '2026-09-30T10:00:00Z', nextDue: '2026-10-01T09:00:00Z'
                }] }));
        api.replaySources.mockReturnValue(of({ asOf: '2026-10-01T10:00:00Z', localStudyDate: '2026-10-01',
            items: [{ sessionId: '99999999-9999-4999-8999-999999999995', completedAt: '2026-10-01T09:00:00Z', presentationCount: 1 }] }));
        api.restart.mockReturnValue(of({ value: { commandId: ids.commandId, restartedAt: '2026-10-01T10:00:00Z',
                objectiveCount: 2, learningEpochs: [{ objectiveId: '77777777-7777-4777-8777-777777777771', learningEpoch: '1' },
                    { objectiveId: '77777777-7777-4777-8777-777777777772', learningEpoch: '1' }] }, replayed: false }));
        vi.spyOn(window, 'confirm').mockReturnValue(true);

        createStarted();
        const root = page();
        expect(root.textContent).toContain('Статус обучения');
        expect(root.textContent).toContain('Пора повторить');
        expect(root.querySelector('.material-title')?.textContent).toContain('Столица Франции');
        expect(root.textContent).not.toContain('%');

        fixture.componentInstance.startReplay();
        expect(lastCall(api.start)[2]).toEqual({ mode: 'REPLAY', sourceSessionId: '99999999-9999-4999-8999-999999999995' });
        fixture.componentInstance.setIncludeNewPractice(true);
        fixture.componentInstance.setPracticeOrder('WEAKEST_FIRST');
        fixture.componentInstance.startPractice();
        expect(lastCall(api.start)[2]).toEqual({ mode: 'PRACTICE', includeNew: true, order: 'WEAKEST_FIRST' });

        fixture.componentInstance.restartMaterial(fixture.componentInstance.progress()[0]);
        expect(window.confirm).toHaveBeenCalled();
        expect(api.restart).toHaveBeenCalledWith(deck.deckId, expect.any(String), ['44444444-4444-4444-8444-444444444444']);
    });

    // Horizontal overflow at 320/390/1440 is a geometry assertion jsdom cannot make; the browser harness owns it
    // (scripts/browser-identity, scenarios mechanics-study-*-390 / *_study_390_overflow).
    it('renders long unbroken text in free-response and match presentations', () => {
        const long = 'x'.repeat(300);
        const wide = clone(fixtures['freeResponse']);
        wide.content.prompt[1].text = long;
        startWithPresentations([wide]);
        expect(page().textContent).toContain(long);
        const match = clone(fixtures['match']);
        match.content.left[1].blocks[0].text = long;
        match.content.right[0].blocks[0].text = long;
        startWithPresentations([match]);
        expect(page().textContent).toContain(long);
    });

    it('marks a new generated exercise with the text «Новое» on its card, and shows nothing for the others (AI-13)', () => {
        startWithPresentations([{ ...fixtures['selfCheck'], isNew: true }]);
        const badge = fixture.nativeElement.querySelector('.study-card app-new-badge');
        expect(badge?.textContent).toBe('Новое');
        expect(fixture.nativeElement.querySelector('.study-card .eyebrow')?.textContent).toContain('Вспомните без подсказки');
        startWithPresentations([{ ...fixtures['selfCheck'], isNew: false }]);
        expect(fixture.nativeElement.querySelector('.study-card app-new-badge')).toBeNull();
    });

    it('offers honest session presets before issuing a scheduled command', () => {
        api.start.mockReturnValue(of({ value: sessionOf([fixtures['selfCheck']]), replayed: false }));
        fixture = TestBed.createComponent(StudySessionPageComponent);
        fixture.detectChanges();
        expect(api.start).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).toContain('До 10 заданий');
        expect(fixture.nativeElement.textContent).toContain('Не торопитесь');
        fixture.componentInstance.startScheduled('QUICK');
        expect(lastCall(api.start)[2]).toEqual({ mode: 'SCHEDULED', preset: 'QUICK' });
    });

    // ------------------------------------------------------------------------------------------

    function page(): HTMLElement { return fixture.nativeElement as HTMLElement; }

    function click(selector: string): void {
        const element = page().querySelector<HTMLElement>(selector);
        if (element === null)
            throw new Error(`Missing ${selector}`);
        element.click();
        fixture.detectChanges();
    }

    function type(selector: string, value: string): void {
        const field = page().querySelector<HTMLTextAreaElement | HTMLInputElement>(selector)!;
        field.value = value;
        field.dispatchEvent(new Event('input'));
        fixture.detectChanges();
    }

    function ratingButton(label: string): HTMLButtonElement {
        return [...page().querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === label)!;
    }

    function startWith(name: 'selfCheck' | 'freeResponse' | 'cloze' | 'choice' | 'match' | 'order' | 'categorize'): void {
        startWithPresentations([fixtures[name]]);
    }

    function startWithPresentations(presentations: readonly unknown[]): void {
        api.start.mockReturnValue(of({ value: sessionOf(presentations), replayed: false }));
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

    function outcome(name: 'selfCheck' | 'freeResponse' | 'cloze' | 'choice' | 'match' | 'order' | 'categorize', command?: AttemptCommand): AttemptOutcome {
        return { attemptId: command?.attemptId ?? ids.commandId, presentationId: fixtures[name].presentationId, mode: 'SCHEDULED',
            status: 'ASSESSED', feedback: clone(mechanics['feedback'][name]), canonicalEffects: true,
            transition: { beforeLevel: 0, afterLevel: 1, nextDue: '2026-10-02T10:00:00Z' }, disputed: false };
    }
});
