import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, effect, inject, input, output,
    signal, viewChild } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { NativeDocument, NativeNode } from '../../content/native-document';
import { AssetState, NativeMediaKind, NativeMediaUploadApi, UploadView } from './native-media-upload.api';

type QueuePhase = 'selected' | 'uploading' | 'finalizing' | 'waiting' | 'ready' | 'needs-file' | 'error' | 'cancelled';
interface QueueEntry {
    readonly id: string;
    readonly name: string;
    readonly kind: NativeMediaKind;
    readonly file: File | null;
    readonly phase: QueuePhase;
    readonly assetId: string | null;
    readonly transfer: UploadView | null;
    readonly progress: number;
    readonly error: string | null;
    readonly intentId: string;
    readonly finalizeId: string;
    readonly assetState: AssetState | null;
}

const MAX_PARALLEL_TRANSFERS = 2;
const POLL_INITIAL_MS = 2_000;
const POLL_MAX_MS = 15_000;
const PART_URL_BATCH = 16;
const FILE_ACCEPT = '.jpg,.jpeg,.png,.webp,.gif,.mp3,.m4a,.mp4,.mov,.webm';

const MIME_BY_EXTENSION: Readonly<Record<string, { kind: NativeMediaKind; mime: string }>> = {
    jpg: { kind: 'image', mime: 'image/jpeg' }, jpeg: { kind: 'image', mime: 'image/jpeg' },
    png: { kind: 'image', mime: 'image/png' }, webp: { kind: 'image', mime: 'image/webp' },
    gif: { kind: 'image', mime: 'image/gif' }, mp3: { kind: 'audio', mime: 'audio/mpeg' },
    m4a: { kind: 'audio', mime: 'audio/mp4' }, mp4: { kind: 'video', mime: 'video/mp4' },
    mov: { kind: 'video', mime: 'video/quicktime' }, webm: { kind: 'video', mime: 'video/webm' }
};

@Component({
    selector: 'app-native-media-upload',
    templateUrl: './native-media-upload.component.html',
    styleUrl: './native-media-upload.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class NativeMediaUploadComponent {
    readonly document = input.required<NativeDocument>();
    readonly disabled = input(false);
    readonly chooseAsset = output<{ kind: NativeMediaKind; assetId: string }>();
    readonly entries = signal<readonly QueueEntry[]>([]);
    readonly dropActive = signal(false);
    readonly recording = signal(false);
    readonly recordPreview = signal<string | null>(null);
    readonly message = signal<string | null>(null);
    readonly fileAccept = FILE_ACCEPT;

    private readonly api = inject(NativeMediaUploadApi);
    private readonly destroyRef = inject(DestroyRef);
    private readonly replacePicker = viewChild<ElementRef<HTMLInputElement>>('replacePicker');
    private activeTransfers = 0;
    private replaceTarget: string | null = null;
    private readonly aborts = new Map<string, AbortController>();
    private pollTimer: ReturnType<typeof setTimeout> | null = null;
    private pollDelay = POLL_INITIAL_MS;
    private recorder: MediaRecorder | null = null;
    private stream: MediaStream | null = null;
    private chunks: Blob[] = [];
    private recordedFile: File | null = null;
    private recordingUrl: string | null = null;

    constructor() {
        effect(() => this.recoverDocumentRefs(this.document()));
        const onVisibility = () => {
            if (document.visibilityState === 'visible') { this.pollDelay = POLL_INITIAL_MS; void this.refreshStatuses(); }
            else this.clearPoll();
        };
        document.addEventListener('visibilitychange', onVisibility);
        window.addEventListener('online', onVisibility);
        this.destroyRef.onDestroy(() => {
            document.removeEventListener('visibilitychange', onVisibility);
            window.removeEventListener('online', onVisibility);
            this.clearPoll();
            // In-flight byte transfers finish after SPA navigation to the published material.
            // A full browser reload still requires reselecting the local file.
            this.stopMediaTracks();
            if (this.recordingUrl !== null) URL.revokeObjectURL(this.recordingUrl);
        });
    }

    onFiles(files: FileList | readonly File[] | null): void {
        if (files === null || this.disabled()) return;
        const chosen = Array.from(files);
        if (this.replaceTarget !== null) {
            const target = this.replaceTarget;
            this.replaceTarget = null;
            if (chosen[0] !== undefined) void this.retryWithFile(target, chosen[0]);
            return;
        }
        for (const file of chosen) this.enqueue(file);
        this.drain();
    }

    onDrop(event: DragEvent): void {
        event.preventDefault();
        this.dropActive.set(false);
        this.onFiles(event.dataTransfer?.files ?? null);
    }

    onDragOver(event: DragEvent): void {
        event.preventDefault();
        if (!this.disabled()) this.dropActive.set(true);
    }

    choose(entry: QueueEntry): void {
        if (entry.assetId !== null) this.chooseAsset.emit({ kind: entry.kind, assetId: entry.assetId });
    }

    cancel(entry: QueueEntry): void {
        this.aborts.get(entry.id)?.abort();
        this.patch(entry.id, { phase: 'cancelled', error: null });
        if (entry.assetId !== null && entry.transfer !== null) {
            void this.api.cancel(entry.assetId, entry.transfer.generation).catch(() => {
                this.patch(entry.id, { error: 'Отмена на сервере не подтверждена. Проверьте состояние позже.' });
            });
        }
    }

    retry(entry: QueueEntry): void {
        if (entry.file === null) {
            this.replaceTarget = entry.id;
            this.replacePicker()?.nativeElement.click();
            return;
        }
        this.patch(entry.id, { phase: 'selected', error: null,
            transfer: ['REJECTED', 'FAILED_RETRYABLE'].includes(entry.assetState ?? '') ? null : entry.transfer });
        this.drain();
    }

    async startRecording(): Promise<void> {
        if (this.disabled() || this.recording()) return;
        if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
            this.message.set('Запись здесь недоступна. Выберите готовый аудиофайл.');
            return;
        }
        const mime = ['audio/webm;codecs=opus', 'audio/mp4'].find(candidate => MediaRecorder.isTypeSupported(candidate));
        if (mime === undefined) {
            this.message.set('Браузер не поддерживает подходящий формат записи. Выберите аудиофайл.');
            return;
        }
        try {
            this.discardRecording();
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            if (this.destroyRef.destroyed) { stream.getTracks().forEach(track => track.stop()); return; }
            this.stream = stream;
            this.chunks = [];
            const recorder = new MediaRecorder(stream, { mimeType: mime });
            this.recorder = recorder;
            recorder.ondataavailable = event => { if (event.data.size > 0) this.chunks.push(event.data); };
            recorder.onerror = () => { this.message.set('Запись прервалась. Повторите или выберите файл.'); this.stopMediaTracks(); };
            recorder.onstop = () => {
                const recorded = new Blob(this.chunks, { type: recorder.mimeType });
                const extension = recorder.mimeType.startsWith('audio/mp4') ? 'm4a' : 'webm';
                this.recordedFile = new File([recorded], `Запись-${Date.now()}.${extension}`, { type: recorder.mimeType });
                this.recordingUrl = URL.createObjectURL(recorded);
                this.recordPreview.set(this.recordingUrl);
                this.stopMediaTracks();
            };
            recorder.start();
            this.recording.set(true);
            this.message.set(null);
        } catch {
            this.stopMediaTracks();
            this.message.set('Микрофон недоступен или доступ запрещён. Выберите готовый файл.');
        }
    }

    stopRecording(): void {
        if (this.recorder?.state === 'recording') this.recorder.stop();
        this.recording.set(false);
    }

    useRecording(): void {
        if (this.recordedFile === null) return;
        this.enqueue(this.recordedFile, 'recording');
        this.discardRecording();
        this.drain();
    }

    discardRecording(): void {
        if (this.recorder?.state === 'recording') {
            this.recorder.onstop = null;
            this.recorder.stop();
            this.stopMediaTracks();
        }
        if (this.recordingUrl !== null) URL.revokeObjectURL(this.recordingUrl);
        this.recordingUrl = null;
        this.recordPreview.set(null);
        this.recordedFile = null;
    }

    private enqueue(file: File, origin: 'upload' | 'recording' = 'upload'): void {
        const candidate = classify(file);
        const id = crypto.randomUUID();
        const entry: QueueEntry = {
            id, name: file.name, kind: candidate?.kind ?? 'image', file: candidate === null ? null : file,
            phase: candidate === null ? 'error' : 'selected', assetId: null, transfer: null, progress: 0,
            error: candidate === null ? 'Формат файла не поддерживается. Выберите JPEG, PNG, WebP, GIF, MP3, M4A, MP4, MOV или WebM.' : null,
            intentId: crypto.randomUUID(), finalizeId: crypto.randomUUID(), assetState: null
        };
        this.entries.update(items => [...items, entry]);
        this.origins.set(id, origin);
    }

    private readonly origins = new Map<string, 'upload' | 'recording'>();

    private drain(): void {
        while (this.activeTransfers < MAX_PARALLEL_TRANSFERS) {
            const next = this.entries().find(entry => entry.phase === 'selected' && entry.file !== null);
            if (next === undefined) break;
            this.activeTransfers++;
            this.patch(next.id, { phase: 'uploading' });
            void this.transfer(next.id).finally(() => { this.activeTransfers--; this.drain(); });
        }
    }

    private async transfer(id: string): Promise<void> {
        const entry = this.find(id);
        if (entry?.file === null || entry === undefined) return;
        const file = entry.file;
        const classified = classify(file);
        if (classified === null) return;
        const abort = new AbortController();
        this.aborts.set(id, abort);
        try {
            const policy = await this.api.policy();
            const maxBytes = policy[entry.kind === 'image' ? 'maxImageBytes'
                : entry.kind === 'audio' ? 'maxAudioBytes' : 'maxVideoBytes'];
            if (!Number.isSafeInteger(maxBytes) || maxBytes < 1) {
                throw new Error('Invalid media upload policy');
            }
            if (file.size > maxBytes) {
                this.patch(id, { phase: 'error', error: `Файл превышает допустимый размер (${formatBytes(maxBytes)}). Выберите файл меньше.` });
                return;
            }
            let view = entry.transfer;
            if (view === null) {
                view = entry.assetId === null
                    ? await this.api.intent(entry.intentId, this.origins.get(id) ?? 'upload', entry.kind, classified.mime, file.size)
                    : await this.api.status(entry.assetId);
                this.patch(id, { assetId: view.assetId, transfer: view, assetState: view.assetState });
            } else {
                view = await this.api.status(view.assetId);
                this.patch(id, { transfer: view, assetState: view.assetState });
            }
            if (this.find(id)?.phase === 'cancelled') {
                await this.api.cancel(view.assetId, view.generation);
                return;
            }
            if (view.assetState === 'REJECTED' || view.assetState === 'FAILED_RETRYABLE'
                || view.state === 'EXPIRED' || view.state === 'ABORTED') {
                view = await this.api.retry(view.assetId, crypto.randomUUID(), entry.kind, classified.mime, file.size);
                this.patch(id, { transfer: view, assetState: view.assetState, finalizeId: crypto.randomUUID() });
            }
            if (view.state === 'SEALED') { this.patch(id, { phase: 'waiting', progress: 100 }); this.schedulePoll(); return; }
            if (view.state === 'FINALIZING') {
                if (entry.transfer === null) {
                    this.patch(id, { transfer: view, assetState: view.assetState, phase: 'waiting', progress: 100 });
                } else {
                    const sealed = await this.api.finalize(view.assetId, view.generation, this.find(id)?.finalizeId ?? entry.finalizeId);
                    this.patch(id, { transfer: sealed, assetState: sealed.assetState, phase: 'waiting', progress: 100 });
                }
                this.schedulePoll();
                return;
            }
            if (view.state === 'INITIATING') {
                this.patch(id, { phase: 'waiting', transfer: view });
                this.schedulePoll();
                return;
            }
            if (view.state !== 'OPEN') throw new Error('Сервер не готов принять файл. Повторите действие.');
            if (view.method === 'SINGLE') {
                if (view.url === null || (view.urlExpiresAt !== null && Date.parse(view.urlExpiresAt) < Date.now() + 30_000))
                    view = await this.api.renewSingle(view.assetId, view.generation);
                if (view.url === null) throw new Error('Ссылка на загрузку недоступна. Повторите отправку.');
                await this.api.put(view.url, file, view.headers ?? {}, loaded => this.progress(id, loaded, file.size), abort.signal);
            } else {
                if (view.partSize === null || view.partCount === null) throw new Error('Сервер не указал части файла.');
                const partSize = view.partSize;
                const completed = new Set(await this.api.completedParts(view.assetId, view.generation));
                for (let first = 1; first <= view.partCount; first += PART_URL_BATCH) {
                    if (abort.signal.aborted) return;
                    const count = Math.min(PART_URL_BATCH, view.partCount - first + 1);
                    const urls = await this.api.partUrls(view.assetId, view.generation, first, count);
                    for (const part of urls.parts) {
                        if (completed.has(part.number)) continue;
                        const start = (part.number - 1) * partSize;
                        const body = file.slice(start, start + part.length);
                        const previous = [...completed].reduce((sum, number) => sum + Math.min(partSize, file.size - (number - 1) * partSize), 0);
                        await this.api.put(part.url, body, part.headers,
                            loaded => this.progress(id, previous + loaded, file.size), abort.signal);
                        completed.add(part.number);
                        this.progress(id, previous + body.size, file.size);
                    }
                }
            }
            if (this.find(id)?.phase === 'cancelled') return;
            this.patch(id, { phase: 'finalizing', progress: 100 });
            const sealed = await this.api.finalize(view.assetId, view.generation, entry.finalizeId);
            this.patch(id, { transfer: sealed, assetState: sealed.assetState, phase: 'waiting' });
            this.schedulePoll();
        } catch (error) {
            if (this.find(id)?.phase !== 'cancelled') this.patch(id, {
                phase: 'error', error: uploadError(error)
            });
        } finally { this.aborts.delete(id); }
    }

    private async retryWithFile(id: string, file: File): Promise<void> {
        const entry = this.find(id);
        if (entry === undefined || entry.assetId === null) return;
        const selected = classify(file);
        if (selected?.kind !== entry.kind) {
            this.patch(id, { error: 'Выберите файл того же типа для этого места в материале.' });
            return;
        }
        this.patch(id, { file, name: file.name, transfer: null, phase: 'selected', error: null,
            finalizeId: crypto.randomUUID(), progress: 0 });
        this.drain();
    }

    private recoverDocumentRefs(documentValue: NativeDocument): void {
        const refs = mediaNodes(documentValue.root);
        for (const ref of refs) {
            if (this.entries().some(entry => entry.assetId === ref.assetId)) continue;
            this.entries.update(items => [...items, {
                id: crypto.randomUUID(), name: `Медиа в материале`, kind: ref.kind, file: null,
                phase: 'needs-file', assetId: ref.assetId, transfer: null, progress: 0, error: null,
                intentId: crypto.randomUUID(), finalizeId: crypto.randomUUID(), assetState: null
            }]);
        }
        this.schedulePoll();
    }

    private async refreshStatuses(): Promise<void> {
        this.clearPoll();
        if (document.visibilityState !== 'visible' || !navigator.onLine) return;
        const waiting = this.entries().filter(entry => entry.assetId !== null
            && ['waiting', 'needs-file'].includes(entry.phase));
        if (waiting.length === 0) return;
        const results = await Promise.allSettled(waiting.map(entry => this.api.status(entry.assetId!)));
        if (this.destroyRef.destroyed) return;
        let changed = false;
        results.forEach((result, index) => {
            const entry = waiting[index]!;
            if (result.status !== 'fulfilled') return;
            const view = result.value;
            const phase: QueuePhase = view.assetState === 'READY' ? 'ready'
                : view.assetState === 'REJECTED' || view.assetState === 'DELETED'
                    || view.assetState === 'FAILED_RETRYABLE' ? 'error'
                : view.state === 'ABORTED' || view.state === 'EXPIRED' ? 'needs-file' : 'waiting';
            const resumedPhase = view.state === 'OPEN' && entry.file !== null ? 'selected' : phase;
            if (resumedPhase !== entry.phase || view.assetState !== entry.assetState) changed = true;
            this.patch(entry.id, { transfer: view, assetState: view.assetState, phase: resumedPhase,
                error: resumedPhase === 'error' ? 'Файл не прошёл проверку. Выберите замену.' : null });
        });
        this.drain();
        this.pollDelay = changed ? POLL_INITIAL_MS : Math.min(this.pollDelay * 2, POLL_MAX_MS);
        this.schedulePoll();
    }

    private schedulePoll(): void {
        if (this.destroyRef.destroyed || this.pollTimer !== null
            || document.visibilityState !== 'visible' || !navigator.onLine) return;
        if (!this.entries().some(entry => entry.assetId !== null
            && ['waiting', 'needs-file'].includes(entry.phase))) return;
        this.pollTimer = setTimeout(() => { this.pollTimer = null; void this.refreshStatuses(); }, this.pollDelay);
    }

    private clearPoll(): void {
        if (this.pollTimer !== null) clearTimeout(this.pollTimer);
        this.pollTimer = null;
    }

    private progress(id: string, loaded: number, total: number): void {
        this.patch(id, { progress: Math.min(100, Math.round(loaded / total * 100)) });
    }

    private find(id: string): QueueEntry | undefined { return this.entries().find(entry => entry.id === id); }

    private patch(id: string, change: Partial<QueueEntry>): void {
        this.entries.update(items => items.map(entry => entry.id === id ? { ...entry, ...change } : entry));
    }

    private stopMediaTracks(): void {
        this.stream?.getTracks().forEach(track => track.stop());
        this.stream = null;
        this.recorder = null;
        this.recording.set(false);
    }
}

function formatBytes(bytes: number): string {
    if (bytes < 1024 * 1024) return `${bytes} Б`;
    if (bytes >= 1024 * 1024 * 1024 && bytes % (1024 * 1024 * 1024) === 0) {
        return `${bytes / (1024 * 1024 * 1024)} ГиБ`;
    }
    if (bytes % (1024 * 1024) === 0) return `${bytes / (1024 * 1024)} МиБ`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} МиБ`;
}

function classify(file: File): { kind: NativeMediaKind; mime: string } | null {
    if (file.size < 1) return null;
    const extension = file.name.split('.').pop()?.toLowerCase() ?? '';
    const known = MIME_BY_EXTENSION[extension];
    if (known === undefined) return null;
    if (extension === 'webm' && file.type.startsWith('audio/webm')) {
        return { kind: 'audio', mime: file.type };
    }
    if (file.type && file.type !== 'application/octet-stream'
        && file.type.split(';')[0] !== known.mime.split(';')[0]
        && !(extension === 'm4a' && file.type === 'audio/x-m4a')) return null;
    return { kind: known.kind, mime: file.type && file.type !== 'application/octet-stream' ? file.type : known.mime };
}

function uploadError(error: unknown): string {
    if (!navigator.onLine || error instanceof HttpErrorResponse && error.status === 0) {
        return 'Нет соединения. Файл и место в материале сохранены в этой вкладке; повторите после подключения.';
    }
    if (error instanceof HttpErrorResponse) {
        if (error.status === 413 || error.status === 429 || error.status === 507) {
            return 'Хранилище или квота сейчас не позволяют загрузку. Выберите меньший файл или повторите позже.';
        }
        if (error.status === 415 || error.status === 422) {
            return 'Этот формат не принят. Выберите другой файл для того же места в материале.';
        }
        if (error.status === 403 || error.status === 409) {
            return 'Ссылка или попытка загрузки устарела. Повторите отправку файла.';
        }
    }
    return 'Не удалось завершить загрузку. Повторите отправку файла.';
}

function mediaNodes(root: NativeNode): readonly { kind: NativeMediaKind; assetId: string }[] {
    const result: { kind: NativeMediaKind; assetId: string }[] = [];
    const walk = (node: NativeNode): void => {
        if (node.version !== 1) return;
        if (node.type === 'image' || node.type === 'audio' || node.type === 'video') {
            const assetId = node.attrs['assetId'];
            if (typeof assetId === 'string') result.push({ kind: node.type, assetId });
        }
        for (const child of node.content ?? []) walk(child);
    };
    walk(root);
    return result;
}
