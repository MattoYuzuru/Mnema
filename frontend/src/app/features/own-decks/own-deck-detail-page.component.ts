import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { AuthoringApiService } from '../authoring/authoring-api.service';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService } from '../authoring/capabilities-api.service';

import { DeckMetadata, DeckVisibility, OwnDeck, validateDeckMetadata } from './own-deck.models';
import { AccessLevelComponent } from '../../shared/access-level.component';
import { ActionMenuComponent, ActionMenuItem } from '../../shared/action-menu.component';
import { COPY_TEXT } from '../../shared/copy-text';
import { CopyOriginComponent } from '../../shared/copy-origin.component';
import { CopyStatusComponent } from '../../shared/copy-status.component';
import { sizeLine } from '../../shared/public-deck-card';
import { PublicationBlockComponent } from './publication/publication-block.component';
import { DeckDescriptionComponent } from './deck-description.component';
import { OwnDecksApiService } from './own-decks-api.service';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { DeckHubApiService } from './hub/deck-hub-api.service';
import { DeckInsightsComponent, InsightsState } from './hub/deck-insights.component';
import { DeckMaterialsComponent } from './hub/deck-materials.component';
import { DeckWorkshopsComponent } from '../generation/deck-workshops.component';
import { DeckRecoveryContext, OwnDeckRecoveryService } from './own-deck-recovery.service';
import { ForkApiService } from './fork/fork-api.service';
import { FORK_POLL_LIMIT, FORK_POLL_MIN_GAP_MS, forkPollDelay } from './fork/fork-poll';
import { ForkProgress, forkProgressOf } from './fork/fork.models';
import {
    OwnDecksStore,
    DeckMutationState,
    canStartNewMutation,
    deckFailureMessage,
    mayRetrySameCommand,
    mutationLocksDraft,
    recoverablePendingCommand
} from './own-decks.store';

@Component({
    selector: 'app-own-deck-detail-page',
    imports: [DatePipe, ReactiveFormsModule, RouterLink, DeckDescriptionComponent, HoldToDeleteButtonComponent,
        DeckInsightsComponent, DeckMaterialsComponent, DeckWorkshopsComponent, AccessLevelComponent, PublicationBlockComponent, ActionMenuComponent,
        CopyOriginComponent, CopyStatusComponent],
    providers: [OwnDecksStore],
    templateUrl: './own-deck-detail-page.component.html',
    styleUrl: './own-decks-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
/**
 * The Deck hub: what the Deck is (title and rendered description), what to do next (actions), how it stands
 * (statistics) and its materials. The metadata form and «Удалить колоду» sit behind «Изменить». Detail, statistics and
 * the material list are three independent requests: a failed one degrades only its own block.
 */
export class OwnDeckDetailPageComponent {
    readonly store = inject(OwnDecksStore);
    readonly form = new FormGroup({
        title: new FormControl('', { nonNullable: true }),
        description: new FormControl('', { nonNullable: true })
    });
    readonly draft = signal<DeckMetadata>({ title: '', description: '' });
    readonly validation = computed(() => validateDeckMetadata(this.draft()));
    readonly submitted = signal(false);
    readonly recovered = signal(false);
    readonly captureCount = signal<number | null>(null);
    readonly deleting = signal(false);
    readonly deleteError = signal<string | null>(null);
    /** The metadata form is open. It opens on its own when a restored draft or a save problem needs it. */
    readonly editing = signal(false);
    readonly insights = signal<InsightsState>({ phase: 'loading' });
    /** The server offers AI exercise generation; false until it says so (fail closed). */
    readonly generationAvailable = signal(false);
    /** The real access level once the publication block has read it; until then the deck's own `visibility`. */
    readonly publishedLevel = signal<DeckVisibility | null>(null);
    /** Bumped when the deck's head revision changes (a saved description), so the publication block re-reads. */
    readonly publicationToken = signal(0);
    readonly editButton = viewChild<ElementRef<HTMLButtonElement>>('editButton');
    readonly publication = viewChild(PublicationBlockComponent);
    /** The copy state of this deck (`null` for an ordinary deck, and while the first read is on its way). */
    readonly fork = computed(() => this.store.detailState().deck?.fork ?? null);
    /** `preparing` or `failed` while the copy is not ready: the hub then shows the state instead of the content. */
    readonly copyState = computed<Exclude<ForkProgress, 'ready'> | null>(() => {
        const fork = this.fork();
        const progress = fork === null ? 'ready' : forkProgressOf(fork);
        return progress === 'ready' ? null : progress;
    });
    /** «40 материалов · 12 упражнений»: what the copy will hold (the counts of the deck are those being prepared). */
    readonly copySummary = computed(() => {
        const deck = this.store.detailState().deck;
        return deck === null || deck.memberCount + deck.exerciseCount === 0 ? null : sizeLine(deck.memberCount, deck.exerciseCount);
    });
    /** The «⋯» menu of the deck: only actions that work. */
    readonly menuItems = computed<readonly ActionMenuItem[]>(() => {
        const fork = this.fork();
        return [
            { id: 'access', label: 'Доступ…', glyph: 'lock' },
            ...(fork !== null && !fork.detached ? [{ id: 'detach', label: COPY_TEXT.detach, glyph: 'copy-branch' } as const] : [])
        ];
    });
    /** Said once to assistive technology when the copy changes state (a polite live region; nothing is announced by polling itself). */
    readonly liveMessage = signal('');
    /** The page stopped checking for the end of the preparation by itself. */
    readonly pollStalled = signal(false);
    readonly retrying = signal(false);
    readonly retryError = signal<string | null>(null);
    /** «Отвязать от автора?» is asked inline, in the page: the house pattern has no dialog for it. */
    readonly confirmingDetach = signal(false);
    readonly detaching = signal(false);
    readonly detachError = signal<string | null>(null);
    protected readonly copyText = COPY_TEXT;
    readonly materials = viewChild(DeckMaterialsComponent);
    readonly failureMessage = deckFailureMessage;
    readonly mayRetrySameCommand = mayRetrySameCommand;
    readonly mutationLocksDraft = mutationLocksDraft;
    readonly canStartNewMutation = canStartNewMutation;
    readonly canDelete = (mutation: DeckMutationState): boolean => mutation.phase === 'completed'
        || canStartNewMutation(mutation);

    private readonly route = inject(ActivatedRoute);
    private readonly authoring = inject(AuthoringApiService);
    private readonly decksApi = inject(OwnDecksApiService);
    private readonly hubApi = inject(DeckHubApiService);
    private readonly forks = inject(ForkApiService);
    private readonly capabilities = inject(CapabilitiesApiService);
    private readonly router = inject(Router);
    private readonly injector = inject(Injector);
    private readonly destroyRef = inject(DestroyRef);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    private readonly recovery = inject(OwnDeckRecoveryService);
    private recoveryContext: DeckRecoveryContext | null = null;
    private recoveredDeckId: string | null = null;
    private loadedRevisionId: string | null = null;
    private insightsLoad: Subscription | null = null;
    private insightsDeckId: string | null = null;
    private publicationRevisionId: string | null = null;
    private readonly fragment = toSignal(this.route.fragment, { initialValue: null });
    /** The fragment `access` was already followed for this deck, so a later re-render does not pull the focus again. */
    private followedAccess: string | null = null;
    private announced: { readonly deckId: string; readonly progress: ForkProgress | 'none' } | null = null;
    private pollTimer: ReturnType<typeof setTimeout> | null = null;
    private pollAttempt = 0;
    private lastPollAt = 0;
    private polledDeckId: string | null = null;

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
            const deckId = params.get('deckId');
            if (deckId === null) return;
            const normalizedDeckId = deckId.toLowerCase();
            this.captureCount.set(null);
            this.confirmingDetach.set(false);
            this.detachError.set(null);
            this.retryError.set(null);
            this.liveMessage.set('');
            this.announced = null;
            this.publishedLevel.set(null);
            this.publicationRevisionId = null;
            this.editing.set(false);
            this.loadInsights(normalizedDeckId);
            this.authoring.listDeckCaptures(normalizedDeckId, null, 1)
                .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
                    next: page => this.captureCount.set(page.total),
                    error: () => this.captureCount.set(null)
                });
            this.recoveryContext = { operation: 'save', deckId: normalizedDeckId };
            this.recoveredDeckId = null;
            this.recovered.set(false);
            this.loadedRevisionId = null;
            this.store.openDeck(normalizedDeckId);
            const restored = this.recovery.restore(this.recoveryContext);
            if (restored !== null) {
                this.recoveredDeckId = normalizedDeckId;
                this.recovered.set(true);
                this.editing.set(true);
                this.loadDraft(restored.draft, true);
                if (restored.pending !== null) this.store.recoverMutation(restored.pending);
            }
        });

        this.capabilities.read().pipe(takeUntilDestroyed()).subscribe({
            next: capabilities => this.generationAvailable.set(capabilities.aiGeneration.available),
            error: () => this.generationAvailable.set(CAPABILITIES_UNAVAILABLE.aiGeneration.available)
        });

        const document = this.element.nativeElement.ownerDocument;
        const onVisible = (): void => {
            // The tab is back: look now instead of waiting out the timer, within the same bounds and no more often than the first step.
            if (document.visibilityState === 'hidden' || this.copyState() !== 'preparing' || this.pollStalled()
                || Date.now() - this.lastPollAt < FORK_POLL_MIN_GAP_MS) return;
            if (this.pollTimer !== null) clearTimeout(this.pollTimer);
            this.pollTimer = null;
            void this.poll();
        };
        document.addEventListener('visibilitychange', onVisible);
        this.destroyRef.onDestroy(() => { document.removeEventListener('visibilitychange', onVisible); this.stopPolling(); });
        effect(() => {
            const deck = this.store.detailState().deck;
            untracked(() => this.announceCopyState(deck));
        });
        effect(() => {
            // While the copy is being prepared the page asks again at a bounded backoff; a ready, failed or ordinary deck needs nothing.
            const copyState = this.copyState();
            const deckId = this.store.detailState().deck?.deckId ?? null;
            untracked(() => {
                if (copyState === 'preparing' && deckId !== null) this.startPolling(deckId); else this.stopPolling();
            });
        });
        effect(() => {
            // `/decks/{id}#access` (the link of «Запрошен доступ») lands on the access section once the block is on the page.
            const block = this.publication();
            const deckId = this.store.detailState().deck?.deckId ?? null;
            if (block === undefined || deckId === null || this.fragment() !== 'access' || this.followedAccess === deckId) return;
            this.followedAccess = deckId;
            untracked(() => block.focusSection());
        });

        effect(() => {
            const detail = this.store.detailState();
            const mutation = this.store.mutationState();
            // A save that needs a decision (conflict, error) or just finished is shown with the form that caused it.
            untracked(() => {
                if (mutation.phase === 'conflict' || mutation.phase === 'error') this.editing.set(true);
                else if (mutation.phase === 'completed' && mutation.operation === 'save' && this.editing()) this.closeEditor();
            });
            if (mutation.phase === 'completed' && mutation.operation === 'save' && this.recoveryContext !== null) {
                this.recovery.clear(this.recoveryContext);
                this.recoveredDeckId = null;
                this.recovered.set(false);
            } else {
                const pending = recoverablePendingCommand(mutation);
                if (pending !== null && this.recoveryContext !== null) {
                    this.recovery.save(this.recoveryContext, this.draft(), pending);
                } else if (mutation.phase === 'error') {
                    this.persistRecovery();
                }
            }
            if (detail.phase === 'ready' && detail.deck !== null) {
                if (this.publicationRevisionId !== null && this.publicationRevisionId !== detail.deck.revisionId) {
                    this.publicationToken.update(token => token + 1);
                }
                this.publicationRevisionId = detail.deck.revisionId;
            }
            if (detail.phase !== 'ready' || detail.deck === null || mutation.phase === 'conflict') return;
            if (this.recoveredDeckId === detail.deck.deckId && mutation.phase !== 'completed') return;
            if (detail.deck.revisionId === this.loadedRevisionId) return;
            this.loadForm(detail.deck);
        });
    }

    /** «Доступ…» and «Отвязать от автора» of the «⋯» menu. */
    onMenu(id: string): void {
        if (id === 'access') this.publication()?.focusSection();
        else if (id === 'detach') this.askDetach();
    }

    /** «Повторить» on a copy that could not be prepared. */
    retryCopy(deck: OwnDeck): void {
        if (this.retrying()) return;
        this.retrying.set(true);
        this.retryError.set(null);
        this.forks.retry(deck.deckId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: answer => {
                // The answer already says the copy is being prepared again: show it and start watching, then confirm with a read.
                this.resetPolling();
                this.store.applyFork(deck.deckId, { preparing: answer.preparing, failed: answer.failed });
                void this.store.refreshDetail().then(() => {
                    this.retrying.set(false);
                    this.focusStatusHeadingIfLost();
                });
            },
            error: () => {
                this.retryError.set(COPY_TEXT.retryFailed);
                this.retrying.set(false);
            }
        });
    }

    /** «Проверить снова» after the page stopped asking by itself. */
    checkCopyAgain(): void {
        this.resetPolling();
        void this.poll();
        this.focusStatusHeadingIfLost();
    }

    askDetach(): void {
        this.detachError.set(null);
        this.confirmingDetach.set(true);
        afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>('#detach-title')?.focus(), { injector: this.injector });
    }

    cancelDetach(): void {
        if (this.detaching()) return;
        this.confirmingDetach.set(false);
        this.detachError.set(null);
        // The «⋯» button it was opened from.
        queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('app-action-menu button.trigger')?.focus());
    }

    confirmDetach(deck: OwnDeck): void {
        if (this.detaching()) return;
        this.detaching.set(true);
        this.detachError.set(null);
        this.forks.detach(deck.deckId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: () => {
                void this.store.refreshDetail().then(() => {
                    this.detaching.set(false);
                    this.confirmingDetach.set(false);
                    this.liveMessage.set(COPY_TEXT.detachedDone);
                    afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>('h1')?.focus(), { injector: this.injector });
                });
            },
            error: error => {
                this.detachError.set(error instanceof HttpErrorResponse && error.status === 404 ? COPY_TEXT.deckMissing : COPY_TEXT.detachFailed);
                this.detaching.set(false);
            }
        });
    }

    openEditor(field: 'title' | 'description' = 'title'): void {
        this.editing.set(true);
        afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>(`#detail-${field}`)?.focus(), { injector: this.injector });
    }

    /** Closing keeps the draft: it stays in the form and in the recovery store until saved or discarded. */
    closeEditor(): void {
        this.editing.set(false);
        queueMicrotask(() => this.editButton()?.nativeElement.focus());
    }

    toggleEditor(): void {
        if (this.editing()) this.closeEditor(); else this.openEditor();
    }

    /** Material list actions that belong to the statistics: list the materials without exercises first. */
    showMaterialsWithoutExercises(): void {
        this.materials()?.showMissingFirst();
    }

    loadInsights(deckId: string = this.insightsDeckId ?? ''): void {
        if (deckId === '') return;
        this.insightsDeckId = deckId;
        this.insightsLoad?.unsubscribe();
        this.insights.set({ phase: 'loading' });
        this.insightsLoad = this.hubApi.insights(deckId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: insights => this.insights.set({ phase: 'ready', insights }),
            error: () => this.insights.set({ phase: 'error' })
        });
    }

    syncDraft(): void {
        if (this.store.mutationState().phase === 'completed') this.store.clearMutation();
        this.draft.set(this.form.getRawValue());
        this.persistRecovery();
    }

    save(deck: OwnDeck): void {
        this.syncDraft();
        this.submitted.set(true);
        if (!this.validation().valid) {
            queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
            return;
        }
        this.store.startSave(deck, this.draft());
        this.persistRecovery();
    }

    chooseServerVersion(): void {
        const latest = this.store.useServerVersion();
        if (latest !== null) {
            if (this.recoveryContext !== null) this.recovery.clear(this.recoveryContext);
            this.recoveredDeckId = null;
            this.recovered.set(false);
            this.loadForm(latest);
        }
    }

    retryMutation(): void {
        this.store.retryMutation();
        this.persistRecovery();
    }

    deleteDeck(deck: OwnDeck): void {
        if (this.deleting() || !this.canDelete(this.store.mutationState())) return;
        this.deleting.set(true);
        this.deleteError.set(null);
        this.decksApi.delete(deck).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: () => {
                if (this.recoveryContext !== null) this.recovery.clear(this.recoveryContext);
                void this.router.navigate(['/decks']);
            },
            error: () => {
                this.deleteError.set('Не удалось удалить колоду. Возможно, она уже изменилась — обновите страницу и попробуйте снова.');
                this.deleting.set(false);
            }
        });
    }

    retryAsNewCommand(): void {
        this.store.retryAsNewCommand();
        this.persistRecovery();
    }

    reapplyConflict(): void {
        this.store.reapplyConflict();
        this.persistRecovery();
    }

    /** Says each change of the copy's state once: first sight of a copy being prepared, a failure, and the end of the preparation. */
    private announceCopyState(deck: OwnDeck | null): void {
        if (deck === null) return;
        const fork = deck.fork ?? null;
        const progress = fork === null ? 'none' : forkProgressOf(fork);
        const previous = this.announced?.deckId === deck.deckId ? this.announced.progress : null;
        this.announced = { deckId: deck.deckId, progress };
        if (progress === previous) return;
        if (progress === 'preparing') {
            this.liveMessage.set(previous === 'failed' ? COPY_TEXT.retried : COPY_TEXT.preparing);
            // Arriving from «Добавить к себе»: the focus is on nothing, so it goes to the title that names the copy.
            if (previous === null) this.focusHeadingIfLost('h1');
        } else if (progress === 'failed') this.liveMessage.set(`${COPY_TEXT.failed}.`);
        else if (progress === 'ready' && (previous === 'preparing' || previous === 'failed')) {
            this.liveMessage.set(COPY_TEXT.ready);
            // The statistics were read while the copy was empty.
            this.loadInsights(deck.deckId);
            this.focusHeadingIfLost('h1');
        }
    }

    /** The pressed button went away with its state: the focus goes to the heading of the status block that replaced it. */
    private focusStatusHeadingIfLost(): void {
        this.focusHeadingIfLost('app-copy-status [tabindex="-1"]');
    }

    private focusHeadingIfLost(selector: string): void {
        afterNextRender(() => {
            const document = this.element.nativeElement.ownerDocument;
            if (document.activeElement === null || document.activeElement === document.body || !document.activeElement.isConnected) {
                this.element.nativeElement.querySelector<HTMLElement>(selector)?.focus();
            }
        }, { injector: this.injector });
    }

    private startPolling(deckId: string): void {
        if (this.polledDeckId !== deckId) {
            this.polledDeckId = deckId;
            this.pollAttempt = 0;
            this.pollStalled.set(false);
        }
        this.schedulePoll();
    }

    private resetPolling(): void {
        this.pollAttempt = 0;
        this.pollStalled.set(false);
    }

    private stopPolling(): void {
        if (this.pollTimer !== null) clearTimeout(this.pollTimer);
        this.pollTimer = null;
        this.polledDeckId = null;
    }

    private schedulePoll(): void {
        if (this.pollTimer !== null || this.pollStalled() || this.destroyRef.destroyed) return;
        this.pollTimer = setTimeout(() => void this.poll(), forkPollDelay(this.pollAttempt));
    }

    private async poll(): Promise<void> {
        this.pollTimer = null;
        if (this.destroyRef.destroyed || this.copyState() !== 'preparing') return;
        // A hidden tab does not ask; the next check comes at the same pace once it is visible again.
        if (this.element.nativeElement.ownerDocument.visibilityState !== 'hidden') {
            this.pollAttempt += 1;
            this.lastPollAt = Date.now();
            if (this.pollAttempt > FORK_POLL_LIMIT) {
                this.pollStalled.set(true);
                return;
            }
            await this.store.refreshDetail();
        }
        if (!this.destroyRef.destroyed && this.copyState() === 'preparing') this.schedulePoll();
    }

    private loadForm(deck: OwnDeck): void {
        this.loadedRevisionId = deck.revisionId;
        this.loadDraft(deck.metadata, false);
    }

    private loadDraft(metadata: DeckMetadata, dirty: boolean): void {
        this.form.setValue(metadata, { emitEvent: false });
        this.draft.set({ ...metadata });
        this.submitted.set(false);
        if (dirty) this.form.markAsDirty();
        else this.form.markAsPristine();
    }

    private persistRecovery(): void {
        if (this.recoveryContext === null) return;
        const pending = recoverablePendingCommand(this.store.mutationState());
        const detail = this.store.detailState();
        const draft = this.draft();
        const serverMetadata = detail.phase === 'ready' ? detail.deck?.metadata ?? null : null;
        const dirty = serverMetadata === null || draft.title !== serverMetadata.title
            || draft.description !== serverMetadata.description;
        if (!dirty && pending === null) {
            this.recovery.clear(this.recoveryContext);
            this.recoveredDeckId = null;
            this.recovered.set(false);
            return;
        }
        this.recovery.save(this.recoveryContext, draft, pending);
    }
}
