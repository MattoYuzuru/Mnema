import { AudioRecorder, RecordedAudio, RecordingEnd, SPEECH_MAX_MS, SPEECH_MIME_PREFERENCE, clockText } from './audio-recorder';

class FakeRecorder {
    static instances: FakeRecorder[] = [];
    static supported: readonly string[] = [];
    static payload = new Blob(['audio-bytes']);
    static isTypeSupported = (type: string): boolean => FakeRecorder.supported.includes(type);
    state: 'inactive' | 'recording' = 'inactive';
    readonly mimeType: string;
    ondataavailable: ((event: { data: Blob }) => void) | null = null;
    onstop: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(readonly stream: unknown, options: { mimeType: string }) { this.mimeType = options.mimeType; FakeRecorder.instances.push(this); }
    start(): void { this.state = 'recording'; }
    stop(): void { this.state = 'inactive'; this.ondataavailable?.({ data: FakeRecorder.payload }); this.onstop?.(); }
}

describe('AudioRecorder', () => {
    const globals = globalThis as unknown as { MediaRecorder: unknown };
    let original: unknown;
    let tracks: { stop: ReturnType<typeof vi.fn>; onended: (() => void) | null }[];
    let stream: MediaStream;
    let finished: { audio: RecordedAudio; reason: RecordingEnd }[];

    const create = (options: Partial<ConstructorParameters<typeof AudioRecorder>[0]> = {}) =>
        new AudioRecorder({ onFinished: (audio, reason) => finished.push({ audio, reason }), ...options });

    beforeEach(() => {
        vi.useFakeTimers();
        original = globals.MediaRecorder;
        globals.MediaRecorder = FakeRecorder;
        FakeRecorder.instances = [];
        FakeRecorder.supported = [...SPEECH_MIME_PREFERENCE];
        FakeRecorder.payload = new Blob(['audio-bytes']);
        finished = [];
        tracks = [{ stop: vi.fn(), onended: null }];
        stream = { getTracks: () => tracks, getAudioTracks: () => tracks } as unknown as MediaStream;
        vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
    });
    afterEach(() => { globals.MediaRecorder = original; vi.useRealTimers(); vi.restoreAllMocks(); });

    it('prefers audio/mp4, then ogg/opus, then webm/opus', async () => {
        for (const [supported, expected] of [
            [['audio/mp4', 'audio/ogg;codecs=opus', 'audio/webm;codecs=opus'], 'audio/mp4'],
            [['audio/ogg;codecs=opus', 'audio/webm;codecs=opus'], 'audio/ogg;codecs=opus'],
            [['audio/webm;codecs=opus'], 'audio/webm;codecs=opus']
        ] as const) {
            FakeRecorder.instances = [];
            FakeRecorder.supported = supported;
            const recorder = create();
            expect(await recorder.start()).toBe(true);
            expect(FakeRecorder.instances[0].mimeType).toBe(expected);
            recorder.cancel();
        }
    });

    it('says so, without asking for the microphone, when no format is supported or there is no recorder', async () => {
        FakeRecorder.supported = [];
        const recorder = create();
        expect(await recorder.start()).toBe(false);
        expect(recorder.error()).toContain('не поддерживает подходящий формат');
        expect(navigator.mediaDevices.getUserMedia).not.toHaveBeenCalled();
        globals.MediaRecorder = undefined;
        expect(await create().start()).toBe(false);
    });

    it('records, measures the duration, hands the audio over and releases the microphone', async () => {
        const recorder = create();
        expect(recorder.state()).toBe('idle');
        expect(await recorder.start()).toBe(true);
        expect(recorder.state()).toBe('recording');
        await vi.advanceTimersByTimeAsync(3200);
        expect(recorder.elapsedMs()).toBe(3000);
        recorder.stop();
        expect(recorder.state()).toBe('idle');
        expect(tracks[0].stop).toHaveBeenCalled();
        expect(finished).toHaveLength(1);
        expect(finished[0].reason).toBe('stopped');
        expect(finished[0].audio.durationMs).toBe(3200);
        expect(finished[0].audio.mimeType).toBe('audio/mp4');
        expect(finished[0].audio.blob.size).toBe('audio-bytes'.length);
    });

    it('stops by itself at the limit and reports why', async () => {
        const recorder = create();
        await recorder.start();
        await vi.advanceTimersByTimeAsync(SPEECH_MAX_MS + 5);
        expect(recorder.state()).toBe('idle');
        expect(finished).toHaveLength(1);
        expect(finished[0].reason).toBe('limit');
        expect(finished[0].audio.durationMs).toBe(SPEECH_MAX_MS);
        expect(tracks[0].stop).toHaveBeenCalled();
        expect(vi.getTimerCount()).toBe(0);
    });

    it('keeps what was recorded when the device disappears', async () => {
        const recorder = create();
        await recorder.start();
        await vi.advanceTimersByTimeAsync(1000);
        tracks[0].onended?.();
        expect(finished.map(entry => entry.reason)).toEqual(['device']);
    });

    it('produces nothing after cancel and releases the device and the timers', async () => {
        const recorder = create();
        await recorder.start();
        recorder.cancel();
        expect(finished).toEqual([]);
        expect(recorder.state()).toBe('idle');
        expect(tracks[0].stop).toHaveBeenCalled();
        expect(FakeRecorder.instances[0].state).toBe('inactive');
        expect(vi.getTimerCount()).toBe(0);
    });

    it('releases a microphone granted after the request was cancelled', async () => {
        let grant: (value: MediaStream) => void = () => undefined;
        vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockReturnValue(new Promise(resolve => { grant = resolve; }));
        const recorder = create();
        const started = recorder.start();
        expect(recorder.state()).toBe('requesting');
        recorder.cancel();
        grant(stream);
        expect(await started).toBe(false);
        expect(tracks[0].stop).toHaveBeenCalled();
        expect(FakeRecorder.instances).toHaveLength(0);
    });

    it('releases the microphone when the owner is destroyed mid-recording', async () => {
        const recorder = create();
        await recorder.start();
        recorder.dispose();
        expect(tracks[0].stop).toHaveBeenCalled();
        expect(finished).toEqual([]);
    });

    it('does not start twice', async () => {
        const recorder = create();
        await recorder.start();
        expect(await recorder.start()).toBe(false);
        expect(FakeRecorder.instances).toHaveLength(1);
        recorder.cancel();
    });

    it('turns a refused microphone into a calm sentence', async () => {
        const sentences: string[] = [];
        for (const name of ['NotAllowedError', 'NotFoundError', 'NotReadableError', 'Other']) {
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockRejectedValue(new DOMException('x', name));
            const recorder = create({ onError: message => sentences.push(message) });
            expect(await recorder.start()).toBe(false);
            expect(recorder.state()).toBe('idle');
            expect(recorder.error()).toBe(sentences.at(-1));
        }
        expect(sentences[0]).toBe('Нет доступа к микрофону. Разрешите его в настройках браузера.');
        expect(sentences[1]).toContain('не найден');
        expect(sentences[2]).toContain('занят');
        expect(sentences[3]).toBe(sentences[0]);
    });

    it('refuses an empty recording and a recorder failure', async () => {
        FakeRecorder.payload = new Blob([]);
        const recorder = create();
        await recorder.start();
        recorder.stop();
        expect(finished).toEqual([]);
        expect(recorder.error()).toContain('пустой');
        FakeRecorder.payload = new Blob(['x']);
        await recorder.start();
        FakeRecorder.instances[1].onerror?.();
        expect(recorder.error()).toContain('прервалась');
        expect(recorder.state()).toBe('idle');
        expect(tracks[0].stop).toHaveBeenCalled();
    });

    it('formats the elapsed time', () => {
        expect(clockText(0)).toBe('0:00');
        expect(clockText(7_400)).toBe('0:07');
        expect(clockText(60_000)).toBe('1:00');
    });
});
