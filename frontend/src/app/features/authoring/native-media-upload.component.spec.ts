import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';

import { createEmptyNativeDocument } from '../../content/editing/native-editor-adapter';
import { NativeMediaUploadApi, UploadView } from './native-media-upload.api';
import { NativeMediaUploadComponent } from './native-media-upload.component';

describe('NativeMediaUploadComponent', () => {
    const assetId = '0c2f05ec-8e16-464a-95d7-8fd97602d12d';
    const open: UploadView = {
        assetId, generation: 0, currentGeneration: 0, state: 'OPEN', assetState: 'PENDING_UPLOAD',
        method: 'SINGLE', declaredLength: 3, declaredMime: 'image/png', expiresAt: '2026-09-29T00:00:00Z',
        partSize: null, partCount: null, url: 'https://storage.example/signed', headers: { 'content-length': '3' },
        urlExpiresAt: '2099-01-01T00:00:00Z', parts: []
    };

    function setup() {
        const api = jasmine.createSpyObj<NativeMediaUploadApi>('NativeMediaUploadApi', [
            'policy', 'intent', 'status', 'partUrls', 'completedParts', 'finalize', 'retry', 'cancel', 'renewSingle', 'put'
        ]);
        api.policy.and.resolveTo({ maxImageBytes: 67_108_864, maxAudioBytes: 536_870_912,
            maxVideoBytes: 4_294_967_296 });
        api.intent.and.resolveTo(open);
        api.put.and.resolveTo();
        api.finalize.and.resolveTo({ ...open, state: 'SEALED', assetState: 'VERIFYING', url: null });
        api.status.and.resolveTo({ ...open, state: 'SEALED', assetState: 'READY', url: null });
        TestBed.configureTestingModule({ providers: [{ provide: NativeMediaUploadApi, useValue: api }] });
        const fixture = TestBed.createComponent(NativeMediaUploadComponent);
        fixture.componentRef.setInput('document', createEmptyNativeDocument());
        fixture.detectChanges();
        return { fixture, component: fixture.componentInstance, api };
    }

    it('reserves an asset before upload completes and keeps it available for insertion', fakeAsync(() => {
        const { fixture, component, api } = setup();
        const selected: string[] = [];
        component.chooseAsset.subscribe(value => selected.push(value.assetId));
        component.onFiles([new File(['png'], 'diagram.png', { type: 'image/png' })]);
        flushMicrotasks();

        expect(api.intent).toHaveBeenCalledOnceWith(jasmine.any(String), 'upload', 'image', 'image/png', 3);
        expect(api.put).toHaveBeenCalled();
        expect(api.finalize).toHaveBeenCalled();
        expect(component.entries()[0].phase).toBe('waiting');
        component.choose(component.entries()[0]);
        expect(selected).toEqual([assetId]);
        fixture.destroy();
    }));

    it('rejects unknown files before reserving storage', () => {
        const { fixture, component, api } = setup();
        component.onFiles([new File(['bad'], 'diagram.svg', { type: 'image/svg+xml' })]);
        expect(component.entries()[0].phase).toBe('error');
        expect(api.intent).not.toHaveBeenCalled();
        fixture.destroy();
    });

    it('uses the live server video cap before reserving an upload intent', fakeAsync(() => {
        const { fixture, component, api } = setup();
        api.policy.and.resolveTo({ maxImageBytes: 10, maxAudioBytes: 10, maxVideoBytes: 2 });
        component.onFiles([new File(['clip'], 'clip.mov', { type: 'video/quicktime' })]);
        flushMicrotasks();
        expect(component.entries()[0].phase).toBe('error');
        expect(component.entries()[0].error).toBe('Файл слишком большой. Выберите файл меньшего размера.');
        expect(api.intent).not.toHaveBeenCalled();
        fixture.destroy();
    }));

    it('previews a recorded clip with the shared accessible audio player', () => {
        const { fixture, component } = setup();
        component.recordPreview.set('blob:recording-preview');
        fixture.detectChanges();
        const player = fixture.nativeElement.querySelector('app-native-media-player');
        expect(player).not.toBeNull();
        expect(player.querySelector('audio')).not.toBeNull();
        fixture.destroy();
    });

    it('opens the camera stream in the panel and closes its tracks without a file picker', fakeAsync(() => {
        const { fixture, component } = setup();
        const stream = new MediaStream();
        const stop = jasmine.createSpy('stop');
        spyOn(stream, 'getTracks').and.returnValue([{ stop } as unknown as MediaStreamTrack]);
        spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
        const preview = fixture.nativeElement.querySelector('video') as HTMLVideoElement;
        spyOn(preview, 'play').and.resolveTo();

        component.startCamera();
        flushMicrotasks();
        fixture.detectChanges();
        expect(component.cameraOpen()).toBeTrue();
        expect(preview.srcObject).toBe(stream);
        expect(fixture.nativeElement.querySelector('.camera-preview').hidden).toBeFalse();
        component.stopCamera();
        expect(stop).toHaveBeenCalled();
        expect(preview.srcObject).toBeNull();
        fixture.destroy();
    }));

    it('recovers an asset reference from a server draft without inventing local file bytes', fakeAsync(() => {
        const { fixture, component, api } = setup();
        const documentValue = createEmptyNativeDocument();
        fixture.componentRef.setInput('document', {
            ...documentValue, root: { ...documentValue.root, content: [{
                id: '0983ec5d-722e-4ea5-af04-2d331c7c545e', type: 'image', version: 1,
                attrs: { assetId, alt: 'Схема API' }, content: []
            }] }
        });
        fixture.detectChanges();
        flushMicrotasks();
        expect(component.entries()[0].assetId).toBe(assetId);
        expect(component.entries()[0].file).toBeNull();
        expect(component.entries()[0].phase).toBe('needs-file');
        expect(api.intent).not.toHaveBeenCalled();
        fixture.destroy();
    }));

    it('finishes an active byte transfer after SPA navigation from the editor', fakeAsync(() => {
        const { fixture, component, api } = setup();
        let finishPut: (() => void) | undefined;
        api.put.and.returnValue(new Promise<void>(resolve => { finishPut = resolve; }));
        component.onFiles([new File(['png'], 'diagram.png', { type: 'image/png' })]);
        flushMicrotasks();
        expect(api.put).toHaveBeenCalled();
        fixture.destroy();
        finishPut?.();
        flushMicrotasks();
        expect(api.finalize).toHaveBeenCalledOnceWith(assetId, 0, jasmine.any(String));
    }));

    it('resumes multipart from server-observed parts without reuploading accepted bytes', fakeAsync(() => {
        const { fixture, component, api } = setup();
        const multipart: UploadView = { ...open, method: 'MULTIPART', declaredLength: 6, partSize: 3,
            partCount: 2, url: null, headers: {}, urlExpiresAt: null };
        api.intent.and.resolveTo(multipart);
        api.completedParts.and.resolveTo([1]);
        api.partUrls.and.resolveTo({ ...multipart, parts: [{ number: 2, length: 3,
            url: 'https://storage.example/part-2', headers: { 'content-length': '3' },
            expiresAt: '2099-01-01T00:00:00Z' }] });
        component.onFiles([new File(['abcdef'], 'clip.mp4', { type: 'video/mp4' })]);
        flushMicrotasks();
        expect(api.completedParts).toHaveBeenCalledOnceWith(assetId, 0);
        expect(api.put).toHaveBeenCalledTimes(1);
        expect(api.put.calls.mostRecent().args[1].size).toBe(3);
        expect(api.finalize).toHaveBeenCalled();
        fixture.destroy();
    }));

    it('renews an expired single URL before resuming an existing asset', fakeAsync(() => {
        const { fixture, component, api } = setup();
        const file = new File(['png'], 'diagram.png', { type: 'image/png' });
        api.status.and.resolveTo({ ...open, url: null, urlExpiresAt: null });
        api.renewSingle.and.resolveTo(open);
        component.entries.set([{ id: 'existing', name: file.name, kind: 'image', file,
            phase: 'error', assetId, transfer: null, progress: 0, error: 'Соединение прервалось',
            intentId: crypto.randomUUID(), finalizeId: crypto.randomUUID(), assetState: 'PENDING_UPLOAD' }]);
        component.retry(component.entries()[0]);
        flushMicrotasks();
        expect(api.renewSingle).toHaveBeenCalledOnceWith(assetId, 0);
        expect(api.put).toHaveBeenCalled();
        expect(api.intent).not.toHaveBeenCalled();
        fixture.destroy();
    }));

    describe('audio recorder', () => {
        class FakeRecorder {
            static instances: FakeRecorder[] = [];
            static payload = new Blob(['audio-bytes']);
            static isTypeSupported = (): boolean => true;
            state: 'inactive' | 'recording' = 'inactive';
            mimeType: string;
            ondataavailable: ((event: { data: Blob }) => void) | null = null;
            onstop: (() => void) | null = null;
            onerror: (() => void) | null = null;
            constructor(readonly stream: unknown, options: { mimeType: string }) { this.mimeType = options.mimeType; FakeRecorder.instances.push(this); }
            start(): void { this.state = 'recording'; }
            stop(): void {
                this.state = 'inactive';
                this.ondataavailable?.({ data: FakeRecorder.payload });
                this.onstop?.();
            }
        }
        const globals = window as unknown as { MediaRecorder: unknown };
        let original: unknown;
        let tracks: { stop: jasmine.Spy; onended: (() => void) | null }[];
        let stream: MediaStream;

        beforeEach(() => {
            original = globals.MediaRecorder;
            globals.MediaRecorder = FakeRecorder;
            FakeRecorder.instances = [];
            FakeRecorder.payload = new Blob(['audio-bytes']);
            tracks = [{ stop: jasmine.createSpy('stop'), onended: null }];
            stream = { getTracks: () => tracks, getAudioTracks: () => tracks } as unknown as MediaStream;
        });
        afterEach(() => { globals.MediaRecorder = original; });

        it('never touches the microphone until the explicit record click', () => {
            const getUserMedia = spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
            const { fixture } = setup();
            fixture.detectChanges();
            expect(getUserMedia).not.toHaveBeenCalled();
            expect(fixture.nativeElement.textContent).toContain('Записать аудио');
            fixture.destroy();
        });

        it('explains a denied permission, a missing device and an unsupported browser without losing the page state', fakeAsync(() => {
            const { fixture, component } = setup();
            const getUserMedia = spyOn(navigator.mediaDevices, 'getUserMedia');
            getUserMedia.and.rejectWith(new DOMException('no', 'NotAllowedError'));
            void component.startRecording(); flushMicrotasks();
            expect(component.message()).toContain('Доступ к микрофону запрещён');
            expect(component.requesting()).toBeFalse();
            expect(component.recording()).toBeFalse();
            expect(FakeRecorder.instances.length).toBe(0);

            getUserMedia.and.rejectWith(new DOMException('none', 'NotFoundError'));
            void component.startRecording(); flushMicrotasks();
            expect(component.message()).toContain('Микрофон не найден');
            getUserMedia.and.rejectWith(new DOMException('busy', 'NotReadableError'));
            void component.startRecording(); flushMicrotasks();
            expect(component.message()).toContain('занят другим приложением');
            getUserMedia.and.rejectWith(new Error('other'));
            void component.startRecording(); flushMicrotasks();
            expect(component.message()).toContain('Микрофон недоступен');

            FakeRecorder.isTypeSupported = () => false;
            void component.startRecording(); flushMicrotasks();
            expect(component.message()).toContain('не поддерживает подходящий формат');
            FakeRecorder.isTypeSupported = () => true;
            fixture.destroy();
        }));

        it('records, previews, uploads as a recording and releases every resource', fakeAsync(() => {
            const { fixture, component, api } = setup();
            const revoke = spyOn(URL, 'revokeObjectURL').and.callThrough();
            spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
            void component.startRecording(); flushMicrotasks();
            expect(component.recording()).toBeTrue();
            fixture.detectChanges();
            expect(fixture.nativeElement.textContent).toContain('Идёт запись');
            expect(fixture.nativeElement.textContent).toContain('Отменить запись');

            component.stopRecording(); flushMicrotasks();
            expect(component.recording()).toBeFalse();
            expect(tracks[0].stop).toHaveBeenCalled();
            const preview = component.recordPreview();
            expect(preview).toMatch(/^blob:/);

            component.useRecording(); flushMicrotasks();
            expect(api.intent).toHaveBeenCalledOnceWith(jasmine.any(String), 'recording', 'audio', 'audio/webm;codecs=opus', 'audio-bytes'.length);
            expect(component.recordPreview()).toBeNull();
            expect(revoke).toHaveBeenCalledWith(preview!);
            fixture.destroy();
        }));

        it('refuses an empty recording instead of uploading it', fakeAsync(() => {
            const { fixture, component, api } = setup();
            FakeRecorder.payload = new Blob([]);
            spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
            void component.startRecording(); flushMicrotasks();
            component.stopRecording(); flushMicrotasks();
            expect(component.message()).toContain('пустой');
            expect(component.recordPreview()).toBeNull();
            expect(component.entries().length).toBe(0);
            expect(api.intent).not.toHaveBeenCalled();
            expect(tracks[0].stop).toHaveBeenCalled();
            fixture.destroy();
        }));

        it('cancels a running recording without a preview and stops the device', fakeAsync(() => {
            const { fixture, component } = setup();
            spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
            void component.startRecording(); flushMicrotasks();
            component.cancelRecording(); flushMicrotasks();
            expect(component.recording()).toBeFalse();
            expect(component.recordPreview()).toBeNull();
            expect(tracks[0].stop).toHaveBeenCalled();
            expect(FakeRecorder.instances[0].state).toBe('inactive');
            expect(component.message()).toBeNull();
            fixture.destroy();
        }));

        it('releases a microphone granted after the permission prompt was cancelled', fakeAsync(() => {
            const { fixture, component } = setup();
            let grant: (value: MediaStream) => void = () => undefined;
            spyOn(navigator.mediaDevices, 'getUserMedia').and.returnValue(new Promise(resolve => { grant = resolve; }));
            void component.startRecording();
            expect(component.requesting()).toBeTrue();
            fixture.detectChanges();
            expect(fixture.nativeElement.textContent).toContain('Ждём разрешения микрофона');
            component.cancelRecording();
            expect(component.requesting()).toBeFalse();
            grant(stream); flushMicrotasks();
            expect(tracks[0].stop).toHaveBeenCalled();
            expect(FakeRecorder.instances.length).toBe(0);
            expect(component.recording()).toBeFalse();
            fixture.destroy();
        }));

        it('stops the device when the component is destroyed while recording', fakeAsync(() => {
            const { fixture, component } = setup();
            spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
            void component.startRecording(); flushMicrotasks();
            fixture.destroy();
            expect(tracks[0].stop).toHaveBeenCalled();
            expect(FakeRecorder.instances[0].state).toBe('inactive');
        }));

        it('frees the preview URL when the component is destroyed', fakeAsync(() => {
            const { fixture, component } = setup();
            const revoke = spyOn(URL, 'revokeObjectURL').and.callThrough();
            spyOn(URL, 'createObjectURL').and.returnValue('blob:kept');
            spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
            void component.startRecording(); flushMicrotasks();
            component.stopRecording(); flushMicrotasks();
            expect(component.recordPreview()).toBe('blob:kept');
            fixture.destroy();
            expect(revoke).toHaveBeenCalledWith('blob:kept');
        }));

        it('stops by itself at the duration limit and when the device disappears', fakeAsync(() => {
            const { fixture, component } = setup();
            spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
            void component.startRecording(); flushMicrotasks();
            tick(10 * 60_000);
            expect(component.recording()).toBeFalse();
            expect(component.message()).toContain('предел записи');
            expect(component.recordPreview()).not.toBeNull();

            component.discardRecording();
            void component.startRecording(); flushMicrotasks();
            tracks[0].onended?.(); flushMicrotasks();
            expect(component.recording()).toBeFalse();
            expect(component.recordPreview()).not.toBeNull();
            fixture.destroy();
        }));

        it('reports a recorder failure and keeps the queue untouched', fakeAsync(() => {
            const { fixture, component } = setup();
            spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
            void component.startRecording(); flushMicrotasks();
            FakeRecorder.instances[0].onerror?.();
            expect(component.message()).toContain('Запись прервалась');
            expect(component.recording()).toBeFalse();
            expect(tracks[0].stop).toHaveBeenCalled();
            fixture.destroy();
        }));
    });
});
