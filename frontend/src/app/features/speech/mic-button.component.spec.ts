import { Component, ElementRef, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { SPEECH_MIME_PREFERENCE } from '../../shared/audio-recorder';
import { UsageApiService } from '../usage/usage-api.service';
import { MicButtonComponent } from './mic-button.component';
import { SpeechConsent } from './speech-input.models';
import { SpeechInputService, TranscribeOutcome } from './speech-input.service';

class FakeRecorder {
    static instances: FakeRecorder[] = [];
    static isTypeSupported = (type: string): boolean => SPEECH_MIME_PREFERENCE.includes(type);
    state: 'inactive' | 'recording' = 'inactive';
    readonly mimeType: string;
    ondataavailable: ((event: { data: Blob }) => void) | null = null;
    onstop: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(readonly stream: unknown, options: { mimeType: string }) { this.mimeType = options.mimeType; FakeRecorder.instances.push(this); }
    start(): void { this.state = 'recording'; }
    stop(): void { this.state = 'inactive'; this.ondataavailable?.({ data: new Blob(['audio']) }); this.onstop?.(); }
}

@Component({
    selector: 'app-mic-host',
    imports: [MicButtonComponent],
    template: `<textarea #field aria-label="Поле"></textarea>
        <app-mic-button purpose="COMPOSER" [field]="target" [label]="label" (inserted)="inserted.push($event.text)" />`
})
class HostComponent {
    readonly field = viewChild.required<ElementRef<HTMLTextAreaElement>>('field');
    readonly target = (): HTMLTextAreaElement => this.field().nativeElement;
    label = 'Начать запись';
    readonly inserted: string[] = [];
}

describe('MicButtonComponent', () => {
    const consent = (accepted: boolean): SpeechConsent => ({ required: { version: 2, processing: 'RU' }, accepted: accepted ? { version: 2, processing: 'RU', acceptedAt: '2026-10-05T10:00:00Z' } : null });
    const globals = globalThis as unknown as { MediaRecorder: unknown };
    let original: unknown;
    let speech: { checkConsent: ReturnType<typeof vi.fn>; accept: ReturnType<typeof vi.fn>; transcribe: ReturnType<typeof vi.fn> };
    let usage: { load: ReturnType<typeof vi.fn> };
    let fixture: ComponentFixture<HostComponent>;
    let tracks: { stop: ReturnType<typeof vi.fn>; onended: (() => void) | null }[];

    beforeEach(() => {
        original = globals.MediaRecorder;
        globals.MediaRecorder = FakeRecorder;
        FakeRecorder.instances = [];
        tracks = [{ stop: vi.fn(), onended: null }];
        const stream = { getTracks: () => tracks, getAudioTracks: () => tracks } as unknown as MediaStream;
        vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
        HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) { this.setAttribute('open', ''); };
        HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) { this.removeAttribute('open'); };
        speech = {
            checkConsent: vi.fn().mockResolvedValue({ ok: true }),
            accept: vi.fn().mockResolvedValue({ ok: true }),
            transcribe: vi.fn().mockResolvedValue({ ok: true, text: 'привет мир', garbled: false } satisfies TranscribeOutcome)
        };
        usage = { load: vi.fn().mockReturnValue(throwError(() => new Error('no usage'))) };
        TestBed.configureTestingModule({ providers: [{ provide: SpeechInputService, useValue: speech }, { provide: UsageApiService, useValue: usage }] });
        fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
    });
    afterEach(() => { globals.MediaRecorder = original; vi.restoreAllMocks(); });

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
    const field = (): HTMLTextAreaElement => root().querySelector('textarea')!;
    const buttons = (): HTMLButtonElement[] => [...root().querySelectorAll<HTMLButtonElement>('.mic-button, .mic-quiet')];
    const button = (name: string): HTMLButtonElement => buttons().find(candidate => candidate.textContent?.includes(name))!;
    const status = (): string => root().querySelector('.mic-status')!.textContent!.trim();
    async function flush(): Promise<void> { for (let i = 0; i < 6; i++) { await Promise.resolve(); await fixture.whenStable(); fixture.detectChanges(); } }

    async function record(): Promise<void> {
        button('Начать запись').click();
        await flush();
        button('Остановить запись').click();
        await flush();
    }

    it('starts idle with a named button and one polite status region', () => {
        expect(button('Начать запись')).toBeTruthy();
        expect(root().querySelector('.mic-status')!.getAttribute('role')).toBe('status');
        expect(status()).toBe('');
        expect(root().querySelectorAll('[role="status"]').length).toBe(1);
    });

    it('records, shows the stop action, recognises and inserts the text at the caret without sending anything', async () => {
        field().value = 'Начало конец';
        field().setSelectionRange(7, 7);
        button('Начать запись').click();
        await flush();
        expect(button('Остановить запись')).toBeTruthy();
        expect(button('Отмена')).toBeTruthy();
        expect(status()).toContain('Запись идёт');
        expect(root().querySelector('.mic-clock')!.textContent).toContain('0:00');
        button('Остановить запись').click();
        await flush();
        expect(speech.transcribe).toHaveBeenCalledTimes(1);
        const [request] = speech.transcribe.mock.calls[0];
        expect(request).toMatchObject({ purpose: 'COMPOSER', audio: { mimeType: 'audio/mp4' } });
        expect(request.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/u);
        expect(field().value).toBe('Начало привет мир конец');
        expect(document.activeElement).toBe(field());
        expect(field().selectionStart).toBe('Начало привет мир'.length);
        expect(fixture.componentInstance.inserted).toEqual(['привет мир']);
        expect(status()).toContain('Проверьте его и отправьте сами');
        expect(tracks[0].stop).toHaveBeenCalled();
    });

    it('keeps keyboard focus on the one button through recording and recognising (found by the browser harness: it was lost to the body)', async () => {
        const main = root().querySelector<HTMLButtonElement>('.mic-row .mic-button')!;
        main.focus();
        main.click();
        await flush();
        expect(root().querySelector('.mic-row .mic-button')).toBe(main);
        expect(main.textContent).toContain('Остановить запись');
        expect(document.activeElement).toBe(main);
        let finish: (outcome: TranscribeOutcome) => void = () => undefined;
        speech.transcribe.mockReturnValue(new Promise<TranscribeOutcome>(resolve => { finish = resolve; }));
        main.click();
        await flush();
        expect(main.textContent).toContain('Распознаю…');
        expect(main.getAttribute('aria-disabled')).toBe('true');
        expect(document.activeElement).toBe(main);
        finish({ ok: true, text: 'привет', garbled: false });
        await flush();
        expect(root().querySelector('.mic-row .mic-button')).toBe(main);
    });

    it('shows «Распознаю…» while the text is awaited and lets the user cancel it', async () => {
        let abort: AbortSignal | undefined;
        speech.transcribe.mockImplementation((_request: unknown, signal: AbortSignal) => { abort = signal; return new Promise(() => undefined); });
        await record();
        expect(button('Распознаю').getAttribute('aria-disabled')).toBe('true');
        expect(status()).toBe('Распознаю…');
        button('Отмена').click();
        await flush();
        expect(abort?.aborted).toBe(true);
        expect(status()).toBe('Запись отменена.');
        expect(button('Начать запись')).toBeTruthy();
        expect(field().value).toBe('');
    });

    it('says a refusal in the status and leaves the field alone', async () => {
        speech.transcribe.mockResolvedValue({ ok: false, kind: 'error', message: 'Не расслышала речь — попробуйте ещё раз.' });
        await record();
        expect(status()).toBe('Не расслышала речь — попробуйте ещё раз.');
        expect(field().value).toBe('');
        expect(button('Начать запись')).toBeTruthy();
    });

    it('shows the permission refusal calmly and never reaches the server', async () => {
        vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockRejectedValue(new DOMException('no', 'NotAllowedError'));
        button('Начать запись').click();
        await flush();
        expect(status()).toBe('Нет доступа к микрофону. Разрешите его в настройках браузера.');
        expect(speech.transcribe).not.toHaveBeenCalled();
    });

    it('asks for the consent first, records after «Согласен» and says where the speech is processed', async () => {
        speech.checkConsent.mockResolvedValue({ ok: false, consent: consent(false) });
        button('Начать запись').click();
        await flush();
        const dialog = root().querySelector('dialog')!;
        expect(dialog.hasAttribute('open')).toBe(true);
        expect(dialog.textContent).toContain('на наших серверах в России');
        expect(dialog.textContent).toContain('удаляется');
        expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled();
        button('Согласен').click();
        await flush();
        expect(speech.accept).toHaveBeenCalledWith(consent(false));
        expect(dialog.hasAttribute('open')).toBe(false);
        expect(button('Остановить запись')).toBeTruthy();
    });

    it('describes foreign processing as de-identified', async () => {
        speech.checkConsent.mockResolvedValue({ ok: false, consent: { required: { version: 3, processing: 'ABROAD' }, accepted: null } });
        button('Начать запись').click();
        await flush();
        expect(root().querySelector('dialog')!.textContent).toContain('зарубежный сервис распознавания');
        expect(root().querySelector('dialog')!.textContent).toContain('обезличена');
    });

    it('«Не сейчас» and Esc close the disclosure without recording or saving anything', async () => {
        speech.checkConsent.mockResolvedValue({ ok: false, consent: consent(false) });
        button('Начать запись').click();
        await flush();
        button('Не сейчас').click();
        await flush();
        expect(root().querySelector('dialog')!.hasAttribute('open')).toBe(false);
        expect(speech.accept).not.toHaveBeenCalled();
        expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled();
        expect(button('Начать запись')).toBeTruthy();

        button('Начать запись').click();
        await flush();
        const cancel = new Event('cancel', { cancelable: true });
        root().querySelector('dialog')!.dispatchEvent(cancel);
        await flush();
        expect(cancel.defaultPrevented).toBe(true);
        expect(root().querySelector('dialog')!.hasAttribute('open')).toBe(false);
        expect(speech.accept).not.toHaveBeenCalled();
    });

    it('keeps the recording when the server wants a consent the page did not know, then sends it again', async () => {
        speech.transcribe.mockResolvedValueOnce({ ok: false, kind: 'consent' });
        speech.checkConsent.mockResolvedValueOnce({ ok: true }).mockResolvedValueOnce({ ok: false, consent: consent(false) });
        await record();
        expect(root().querySelector('dialog')!.hasAttribute('open')).toBe(true);
        const firstKey = speech.transcribe.mock.calls[0][0].idempotencyKey;
        button('Согласен').click();
        await flush();
        expect(speech.transcribe).toHaveBeenCalledTimes(2);
        expect(speech.transcribe.mock.calls[1][0].idempotencyKey).toBe(firstKey);
        expect(field().value).toBe('привет мир');
    });

    it('does not record when the consent check itself fails', async () => {
        speech.checkConsent.mockResolvedValue({ ok: false, consent: null, message: 'Нет связи. Попробуйте ещё раз или напишите текстом.' });
        button('Начать запись').click();
        await flush();
        expect(status()).toContain('Нет связи');
        expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled();
    });

    it('shows the quiet fair-use counter only when the server warns', async () => {
        usage.load.mockReturnValue(of({ fairUse: { stt: { used: 52, limit: 60, warn: true } } }));
        const second = TestBed.createComponent(HostComponent);
        second.detectChanges();
        expect(second.nativeElement.querySelector('.mic-usage')?.textContent).toContain('Голос: 52');
        expect(second.nativeElement.querySelector('.mic-usage')?.textContent).toContain('из');
        expect(root().querySelector('.mic-usage')).toBeNull();
    });

    it('releases the microphone when the page goes away mid-recording', async () => {
        button('Начать запись').click();
        await flush();
        fixture.destroy();
        expect(tracks[0].stop).toHaveBeenCalled();
        expect(speech.transcribe).not.toHaveBeenCalled();
    });

    it('uses the host-provided label', async () => {
        fixture.componentInstance.label = 'Ответить голосом';
        fixture = TestBed.createComponent(HostComponent);
        fixture.componentInstance.label = 'Ответить голосом';
        fixture.detectChanges();
        expect(buttons().some(candidate => candidate.textContent?.includes('Ответить голосом'))).toBe(true);
    });
});
