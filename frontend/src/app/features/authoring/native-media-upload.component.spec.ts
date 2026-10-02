import type { Mock } from "vitest";
import { TestBed } from '@angular/core/testing';

import { createEmptyNativeDocument } from '../../content/editing/native-editor-adapter';
import { NativeMediaUploadApi, UploadView } from './native-media-upload.api';
import { NativeMediaUploadComponent } from './native-media-upload.component';
import { lastCall } from '../../../testing/mocks';

describe('NativeMediaUploadComponent', () => {
    beforeEach(() => {
        vi.useFakeTimers();
    });
    afterEach(() => {
        vi.useRealTimers();
    });
    const assetId = '0c2f05ec-8e16-464a-95d7-8fd97602d12d';
    const open: UploadView = {
        assetId, generation: 0, currentGeneration: 0, state: 'OPEN', assetState: 'PENDING_UPLOAD',
        method: 'SINGLE', declaredLength: 3, declaredMime: 'image/png', expiresAt: '2026-09-29T00:00:00Z',
        partSize: null, partCount: null, url: 'https://storage.example/signed', headers: { 'content-length': '3' },
        urlExpiresAt: '2099-01-01T00:00:00Z', parts: []
    };

    function setup() {
        const api = {
            policy: vi.fn().mockName("NativeMediaUploadApi.policy"),
            intent: vi.fn().mockName("NativeMediaUploadApi.intent"),
            status: vi.fn().mockName("NativeMediaUploadApi.status"),
            partUrls: vi.fn().mockName("NativeMediaUploadApi.partUrls"),
            completedParts: vi.fn().mockName("NativeMediaUploadApi.completedParts"),
            finalize: vi.fn().mockName("NativeMediaUploadApi.finalize"),
            retry: vi.fn().mockName("NativeMediaUploadApi.retry"),
            cancel: vi.fn().mockName("NativeMediaUploadApi.cancel"),
            renewSingle: vi.fn().mockName("NativeMediaUploadApi.renewSingle"),
            put: vi.fn().mockName("NativeMediaUploadApi.put")
        };
        api.policy.mockResolvedValue({ maxImageBytes: 67108864, maxAudioBytes: 536870912,
            maxVideoBytes: 4294967296 });
        api.intent.mockResolvedValue(open);
        api.put.mockResolvedValue(undefined);
        api.finalize.mockResolvedValue({ ...open, state: 'SEALED', assetState: 'VERIFYING', url: null });
        api.status.mockResolvedValue({ ...open, state: 'SEALED', assetState: 'READY', url: null });
        TestBed.configureTestingModule({ providers: [{ provide: NativeMediaUploadApi, useValue: api }] });
        const fixture = TestBed.createComponent(NativeMediaUploadComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        return { fixture, component: fixture.componentInstance, api };
    }

    it('reserves an asset before upload completes and keeps it available for insertion', async () => {
        const { fixture, component, api } = setup();
        const selected: string[] = [];
        component.chooseAsset.subscribe(value => selected.push(value.assetId));
        component.onFiles([new File(['png'], 'diagram.png', { type: 'image/png' })]);
        await vi.advanceTimersByTimeAsync(0);

        expect(api.intent).toHaveBeenCalledTimes(1);

        expect(api.intent).toHaveBeenCalledWith(expect.any(String), 'upload', 'image', 'image/png', 3);
        expect(api.put).toHaveBeenCalled();
        expect(api.finalize).toHaveBeenCalled();
        expect(component.entries()[0].phase).toBe('waiting');
        component.choose(component.entries()[0]);
        expect(selected).toEqual([assetId]);
        fixture.destroy();
    });

    it('limits files, queue, camera and recorder to the requested kind', () => {
        const { fixture, component, api } = setup();
        fixture.componentRef.setInput('kind', 'audio');
        fixture.detectChanges();
        const root = fixture.nativeElement as HTMLElement;
        expect(root.querySelector('input[type="file"]')?.getAttribute('accept')).toBe('.mp3,.m4a,.webm');
        expect(root.textContent).toContain('MP3, M4A или WebM');
        expect(root.textContent).toContain('Записать аудио');
        expect(root.textContent).not.toContain('Открыть камеру');
        component.onFiles([new File(['png'], 'diagram.png', { type: 'image/png' })]);
        fixture.detectChanges();
        expect(api.intent).not.toHaveBeenCalled();
        expect(root.querySelector('.media-status')?.textContent).toContain('Здесь нужно аудио');
        fixture.componentRef.setInput('kind', 'image');
        fixture.detectChanges();
        expect(root.textContent).toContain('Открыть камеру');
        expect(root.textContent).not.toContain('Записать аудио');
        expect(root.textContent).not.toContain('Здесь нужно аудио');
        expect(root.querySelector('input[type="file"]')?.getAttribute('accept')).toBe('.jpg,.jpeg,.png,.webp,.gif');
        fixture.componentRef.setInput('kind', null);
        fixture.detectChanges();
        expect(root.textContent).toContain('Записать аудио');
        expect(root.textContent).toContain('Открыть камеру');
        fixture.destroy();
    });

    it('rejects unknown files before reserving storage', () => {
        const { fixture, component, api } = setup();
        component.onFiles([new File(['bad'], 'diagram.svg', { type: 'image/svg+xml' })]);
        expect(component.entries()[0].phase).toBe('error');
        expect(api.intent).not.toHaveBeenCalled();
        fixture.destroy();
    });

    it('uses the live server video cap before reserving an upload intent', async () => {
        const { fixture, component, api } = setup();
        api.policy.mockResolvedValue({ maxImageBytes: 10, maxAudioBytes: 10, maxVideoBytes: 2 });
        component.onFiles([new File(['clip'], 'clip.mov', { type: 'video/quicktime' })]);
        await vi.advanceTimersByTimeAsync(0);
        expect(component.entries()[0].phase).toBe('error');
        expect(component.entries()[0].error).toBe('Файл слишком большой. Выберите файл меньшего размера.');
        expect(api.intent).not.toHaveBeenCalled();
        fixture.destroy();
    });

    it('previews a recorded clip with the shared accessible audio player', () => {
        const { fixture, component } = setup();
        component.recordPreview.set('blob:recording-preview');
        fixture.detectChanges();
        const player = fixture.nativeElement.querySelector('app-native-media-player');
        expect(player).not.toBeNull();
        expect(player.querySelector('audio')).not.toBeNull();
        fixture.destroy();
    });

    it('opens the camera stream in the panel and closes its tracks without a file picker', async () => {
        const { fixture, component } = setup();
        const stream = new MediaStream();
        const stop = vi.fn().mockName('stop');
        vi.spyOn(stream, 'getTracks').mockReturnValue([{ stop } as unknown as MediaStreamTrack]);
        vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
        const preview = fixture.nativeElement.querySelector('video') as HTMLVideoElement;
        vi.spyOn(preview, 'play').mockResolvedValue();

        component.startCamera();
        await vi.advanceTimersByTimeAsync(0);
        fixture.detectChanges();
        expect(component.cameraOpen()).toBe(true);
        expect(preview.srcObject).toBe(stream);
        expect(fixture.nativeElement.querySelector('.camera-preview').hidden).toBe(false);
        component.stopCamera();
        expect(stop).toHaveBeenCalled();
        expect(preview.srcObject).toBeNull();
        fixture.destroy();
    });

    it('recovers an asset reference from a server draft without inventing local file bytes', async () => {
        const { fixture, component, api } = setup();
        const documentValue = createEmptyNativeDocument();
        fixture.componentRef.setInput('document', {
            ...documentValue, root: { ...documentValue.root, content: [{
                        id: '0983ec5d-722e-4ea5-af04-2d331c7c545e', type: 'image', version: 1,
                        attrs: { assetId, alt: 'Схема API' }, content: []
                    }] }
        });
        fixture.detectChanges();
        await vi.advanceTimersByTimeAsync(0);
        expect(component.entries()[0].assetId).toBe(assetId);
        expect(component.entries()[0].file).toBeNull();
        expect(component.entries()[0].phase).toBe('needs-file');
        expect(api.intent).not.toHaveBeenCalled();
        fixture.destroy();
    });

    it('finishes an active byte transfer after SPA navigation from the editor', async () => {
        const { fixture, component, api } = setup();
        let finishPut: (() => void) | undefined;
        api.put.mockReturnValue(new Promise<void>(resolve => { finishPut = resolve; }));
        component.onFiles([new File(['png'], 'diagram.png', { type: 'image/png' })]);
        await vi.advanceTimersByTimeAsync(0);
        expect(api.put).toHaveBeenCalled();
        fixture.destroy();
        finishPut?.();
        await vi.advanceTimersByTimeAsync(0);
        expect(api.finalize).toHaveBeenCalledTimes(1);
        expect(api.finalize).toHaveBeenCalledWith(assetId, 0, expect.any(String));
    });

    it('resumes multipart from server-observed parts without reuploading accepted bytes', async () => {
        const { fixture, component, api } = setup();
        const multipart: UploadView = { ...open, method: 'MULTIPART', declaredLength: 6, partSize: 3,
            partCount: 2, url: null, headers: {}, urlExpiresAt: null };
        api.intent.mockResolvedValue(multipart);
        api.completedParts.mockResolvedValue([1]);
        api.partUrls.mockResolvedValue({ ...multipart, parts: [{ number: 2, length: 3,
                    url: 'https://storage.example/part-2', headers: { 'content-length': '3' },
                    expiresAt: '2099-01-01T00:00:00Z' }] });
        component.onFiles([new File(['abcdef'], 'clip.mp4', { type: 'video/mp4' })]);
        await vi.advanceTimersByTimeAsync(0);
        expect(api.completedParts).toHaveBeenCalledTimes(1);
        expect(api.completedParts).toHaveBeenCalledWith(assetId, 0);
        expect(api.put).toHaveBeenCalledTimes(1);
        expect(lastCall(api.put)[1].size).toBe(3);
        expect(api.finalize).toHaveBeenCalled();
        fixture.destroy();
    });

    it('renews an expired single URL before resuming an existing asset', async () => {
        const { fixture, component, api } = setup();
        const file = new File(['png'], 'diagram.png', { type: 'image/png' });
        api.status.mockResolvedValue({ ...open, url: null, urlExpiresAt: null });
        api.renewSingle.mockResolvedValue(open);
        component.entries.set([{ id: 'existing', name: file.name, kind: 'image', file,
                phase: 'error', assetId, transfer: null, progress: 0, error: 'Соединение прервалось',
                intentId: crypto.randomUUID(), finalizeId: crypto.randomUUID(), assetState: 'PENDING_UPLOAD' }]);
        component.retry(component.entries()[0]);
        await vi.advanceTimersByTimeAsync(0);
        expect(api.renewSingle).toHaveBeenCalledTimes(1);
        expect(api.renewSingle).toHaveBeenCalledWith(assetId, 0);
        expect(api.put).toHaveBeenCalled();
        expect(api.intent).not.toHaveBeenCalled();
        fixture.destroy();
    });

    describe('audio recorder', () => {
        class FakeRecorder {
            static instances: FakeRecorder[] = [];
            static payload = new Blob(['audio-bytes']);
            static isTypeSupported = (): boolean => true;
            state: 'inactive' | 'recording' = 'inactive';
            mimeType: string;
            ondataavailable: ((event: {
                data: Blob;
            }) => void) | null = null;
            onstop: (() => void) | null = null;
            onerror: (() => void) | null = null;
            constructor(readonly stream: unknown, options: {
                mimeType: string;
            }) { this.mimeType = options.mimeType; FakeRecorder.instances.push(this); }
            start(): void { this.state = 'recording'; }
            stop(): void {
                this.state = 'inactive';
                this.ondataavailable?.({ data: FakeRecorder.payload });
                this.onstop?.();
            }
        }
        const globals = window as unknown as {
            MediaRecorder: unknown;
        };
        let original: unknown;
        let tracks: {
            stop: Mock;
            onended: (() => void) | null;
        }[];
        let stream: MediaStream;

        beforeEach(() => {
            original = globals.MediaRecorder;
            globals.MediaRecorder = FakeRecorder;
            FakeRecorder.instances = [];
            FakeRecorder.payload = new Blob(['audio-bytes']);
            tracks = [{ stop: vi.fn().mockName('stop'), onended: null }];
            stream = { getTracks: () => tracks, getAudioTracks: () => tracks } as unknown as MediaStream;
        });
        afterEach(() => { globals.MediaRecorder = original; });

        it('never touches the microphone until the explicit record click', () => {
            const getUserMedia = vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
            const { fixture } = setup();
            fixture.detectChanges();
            expect(getUserMedia).not.toHaveBeenCalled();
            expect(fixture.nativeElement.textContent).toContain('Записать аудио');
            fixture.destroy();
        });

        it('explains a denied permission, a missing device and an unsupported browser without losing the page state', async () => {
            const { fixture, component } = setup();
            const getUserMedia = vi.spyOn(navigator.mediaDevices, 'getUserMedia');
            getUserMedia.mockRejectedValue(new DOMException('no', 'NotAllowedError'));
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.message()).toContain('Доступ к микрофону запрещён');
            expect(component.requesting()).toBe(false);
            expect(component.recording()).toBe(false);
            expect(FakeRecorder.instances.length).toBe(0);

            getUserMedia.mockRejectedValue(new DOMException('none', 'NotFoundError'));
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.message()).toContain('Микрофон не найден');
            getUserMedia.mockRejectedValue(new DOMException('busy', 'NotReadableError'));
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.message()).toContain('занят другим приложением');
            getUserMedia.mockRejectedValue(new Error('other'));
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.message()).toContain('Микрофон недоступен');

            FakeRecorder.isTypeSupported = () => false;
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.message()).toContain('не поддерживает подходящий формат');
            FakeRecorder.isTypeSupported = () => true;
            fixture.destroy();
        });

        it('records, previews, uploads as a recording and releases every resource', async () => {
            const { fixture, component, api } = setup();
            const revoke = vi.spyOn(URL, 'revokeObjectURL');
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.recording()).toBe(true);
            fixture.detectChanges();
            expect(fixture.nativeElement.textContent).toContain('Идёт запись');
            expect(fixture.nativeElement.textContent).toContain('Отменить запись');

            component.stopRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.recording()).toBe(false);
            expect(tracks[0].stop).toHaveBeenCalled();
            const preview = component.recordPreview();
            expect(preview).toMatch(/^blob:/);

            component.useRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(api.intent).toHaveBeenCalledTimes(1);
            expect(api.intent).toHaveBeenCalledWith(expect.any(String), 'recording', 'audio', 'audio/webm;codecs=opus', 'audio-bytes'.length);
            expect(component.recordPreview()).toBeNull();
            expect(revoke).toHaveBeenCalledWith(preview!);
            fixture.destroy();
        });

        it('refuses an empty recording instead of uploading it', async () => {
            const { fixture, component, api } = setup();
            FakeRecorder.payload = new Blob([]);
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            component.stopRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.message()).toContain('пустой');
            expect(component.recordPreview()).toBeNull();
            expect(component.entries().length).toBe(0);
            expect(api.intent).not.toHaveBeenCalled();
            expect(tracks[0].stop).toHaveBeenCalled();
            fixture.destroy();
        });

        it('cancels a running recording without a preview and stops the device', async () => {
            const { fixture, component } = setup();
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            component.cancelRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.recording()).toBe(false);
            expect(component.recordPreview()).toBeNull();
            expect(tracks[0].stop).toHaveBeenCalled();
            expect(FakeRecorder.instances[0].state).toBe('inactive');
            expect(component.message()).toBeNull();
            fixture.destroy();
        });

        it('releases a microphone granted after the permission prompt was cancelled', async () => {
            const { fixture, component } = setup();
            let grant: (value: MediaStream) => void = () => undefined;
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockReturnValue(new Promise(resolve => { grant = resolve; }));
            void component.startRecording();
            expect(component.requesting()).toBe(true);
            fixture.detectChanges();
            expect(fixture.nativeElement.textContent).toContain('Ждём разрешения микрофона');
            component.cancelRecording();
            expect(component.requesting()).toBe(false);
            grant(stream);
            await vi.advanceTimersByTimeAsync(0);
            expect(tracks[0].stop).toHaveBeenCalled();
            expect(FakeRecorder.instances.length).toBe(0);
            expect(component.recording()).toBe(false);
            fixture.destroy();
        });

        it('stops the device when the component is destroyed while recording', async () => {
            const { fixture, component } = setup();
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            fixture.destroy();
            expect(tracks[0].stop).toHaveBeenCalled();
            expect(FakeRecorder.instances[0].state).toBe('inactive');
        });

        it('frees the preview URL when the component is destroyed', async () => {
            const { fixture, component } = setup();
            const revoke = vi.spyOn(URL, 'revokeObjectURL');
            vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:kept');
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            component.stopRecording();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.recordPreview()).toBe('blob:kept');
            fixture.destroy();
            expect(revoke).toHaveBeenCalledWith('blob:kept');
        });

        it('stops by itself at the duration limit and when the device disappears', async () => {
            const { fixture, component } = setup();
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            await vi.advanceTimersByTimeAsync(10 * 60000);
            expect(component.recording()).toBe(false);
            expect(component.message()).toContain('предел записи');
            expect(component.recordPreview()).not.toBeNull();

            component.discardRecording();
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            tracks[0].onended?.();
            await vi.advanceTimersByTimeAsync(0);
            expect(component.recording()).toBe(false);
            expect(component.recordPreview()).not.toBeNull();
            fixture.destroy();
        });

        it('reports a recorder failure and keeps the queue untouched', async () => {
            const { fixture, component } = setup();
            vi.spyOn(navigator.mediaDevices, 'getUserMedia').mockResolvedValue(stream);
            void component.startRecording();
            await vi.advanceTimersByTimeAsync(0);
            FakeRecorder.instances[0].onerror?.();
            expect(component.message()).toContain('Запись прервалась');
            expect(component.recording()).toBe(false);
            expect(tracks[0].stop).toHaveBeenCalled();
            fixture.destroy();
        });
    });
});
