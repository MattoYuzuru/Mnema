import {
    ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, input, output, untracked
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { NativeDocument, NativeNode } from '../../content/native-document';
import { NativeDocumentRendererComponent } from '../../content/rendering/native-document-renderer.component';
import { NativeMediaSurfaceComponent } from '../../content/rendering/native-media-surface.component';
import {
    NBSP, artifactStatus, failureNote, failureReason, positionLabel, slotCaption
} from './generation-view';
import { ArtifactSummary, MediaSlot, SessionState, allows, isApprovable, isRetryable, previewDocument } from './generation.models';
import { Arrival, DraftBlocks } from './workshop-events';
import type { DetailEntry } from './workshop-session.store';

/** How long a block takes to appear (`events.json` clientRule: an ink animation of at most 600 ms). */
export const ARRIVE_MS = 600;

/** One slot still on its way, as the paper frame that holds its place. */
export interface SlotFrame { readonly key: string; readonly kind: 'AUDIO' | 'IMAGE' | 'VIDEO'; readonly caption: string; readonly failed: boolean; }

const MEDIA_BLOCKS = ['image', 'audio', 'video'];

function isTextBlock(block: NativeNode): boolean {
    return !MEDIA_BLOCKS.includes(block.type);
}

function reducedMotion(): boolean {
    return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

/**
 * One material of the batch, in whatever state it is: waiting, being written (the draft blocks grow in place), proposed
 * (the stored revision), failed (with the reason and a retry), rejected (with undo), approved or handed to the editor.
 * It only presents and reports what the user chose; the page owns the commands.
 *
 * Blocks that arrive while it is written appear with the `is-arriving` ink animation (at most 600 ms), never
 * scrolled to and never focused; with reduced motion they are simply there. The article is `aria-busy` while it is
 * being written and is not a live region: only the summary above the pager is announced.
 */
@Component({
    selector: 'app-proposal-view',
    imports: [RouterLink, NativeDocumentRendererComponent, NativeMediaSurfaceComponent],
    templateUrl: './proposal-view.component.html',
    styleUrls: ['../authoring/authoring-page.css', './proposal-view.component.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProposalViewComponent {
    readonly artifact = input.required<ArtifactSummary>();
    readonly index = input.required<number>();
    readonly total = input.required<number>();
    readonly deckId = input.required<string>();
    readonly sessionState = input.required<SessionState>();
    readonly entry = input<DetailEntry | null>(null);
    readonly draft = input<DraftBlocks | null>(null);
    readonly arrival = input<Arrival | null>(null);
    /** A command on this artifact is in flight. */
    readonly busy = input(false);
    /** The editor draft made by the hand-off of this material, when this page made it. */
    readonly handoffDraftId = input<string | null>(null);

    readonly approve = output<void>();
    readonly reject = output<void>();
    readonly undo = output<void>();
    readonly handoff = output<void>();
    readonly retry = output<void>();
    readonly reload = output<void>();

    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private readonly timers = new Set<ReturnType<typeof setTimeout>>();
    /** The token of the arrival that was current when the view first rendered: it arrived before, so it is not animated. */
    private seenToken: number | null = null;

    protected readonly headingId = 'proposal-heading';
    protected readonly status = computed(() => artifactStatus(this.artifact()));
    protected readonly position = computed(() => positionLabel(this.index(), this.total()));
    protected readonly detail = computed(() => this.entry()?.detail ?? null);
    protected readonly document = computed<NativeDocument | null>(() => {
        const payload = this.detail()?.revision?.payload;
        return payload?.kind === 'NATIVE_DOCUMENT' ? payload.document : null;
    });
    protected readonly isExercise = computed(() => this.artifact().targetKind === 'EXERCISE');
    /** The draft without its media blocks: their place is held by the paper frames below, and nothing there pretends to play. */
    protected readonly preview = computed(() => {
        const blocks = (this.draft()?.blocks ?? []).filter(isTextBlock);
        return blocks.length > 0 ? previewDocument(blocks) : null;
    });
    /** The document of the revision the summary names is on screen: approving it is approving what was read. */
    protected readonly shownIsCurrent = computed(() => {
        const artifact = this.artifact();
        return artifact.currentRevisionId !== null && this.detail()?.currentRevisionId === artifact.currentRevisionId;
    });
    protected readonly writing = computed(() => {
        const state = this.artifact().state;
        return state === 'QUEUED' || state === 'GENERATING' || state === 'REVISING';
    });
    protected readonly canApprove = computed(() => allows(this.sessionState(), this.artifact().state, 'approveArtifact')
        && isApprovable(this.artifact()));
    protected readonly canReject = computed(() => allows(this.sessionState(), this.artifact().state, 'rejectArtifact'));
    protected readonly canHandoff = computed(() => allows(this.sessionState(), this.artifact().state, 'handoffArtifact')
        && !this.isExercise());
    protected readonly canUndo = computed(() => allows(this.sessionState(), this.artifact().state, 'undoRejectArtifact'));
    protected readonly canRetry = computed(() => allows(this.sessionState(), this.artifact().state, 'retryArtifact')
        && isRetryable(this.artifact()));
    protected readonly failure = computed(() => failureReason(this.artifact().errorCode));
    protected readonly failureNote = computed(() => failureNote(this.artifact().errorCode));
    protected readonly published = computed(() => {
        const ref = this.artifact().publishedRef;
        return ref?.kind === 'ITEM' ? ref : null;
    });
    /** Why «Одобрить» waits, in words; `null` when it does not. */
    protected readonly approveWait = computed(() => {
        const artifact = this.artifact();
        if (artifact.state !== 'PROPOSED' || this.isExercise()) return null;
        const { total, ready, failed } = artifact.mediaSlotCounts;
        if (ready !== total) {
            return failed > 0 ? 'Не все медиа удалось подготовить. Правьте материал сами: одобрить можно, когда всё готово.'
                : 'Медиа ещё готовятся. Одобрить можно, когда всё будет готово.';
        }
        return this.shownIsCurrent() ? null : 'Материал обновился. Загружаем новую версию…';
    });
    /** Frames of the media that is not ready yet: aspect-ratio boxes, so nothing shifts when it arrives. */
    protected readonly slotFrames = computed<readonly SlotFrame[]>(() => {
        const artifact = this.artifact();
        const slots: readonly MediaSlot[] = this.detail()?.mediaSlots ?? [];
        const open = slots.filter(slot => slot.state !== 'READY' && slot.state !== 'REMOVED');
        if (open.length > 0) {
            return open.map(slot => ({ key: slot.slotKey, kind: slot.kind, caption: slotCaption(slot.kind, slot.state),
                failed: slot.state === 'FAILED' }));
        }
        const { total, ready, failed } = artifact.mediaSlotCounts;
        const missing = total - ready;
        if (slots.length > 0 || missing <= 0) return [];
        // Only the counts are known (the draft is still being written): one frame per slot that is not ready.
        return Array.from({ length: missing }, (_, position) => ({ key: `slot-${position}`, kind: 'IMAGE' as const,
            caption: position < failed ? 'Не удалось подготовить медиа' : 'Медиа в работе', failed: position < failed }));
    });
    protected readonly nbsp = NBSP;

    constructor() {
        // Mark the blocks that just arrived; `seenToken` makes a re-read of the same arrival a no-op.
        effect(() => {
            const arrival = this.arrival();
            if (this.seenToken === null) { this.seenToken = arrival?.token ?? -1; return; }
            if (arrival === null || arrival.token === this.seenToken) return;
            this.seenToken = arrival.token;
            const shownId = untracked(() => this.artifact().artifactId);
            if (arrival.artifactId !== shownId) return;
            // The rendered blocks leave out the media blocks: count only the text blocks before the first new one.
            const shownFrom = (untracked(() => this.draft())?.blocks ?? []).slice(0, arrival.fromIndex).filter(isTextBlock).length;
            afterNextRender(() => this.reveal(shownFrom), { injector: this.injector });
        });
        inject(DestroyRef).onDestroy(() => this.timers.forEach(timer => clearTimeout(timer)));
    }

    /** Moves focus to the title of the material: after the user approved the previous one, so focus does not fall to the page. */
    focusHeading(): void {
        this.element.nativeElement.querySelector<HTMLElement>('h2')?.focus();
    }

    private reveal(fromIndex: number): void {
        if (reducedMotion()) return;
        const blocks = this.element.nativeElement.querySelectorAll<HTMLElement>('.draft .native-document > *');
        blocks.forEach((block, position) => {
            if (position < fromIndex) return;
            block.classList.add('is-arriving');
            const timer = setTimeout(() => { block.classList.remove('is-arriving'); this.timers.delete(timer); }, ARRIVE_MS);
            this.timers.add(timer);
        });
    }
}
