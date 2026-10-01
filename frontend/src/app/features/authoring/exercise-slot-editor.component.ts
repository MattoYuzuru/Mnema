import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, inject, input, model, signal, viewChild } from '@angular/core';

import { youtubeVideoId } from '../../content/youtube-video-id';
import { MnemaSelectComponent, MnemaSelectOption } from '../../core/controls/mnema-select.component';
import { AuthoringBlock, AuthoringBlockKind, MediaBlockKind, SLOT_PROFILES, SlotSpec } from '../../content/exercise/exercise-content.models';
import { blockProblem } from '../../content/exercise/exercise-content.parse';
import { SlotContext, materialText } from './exercise-draft';
import { NativeMediaKind } from './native-media-upload.api';
import { NativeMediaUploadComponent } from './native-media-upload.component';

const KIND_LABELS: Readonly<Record<AuthoringBlockKind, string>> = {
    TEXT: 'Текст', MATERIAL: 'Фрагмент материала', IMAGE: 'Изображение', AUDIO: 'Аудио', VIDEO: 'Видео', YOUTUBE: 'YouTube'
};
const MEDIA_KINDS: Readonly<Record<NativeMediaKind, MediaBlockKind>> = { image: 'IMAGE', audio: 'AUDIO', video: 'VIDEO' };
const MEDIA = new Set<AuthoringBlockKind>(['IMAGE', 'AUDIO', 'VIDEO']);
const MEDIA_BLOCKS: Readonly<Record<NativeMediaKind, MediaBlockKind>> = { image: 'IMAGE', audio: 'AUDIO', video: 'VIDEO' };

/**
 * Typed editor of one content slot: an ordered list of text, material, media and YouTube blocks.
 * The slot's profile decides which kinds and how many blocks are allowed; the limits are shown next to
 * the field and nothing is ever silently truncated. Media comes from the shared upload/record component,
 * so a recording started here can only ever be inserted into this slot.
 */
@Component({
    selector: 'app-exercise-slot-editor',
    imports: [MnemaSelectComponent, NativeMediaUploadComponent],
    templateUrl: './exercise-slot-editor.component.html',
    styleUrl: './exercise-fields.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ExerciseSlotEditorComponent {
    readonly blocks = model.required<readonly AuthoringBlock[]>();
    readonly spec = input.required<SlotSpec>();
    readonly label = input.required<string>();
    readonly context = input.required<SlotContext>();
    readonly idPrefix = input.required<string>();
    /** Slot-level message from validation. */
    readonly error = input<string | null>(null);
    /** Highlights invalid blocks; set after the first failed save so empty new blocks stay calm. */
    readonly showProblems = input(false);
    readonly hint = input<string | null>(null);

    readonly pickerMounted = signal(false);
    readonly pickerOpen = signal(false);
    /** Which kind of file the one shared picker offers right now. */
    readonly pickerKind = signal<NativeMediaKind>('image');
    readonly notice = signal<string | null>(null);
    readonly recordMode = signal(false);

    readonly rules = computed(() => SLOT_PROFILES[this.spec().profile]);
    readonly limits = computed(() => {
        const spec = this.spec();
        const rules = this.rules();
        const count = spec.min === spec.max ? `${spec.max}` : `${spec.min}–${spec.max}`;
        return `Блоков: ${count}. Текст — до ${rules.textLimit} знаков в блоке.`
            + (rules.oneTextAndOneMedia ? ' Не больше одного текста и одного изображения, аудио или видео.' : '');
    });
    readonly projectionOptions = computed<readonly MnemaSelectOption[]>(() => [
        { value: '', label: 'Выберите фрагмент материала' },
        ...this.context().projections.map(projection => ({ value: projection.nodeId, label: projection.label }))
    ]);
    readonly canAddYoutube = computed(() => this.rules().kinds.includes('YOUTUBE'));

    private readonly upload = viewChild(NativeMediaUploadComponent);
    private opener: HTMLElement | null = null;
    private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);

    kindLabel(kind: AuthoringBlockKind): string { return KIND_LABELS[kind]; }

    canAdd(kind: AuthoringBlockKind): boolean {
        const blocks = this.blocks();
        if (blocks.length >= this.spec().max) return false;
        if (!this.rules().oneTextAndOneMedia) return true;
        const textual = kind === 'TEXT' || kind === 'MATERIAL';
        return !blocks.some(block => textual ? block.kind === 'TEXT' || block.kind === 'MATERIAL' : MEDIA.has(block.kind));
    }

    /** Whether the profile allows this kind and the slot still has room for one more media block. */
    canAddMedia(kind: NativeMediaKind): boolean {
        const block = MEDIA_BLOCKS[kind];
        return this.rules().kinds.includes(block) && this.canAdd(block);
    }

    pickerShows(kind: NativeMediaKind, record = false): boolean {
        return this.pickerOpen() && this.pickerKind() === kind && this.recordMode() === record;
    }

    problem(block: AuthoringBlock): string | null {
        return this.showProblems() ? blockProblem(block, this.spec().profile) : null;
    }

    /** Resolved text of a material block, or null when its fragment is not in the loaded revision. */
    resolved(block: AuthoringBlock): string | null { return materialText(block, this.context()); }

    isStale(block: AuthoringBlock): boolean {
        return block.kind === 'MATERIAL' && block.memberKey === this.context().memberKey
            && block.itemRevisionId !== this.context().itemRevisionId;
    }

    add(kind: 'TEXT' | 'MATERIAL' | 'YOUTUBE'): void {
        if (!this.canAdd(kind)) return;
        const context = this.context();
        const block: AuthoringBlock = kind === 'TEXT' ? { kind, text: '' }
            : kind === 'MATERIAL' ? { kind, memberKey: context.memberKey, itemRevisionId: context.itemRevisionId, nodeId: '' }
            : { kind, videoId: '', title: '' };
        this.blocks.update(values => [...values, block]);
        this.focusBlock(this.blocks().length - 1);
    }

    /**
     * Opens the shared picker for one kind of file, or closes it when the same button is pressed again. With
     * `record` the recorder starts right away: that click is the explicit request for the microphone.
     */
    openPicker(kind: NativeMediaKind, opener: HTMLElement, record = false): void {
        this.notice.set(null);
        if (this.pickerShows(kind, record)) { this.closePicker(); return; }
        this.opener = opener;
        this.pickerKind.set(kind);
        this.recordMode.set(record);
        this.pickerMounted.set(true);
        this.pickerOpen.set(true);
        afterNextRender({ write: () => {
            if (this.destroyRef.destroyed) return;
            this.host.nativeElement.querySelector<HTMLElement>('.media-picker')?.focus();
            if (record) void this.upload()?.startRecording();
        } }, { injector: this.injector });
    }

    /** Closing returns focus to the button that opened the picker. */
    closePicker(): void {
        this.pickerOpen.set(false);
        this.recordMode.set(false);
        const opener = this.opener;
        this.opener = null;
        if (opener?.isConnected) opener.focus();
    }

    /** Called only by this slot's own picker, so a late upload or recording can never land in another slot. */
    onAsset(selection: { kind: NativeMediaKind; assetId: string }): void {
        const kind = MEDIA_KINDS[selection.kind];
        if (!this.rules().kinds.includes(kind)) { this.notice.set('Этот вид файла здесь недоступен.'); return; }
        if (!this.canAdd(kind)) {
            this.notice.set(this.rules().oneTextAndOneMedia
                ? 'Здесь уже есть изображение, аудио или видео. Удалите его, чтобы выбрать другое.'
                : 'Достигнут предел блоков.');
            return;
        }
        const block: AuthoringBlock = kind === 'IMAGE' ? { kind, assetId: selection.assetId, alt: '' }
            : { kind, assetId: selection.assetId, title: kind === 'AUDIO' ? 'Аудио' : 'Видео' };
        this.blocks.update(values => [...values, block]);
        this.notice.set(null);
        this.pickerOpen.set(false);
        this.recordMode.set(false);
        this.opener = null;
        this.focusBlock(this.blocks().length - 1);
    }

    patch(index: number, change: Partial<Record<string, string | undefined>>): void {
        this.blocks.update(values => values.map((block, position) => {
            if (position !== index) return block;
            const next: Record<string, unknown> = { ...block, ...change };
            for (const key of Object.keys(change)) if (change[key] === undefined) delete next[key];
            return next as unknown as AuthoringBlock;
        }));
    }

    setTranscript(index: number, value: string): void { this.patch(index, { transcript: value === '' ? undefined : value }); }

    setYoutube(index: number, raw: string): void { this.patch(index, { videoId: youtubeVideoId(raw) ?? raw.trim() }); }

    move(index: number, delta: -1 | 1): void {
        const target = index + delta;
        if (target < 0 || target >= this.blocks().length) return;
        this.blocks.update(values => {
            const copy = [...values];
            [copy[index], copy[target]] = [copy[target], copy[index]];
            return copy;
        });
        this.focusBlock(target, '[data-move]');
    }

    remove(index: number): void {
        this.blocks.update(values => values.filter((_, position) => position !== index));
        this.focusBlock(Math.min(index, this.blocks().length - 1), '[data-add]');
    }

    private focusBlock(index: number, selector = 'textarea, input, [role="combobox"]'): void {
        afterNextRender({ write: () => {
            if (this.destroyRef.destroyed) return;
            const row = this.host.nativeElement.querySelector<HTMLElement>(`[data-block="${index}"]`);
            (row?.querySelector<HTMLElement>(selector) ?? this.host.nativeElement.querySelector<HTMLElement>('[data-add]'))?.focus();
        } }, { injector: this.injector });
    }
}
