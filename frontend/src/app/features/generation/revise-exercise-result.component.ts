import {
    ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, input, signal, untracked
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { ExercisePreviewHostComponent } from '../authoring/exercise-preview-host.component';
import { PreviewPresentation } from '../authoring/exercise-preview.models';
import { EditHistoryComponent } from './edit-history.component';
import { ExerciseProposal, proposalPresentation, readProposal } from './exercise-proposal';
import { exerciseTextLines } from './exercise-text';
import { mechanicName } from './exercise-builder';
import { artifactStatus, exerciseFailureReason, failureNote, turnFailureReason, voiceRedoneText } from './generation-view';
import { ArtifactSummary, ArtifactTurn, SessionState, SpeechVoice, allows, isApprovable } from './generation.models';
import { DiffParagraph, diffLines, hasChanges } from './word-diff';
import { DetailEntry, WorkshopSessionStore } from './workshop-session.store';

type DiffState =
    | { readonly phase: 'idle' }
    | { readonly phase: 'loading' }
    | { readonly phase: 'error' }
    | { readonly phase: 'ready'; readonly paragraphs: readonly DiffParagraph[]; readonly changed: boolean };

/**
 * The result of a `REVISE_EXERCISE` session (AI-16, #294): the exercise as Мнема changed it, playable in the same compact preview as a
 * proposal of a batch, with the card that decides. The text change is a word diff of what the learner reads and the answers; a voice
 * change is a chip, «Озвучено заново: мужской» (AI-09: a redo makes new audio and the exercise revision uses it, so the preview plays the new
 * recording). «Оставить» saves the next revision of
 * the same exercise (the old one stays in the history), «Вернуть» goes back to the version the revision started from, «Ещё раз» asks again.
 */
@Component({
    selector: 'app-revise-exercise-result',
    imports: [RouterLink, ExercisePreviewHostComponent, EditHistoryComponent],
    templateUrl: './revise-exercise-result.component.html',
    styleUrls: ['../authoring/authoring-page.css', './revise-result.css'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ReviseExerciseResultComponent {
    readonly artifact = input.required<ArtifactSummary>();
    readonly deckId = input.required<string>();
    readonly sessionState = input.required<SessionState>();
    readonly entry = input<DetailEntry | null>(null);
    readonly busy = input(false);

    protected readonly store = inject(WorkshopSessionStore);
    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);
    private readonly injector = inject(Injector);
    private diffToken = 0;
    private readonly presentations = new Map<string, PreviewPresentation>();

    protected readonly headingId = 'revision-heading';
    protected readonly status = computed(() => artifactStatus(this.artifact()));
    protected readonly detail = computed(() => this.entry()?.detail ?? null);
    protected readonly proposal = computed<ExerciseProposal | null>(() => {
        const detail = this.detail();
        return detail === null ? null : readProposal(detail);
    });
    protected readonly presentation = computed<PreviewPresentation | null>(() => {
        const proposal = this.proposal();
        if (proposal === null) return null;
        const key = `${proposal.artifactId}:${proposal.revisionId}`;
        let held = this.presentations.get(key);
        if (held === undefined) { held = proposalPresentation(proposal); this.presentations.set(key, held); }
        return held;
    });
    protected readonly title = computed(() => {
        const proposal = this.proposal();
        return proposal === null ? this.artifact().title || 'Упражнение' : `${mechanicName(proposal.mechanic)} · ${proposal.objectiveTitle || this.artifact().title}`;
    });
    protected readonly originalId = computed(() => this.detail()?.revisions[0]?.revisionId ?? null);
    protected readonly shownIsCurrent = computed(() => {
        const artifact = this.artifact();
        return artifact.currentRevisionId !== null && this.detail()?.currentRevisionId === artifact.currentRevisionId;
    });
    protected readonly changedFromOriginal = computed(() => this.originalId() !== null && this.artifact().currentRevisionId !== this.originalId());
    protected readonly writing = computed(() => ['QUEUED', 'GENERATING', 'REVISING'].includes(this.artifact().state));
    /** The request as the session echoes it: the text instruction and the voice. */
    protected readonly requestText = computed(() => this.store.session()?.spec.instruction ?? null);
    protected readonly requestVoice = computed(() => this.store.session()?.spec.voice ?? null);
    /** The last turns that ran, by kind: «Ещё раз» repeats the text rewrite when there was one, else the voice redo. */
    protected readonly textTurn = computed(() => this.lastOf('FREE'));
    protected readonly voiceTurn = computed(() => this.lastOf('AUDIO_REGENERATE'));
    private readonly lastTurn = computed(() => this.detail()?.turns.at(-1) ?? null);
    /** The voice of the revision on screen: what its audio slots record (a revert to the original goes back to none). */
    protected readonly voice = computed<SpeechVoice | null>(() => this.detail()?.mediaSlots.find(slot => slot.voice !== null)?.voice ?? null);
    /** The audio did not change, only the voice was recorded: the same assets as in the exercise the revision started from. */
    protected readonly failure = computed(() => {
        const turn = this.lastTurn();
        if (turn === null || (turn.status !== 'FAILED' && turn.status !== 'CANCELLED') || this.artifact().state !== 'PROPOSED') return null;
        if (turn.status === 'CANCELLED') return 'Правка остановлена.';
        return turn.action === 'AUDIO_REGENERATE' ? 'Не удалось сменить голос.' : turnFailureReason(turn.errorCode);
    });
    protected readonly canApprove = computed(() => allows(this.sessionState(), this.artifact().state, 'approveArtifact') && isApprovable(this.artifact()));
    protected readonly canReject = computed(() => allows(this.sessionState(), this.artifact().state, 'rejectArtifact'));
    protected readonly canUndoReject = computed(() => allows(this.sessionState(), this.artifact().state, 'undoRejectArtifact'));
    protected readonly canRevert = computed(() => this.shownIsCurrent() && allows(this.sessionState(), this.artifact().state, 'revertArtifact'));
    protected readonly canAskAgain = computed(() => this.shownIsCurrent() && this.sessionState() !== 'CANCELLED'
        && allows(this.sessionState(), this.artifact().state, 'editArtifact') && this.repeatable() !== null);
    protected readonly published = computed(() => {
        const ref = this.artifact().publishedRef;
        return ref?.kind === 'EXERCISE' ? ref : null;
    });
    protected readonly failureText = computed(() => exerciseFailureReason(this.artifact().errorCode));
    protected readonly failureNote = computed(() => failureNote(this.artifact().errorCode));
    protected readonly diff = signal<DiffState>({ phase: 'idle' });
    protected readonly diffKey = computed(() => {
        const original = this.originalId();
        const proposal = this.proposal();
        return !this.shownIsCurrent() || original === null || proposal === null || proposal.revisionId === original ? null : `${original}:${proposal.revisionId}`;
    });
    protected readonly approveWait = computed(() => this.artifact().state === 'PROPOSED' && !this.shownIsCurrent() ? 'Упражнение обновилось. Загружаем новую версию…' : null);
    /** «Озвучено заново: мужской» when the revision on screen was voiced again: the exercise has new audio, and the preview plays it. */
    protected readonly voiceChip = computed(() => {
        const voice = this.voice();
        return voice === null || !this.changedFromOriginal() ? null : voiceRedoneText(voice);
    });
    /** «Ещё раз» of a request for the text and the voice repeats the text only (the voice is already recorded). */
    protected readonly repeatNote = computed(() => this.textTurn() !== null && this.voiceTurn() !== null && this.canAskAgain()
        ? '«Ещё раз» повторит только правку текста: голос уже записан.' : null);

    constructor() {
        effect(() => {
            const key = this.diffKey();
            untracked(() => {
                if (key === null) { this.diffToken++; this.diff.set({ phase: 'idle' }); return; }
                void this.compare();
            });
        });
    }

    protected async keep(): Promise<void> {
        if (this.busy() || !this.canApprove() || !this.shownIsCurrent()) return;
        if (await this.store.approve(this.artifact().artifactId)) this.focusHeading();
    }

    protected async giveBack(): Promise<void> {
        const original = this.originalId();
        if (this.busy() || !this.canRevert() || original === null || !this.changedFromOriginal()) return;
        if (await this.store.revert(this.artifact().artifactId, original)) this.focusHeading();
    }

    /** The request asked again (a command of its own, named by the last turn and the revision shown, so a lost answer is repeated, not charged twice). */
    protected async again(): Promise<void> {
        const turn = this.repeatable();
        if (this.busy() || !this.canAskAgain() || turn === null) return;
        const outcome = await this.store.edit(this.artifact().artifactId, turn.action === 'AUDIO_REGENERATE'
            ? { action: 'AUDIO_REGENERATE', nodeIds: [], anchorBefore: null, anchorAfter: null, exercise: true, voice: turn.voice, againOf: this.lastTurn()?.turnId ?? 'first' }
            : { action: 'FREE', nodeIds: [], anchorBefore: null, anchorAfter: null, exercise: true, instruction: turn.instruction,
                againOf: this.lastTurn()?.turnId ?? 'first' });
        if (!outcome.ok && !outcome.aborted) this.store.notify(outcome.message);
        else if (outcome.ok) this.focusHeading();
    }

    protected async reject(): Promise<void> {
        if (this.busy() || !this.canReject()) return;
        if (await this.store.reject(this.artifact().artifactId)) this.focusHeading();
    }

    protected async undoReject(): Promise<void> {
        if (this.busy() || !this.canUndoReject()) return;
        if (await this.store.undoReject(this.artifact().artifactId)) this.focusHeading();
    }

    protected revertTo(revisionId: string): void {
        if (this.busy() || !this.canRevert()) return;
        void this.store.revert(this.artifact().artifactId, revisionId);
    }

    protected reload(): void {
        this.store.loadDetail(this.artifact().artifactId);
    }

    focusHeading(): void {
        afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>('h2')?.focus(), { injector: this.injector });
    }

    private lastOf(action: ArtifactTurn['action']): ArtifactTurn | null {
        return this.detail()?.turns.filter(turn => turn.action === action).at(-1) ?? null;
    }

    /** The turn «Ещё раз» repeats; `null` when there is nothing to repeat (the text instruction and the voice are both missing). */
    private repeatable(): { readonly action: 'FREE' | 'AUDIO_REGENERATE'; readonly instruction: string | null; readonly voice: SpeechVoice | null } | null {
        const text = this.textTurn()?.instruction ?? this.requestText();
        if (text !== null && text.trim().length > 0) return { action: 'FREE', instruction: text, voice: null };
        const voice = this.voiceTurn()?.voice ?? this.requestVoice();
        return voice === null ? null : { action: 'AUDIO_REGENERATE', instruction: null, voice };
    }

    /** The words of the exercise as it was and as it is, and whether its audio is the one it had. Read once per pair of revisions. */
    private async compare(): Promise<void> {
        const token = ++this.diffToken;
        const original = this.originalId();
        const proposal = this.proposal();
        if (original === null || proposal === null) return;
        this.diff.set({ phase: 'loading' });
        const before = await this.store.loadRevisionDetail(this.artifact().artifactId, original);
        if (token !== this.diffToken) return;
        const old = before === null ? null : readProposal(before);
        if (old === null) { this.diff.set({ phase: 'error' }); return; }
        const paragraphs = diffLines(exerciseTextLines(old.exercise, old.quotes), exerciseTextLines(proposal.exercise, proposal.quotes), 'ru');
        this.diff.set({ phase: 'ready', paragraphs, changed: hasChanges(paragraphs) });
    }
}
