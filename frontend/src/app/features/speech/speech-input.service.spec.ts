import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import disclosure from '../../../../../contracts/speech/disclosure.json';
import { RecordedAudio } from '../../shared/audio-recorder';
import { SpeechInputService, TranscribeOutcome, TranscribeRequest } from './speech-input.service';
import { SPEECH_DISCLOSURE_VERSION, consentCovers, parseConsent } from './speech-input.models';
import { speechFailureMessage } from './speech-problem';

const inputId = '0a7e9c1e-5c1b-4d6f-9d44-0f2f6c1f7a11';
const expiresAt = '2026-10-05T10:15:00Z';

describe('SpeechInputService', () => {
    let service: SpeechInputService;
    let http: HttpTestingController;
    let abort: AbortController;
    const audio: RecordedAudio = { blob: new Blob(['audio']), mimeType: 'audio/mp4', durationMs: 4321 };
    const request = (extra: Partial<TranscribeRequest> = {}): TranscribeRequest => ({ audio, purpose: 'COMPOSER', idempotencyKey: 'key-1', ...extra });
    const created = { speechInputId: inputId, state: 'QUEUED', pollAfterMs: 400, expiresAt };
    const view = (extra: Record<string, unknown>) => ({ speechInputId: inputId, state: 'DONE', text: null, seconds: 4, lang: 'ru', garbled: false, errorCode: null, expiresAt, ...extra });
    const consent = (accepted: object | null) => ({ required: { version: 2, processing: 'RU' }, accepted });

    beforeEach(() => {
        vi.useFakeTimers();
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        service = TestBed.inject(SpeechInputService);
        http = TestBed.inject(HttpTestingController);
        abort = new AbortController();
    });
    afterEach(() => { vi.useRealTimers(); http.verify(); });

    const settle = () => vi.advanceTimersByTimeAsync(0);
    const post = (): TestRequest => http.expectOne(candidate => candidate.url === '/api/speech-inputs' && candidate.method === 'POST');
    const poll = (): TestRequest => http.expectOne(`/api/speech-inputs/${inputId}`);

    async function failedWith(status: number, body: object | null, headers: Record<string, string> = {}): Promise<TranscribeOutcome> {
        const outcome = service.transcribe(request(), abort.signal);
        await settle();
        post().flush(body, { status, statusText: 'x', headers });
        return outcome;
    }
    const message = (outcome: TranscribeOutcome): string => outcome.ok === false && outcome.kind === 'error' ? outcome.message : '';

    it('uploads the raw recording with its content type, idempotency key, duration and the query', async () => {
        const outcome = service.transcribe(request({ purpose: 'CAPTURE', deckId: 'deck-1', lang: 'ru' }), abort.signal);
        await settle();
        const call = post();
        expect(call.request.body).toBe(audio.blob);
        expect(call.request.headers.get('Content-Type')).toBe('audio/mp4');
        expect(call.request.headers.get('Idempotency-Key')).toBe('key-1');
        expect(call.request.headers.get('X-Audio-Duration-Ms')).toBe('4321');
        expect(call.request.params.get('purpose')).toBe('CAPTURE');
        expect(call.request.params.get('deckId')).toBe('deck-1');
        expect(call.request.params.get('lang')).toBe('ru');
        call.flush(created, { status: 202, statusText: 'Accepted' });
        await vi.advanceTimersByTimeAsync(400);
        poll().flush(view({ text: ' привет ' }));
        expect(await outcome).toEqual({ ok: true, text: 'привет', garbled: false });
    });

    it('omits lang and deckId when unknown and polls every pollAfterMs until a terminal state', async () => {
        const outcome = service.transcribe(request(), abort.signal);
        await settle();
        const call = post();
        expect(call.request.params.has('deckId')).toBe(false);
        expect(call.request.params.has('lang')).toBe(false);
        call.flush(created, { status: 202, statusText: 'Accepted' });
        await vi.advanceTimersByTimeAsync(399);
        http.expectNone(`/api/speech-inputs/${inputId}`);
        await vi.advanceTimersByTimeAsync(1);
        poll().flush(view({ state: 'TRANSCRIBING' }));
        await vi.advanceTimersByTimeAsync(400);
        poll().flush(view({ state: 'DONE', text: 'готово', garbled: true }));
        expect(await outcome).toEqual({ ok: true, text: 'готово', garbled: true });
        await vi.advanceTimersByTimeAsync(5000);
        http.expectNone(`/api/speech-inputs/${inputId}`);
    });

    it('pauses polling while the tab is hidden and resumes when it is visible again', async () => {
        const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
        const outcome = service.transcribe(request(), abort.signal);
        await settle();
        post().flush(created, { status: 202, statusText: 'Accepted' });
        await vi.advanceTimersByTimeAsync(3000);
        http.expectNone(`/api/speech-inputs/${inputId}`);
        visibility.mockReturnValue('visible');
        document.dispatchEvent(new Event('visibilitychange'));
        await vi.advanceTimersByTimeAsync(400);
        poll().flush(view({ text: 'вернулся' }));
        expect(await outcome).toMatchObject({ ok: true, text: 'вернулся' });
        visibility.mockRestore();
    });

    it('backs off on a failing poll and gives up after a few tries', async () => {
        const outcome = service.transcribe(request(), abort.signal);
        await settle();
        post().flush(created, { status: 202, statusText: 'Accepted' });
        for (let attempt = 0; attempt < 5; attempt++) {
            await vi.advanceTimersByTimeAsync(4000);
            poll().flush(null, { status: 503, statusText: 'x' });
        }
        expect(message(await outcome)).toBe('Не удалось распознать речь — напишите текстом.');
    });

    it('stops at once on a definitive answer such as an expired input', async () => {
        const outcome = service.transcribe(request(), abort.signal);
        await settle();
        post().flush(created, { status: 202, statusText: 'Accepted' });
        await vi.advanceTimersByTimeAsync(400);
        poll().flush({ code: 'RESOURCE_NOT_FOUND' }, { status: 404, statusText: 'x' });
        expect(message(await outcome)).toContain('Не удалось распознать речь');
    });

    it('gives up after the client deadline and takes the input back', async () => {
        const outcome = service.transcribe(request(), abort.signal);
        await settle();
        post().flush(created, { status: 202, statusText: 'Accepted' });
        for (let elapsed = 0; elapsed < 50_000; elapsed += 500) {
            await vi.advanceTimersByTimeAsync(500);
            const open = http.match(candidate => candidate.method === 'GET' && candidate.url === `/api/speech-inputs/${inputId}`);
            open.forEach(candidate => candidate.flush(view({ state: 'TRANSCRIBING' })));
        }
        expect(message(await outcome)).toContain('Не удалось распознать речь');
        http.expectOne(candidate => candidate.method === 'DELETE').flush(null, { status: 204, statusText: 'No Content' });
    });

    it('deletes the input and reports a cancellation when the user cancels while it is transcribed', async () => {
        const outcome = service.transcribe(request(), abort.signal);
        await settle();
        post().flush(created, { status: 202, statusText: 'Accepted' });
        await vi.advanceTimersByTimeAsync(100);
        abort.abort();
        expect(await outcome).toEqual({ ok: false, kind: 'cancelled' });
        const removal = http.expectOne(`/api/speech-inputs/${inputId}`);
        expect(removal.request.method).toBe('DELETE');
        removal.flush(null, { status: 204, statusText: 'No Content' });
    });

    it('maps a missing speech to a calm retry and an empty DONE the same way', async () => {
        let outcome = service.transcribe(request(), abort.signal);
        await settle();
        post().flush(created, { status: 202, statusText: 'Accepted' });
        await vi.advanceTimersByTimeAsync(400);
        poll().flush(view({ state: 'FAILED', errorCode: 'NO_SPEECH' }));
        expect(message(await outcome)).toBe('Не расслышала речь — попробуйте ещё раз.');
        outcome = service.transcribe(request(), abort.signal);
        await settle();
        post().flush(created, { status: 202, statusText: 'Accepted' });
        await vi.advanceTimersByTimeAsync(400);
        poll().flush(view({ state: 'DONE', text: '  ' }));
        expect(message(await outcome)).toBe('Не расслышала речь — попробуйте ещё раз.');
    });

    it('words every FAILED error code', () => {
        expect(speechFailureMessage('UNAVAILABLE')).toBe('Не удалось распознать речь — напишите текстом.');
        expect(speechFailureMessage('NO_SPEECH')).toContain('Не расслышала');
        expect(speechFailureMessage('TOO_LONG')).toContain('минуты');
        expect(speechFailureMessage('UNSUPPORTED_AUDIO')).toContain('разобрать');
        expect(speechFailureMessage(null)).toBe('Не удалось распознать речь — напишите текстом.');
    });

    describe('refusals', () => {
        it('asks for the consent on SPEECH_CONSENT_REQUIRED', async () => {
            expect(await failedWith(409, { code: 'SPEECH_CONSENT_REQUIRED' })).toEqual({ ok: false, kind: 'consent' });
        });

        it('says the capability is unavailable', async () => {
            expect(message(await failedWith(409, { code: 'CAPABILITY_UNAVAILABLE', capability: 'speechToText' }))).toBe('Голосовой ввод сейчас недоступен. Напишите текстом.');
        });

        it('tells the day limit and the month limit apart', async () => {
            expect(message(await failedWith(409, { code: 'USAGE_LIMIT_REACHED', window: 'DAY', renewsAt: '2026-10-06T00:00:00Z' })))
                .toBe('Голосовой ввод на сегодня исчерпан — снова доступен завтра.');
            const month = message(await failedWith(409, { code: 'USAGE_LIMIT_REACHED', window: 'MONTH', renewsAt: '2026-11-01T00:00:00Z' }));
            expect(month).toContain('в этом месяце исчерпан');
            expect(month).toContain('ноября');
            expect(message(await failedWith(409, { code: 'USAGE_LIMIT_REACHED', window: 'MONTH' }))).toContain('в следующем');
        });

        it('turns 429 with Retry-After into minutes', async () => {
            expect(message(await failedWith(429, { code: 'RATE_LIMITED' }, { 'Retry-After': '170' }))).toBe('Слишком много записей подряд, попробуйте через 3 мин.');
            expect(message(await failedWith(429, { code: 'RATE_LIMITED' }, { 'Retry-After': '10' }))).toBe('Слишком много записей подряд, попробуйте через 1 мин.');
            expect(message(await failedWith(429, null))).toBe('Слишком много записей подряд, попробуйте чуть позже.');
        });

        it('words the remaining codes without leaking internals', async () => {
            expect(message(await failedWith(413, { code: 'PAYLOAD_TOO_LARGE' }))).toContain('слишком большая');
            expect(message(await failedWith(409, { code: 'IDEMPOTENCY_CONFLICT' }))).toContain('Запишите ещё раз');
            expect(message(await failedWith(400, { code: 'INVALID_REQUEST' }))).toContain('не удалось отправить');
            expect(message(await failedWith(500, { code: 'INTERNAL', trace: 'at X.java:1' }))).toBe('Не удалось распознать речь — напишите текстом.');
            expect(message(await failedWith(0, null))).toContain('Нет связи');
        });

        it('rejects an answer that is not the contract', async () => {
            const outcome = service.transcribe(request(), abort.signal);
            await settle();
            post().flush({ speechInputId: 'nope' }, { status: 202, statusText: 'Accepted' });
            expect(message(await outcome)).toContain('Нет связи');
        });
    });

    describe('consent', () => {
        it('is satisfied only by the accepted version and region on record', async () => {
            const check = service.checkConsent();
            http.expectOne('/api/speech-consent').flush(consent({ version: 2, processing: 'RU', acceptedAt: expiresAt }));
            expect(await check).toEqual({ ok: true });
            expect(consentCovers(parseConsent(consent({ version: 1, processing: 'RU', acceptedAt: expiresAt })))).toBe(false);
            expect(consentCovers(parseConsent(consent({ version: 2, processing: 'ABROAD', acceptedAt: expiresAt })))).toBe(false);
            expect(consentCovers(parseConsent(consent(null)))).toBe(false);
        });

        it('hands out the terms when none is on record and says calmly when the read fails', async () => {
            let check = service.checkConsent();
            http.expectOne('/api/speech-consent').flush(consent(null));
            expect(await check).toMatchObject({ ok: false, consent: { required: { processing: 'RU' } } });
            check = service.checkConsent();
            http.expectOne('/api/speech-consent').flush(null, { status: 500, statusText: 'x' });
            expect(await check).toMatchObject({ ok: false, consent: null });
        });

        it('records the region the server needs under the version of the disclosure that was shown', async () => {
            const parsed = parseConsent(consent(null));
            const accepted = service.accept(parsed);
            await settle();
            const put = http.expectOne('/api/speech-consent');
            expect(put.request.method).toBe('PUT');
            expect(put.request.body).toEqual({ version: SPEECH_DISCLOSURE_VERSION, processing: 'RU' });
            put.flush(null, { status: 204, statusText: 'No Content' });
            expect(await accepted).toEqual({ ok: true });
        });

        it('records nothing behind the back of the person when the server asks for another version, and says to read the terms again', async () => {
            const accepted = service.accept(parseConsent({ required: { version: 'speech-2099-01', processing: 'ABROAD' }, accepted: null }));
            await settle();
            const put = http.expectOne('/api/speech-consent');
            expect(put.request.body).toEqual({ version: SPEECH_DISCLOSURE_VERSION, processing: 'ABROAD' });
            put.flush({ code: 'SPEECH_CONSENT_OUTDATED' }, { status: 409, statusText: 'x' });
            expect(await accepted).toEqual({ ok: false, message: expect.stringContaining('Условия согласия обновились') });
            http.expectNone(candidate => candidate.method === 'PUT');
        });

        it('records under the disclosure version of the shared contract file, which the backend test holds equal to the version the server requires', () => {
            expect(disclosure.consentVersion).toBe(SPEECH_DISCLOSURE_VERSION);
        });
    });
});
