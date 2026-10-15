import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, LOCALE_ID, afterNextRender, computed, effect, inject, linkedSignal, signal, untracked, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { AuthoringApiService } from '../authoring/authoring-api.service';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService } from '../authoring/capabilities-api.service';

import { DeckMetadata, DeckVisibility, OwnDeck, validateDeckMetadata } from './own-deck.models';
import { PaperDialogComponent } from '../../shared/paper-dialog.component';
import { GlyphComponent, GlyphName } from '../../shared/glyph.component';
import { ACCESS_LEVEL_LABELS, AccessLevelComponent } from '../../shared/access-level.component';
import { ActionMenuComponent, ActionMenuItem } from '../../shared/action-menu.component';
import { ToastService } from '../../core/notifications/toast.service';
import { COPY_TEXT, UPDATE_TEXT } from '../../shared/copy-text';
import { CopyOriginComponent } from '../../shared/copy-origin.component';
import { CopyStatusComponent } from '../../shared/copy-status.component';
import { ToggletipComponent } from '../../shared/toggletip.component';
import { UnavailableNoteComponent } from '../../shared/unavailable-note.component';
import { UpdateBadgeComponent } from '../../shared/update-badge.component';
import { UpdateNewsComponent } from '../../shared/update-news.component';
import { sizeLine } from '../../shared/public-deck-card';
import { PublicationBlockComponent } from './publication/publication-block.component';
import { DeckDescriptionComponent } from './deck-description.component';
import { OwnDecksApiService } from './own-decks-api.service';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { DeckHubApiService } from './hub/deck-hub-api.service';
import { DeckInsightsComponent, InsightsState } from './hub/deck-insights.component';
import { DeckMaterialsComponent } from './hub/deck-materials.component';
import { DeckWorkshopsComponent } from '../generation/deck-workshops.component';
import { DeckModerationComponent } from './moderation/deck-moderation.component';
import { DeckRecoveryContext, OwnDeckRecoveryService } from './own-deck-recovery.service';
import { ForkApiService } from './fork/fork-api.service';
import { UpdateSeenState } from './update/update-seen-state';
import { whenOf } from './update/update-history.models';
import { FORK_POLL_LIMIT, FORK_POLL_MIN_GAP_MS, forkPollDelay } from './fork/fork-poll';
import { ForkProgress, forkFailureOf, forkProgressOf } from './fork/fork.models';
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
        PaperDialogComponent, GlyphComponent, DeckInsightsComponent, DeckMaterialsComponent, DeckWorkshopsComponent, AccessLevelComponent, PublicationBlockComponent, ActionMenuComponent, DeckModerationComponent,
        CopyOriginComponent, CopyStatusComponent, ToggletipComponent, UnavailableNoteComponent, UpdateBadgeComponent, UpdateNewsComponent],
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
    readonly shareButton = viewChild<ElementRef<HTMLButtonElement>>('shareButton');
    readonly editButton = viewChild<ElementRef<HTMLButtonElement>>('editButton');
    readonly publication = viewChild(PublicationBlockComponent);
    readonly sharingDialog = viewChild(PaperDialogComponent);
    readonly shareLevel = computed(() => this.publishedLevel() ?? this.store.detailState().deck?.visibility ?? 'private');
    readonly shareGlyph = computed<GlyphName>(() => this.shareLevel() === 'link' ? 'link' : this.shareLevel() === 'public' ? 'colonnade' : 'lock');
    readonly shareLabel = computed(() => `Поделиться. Доступ: ${ACCESS_LEVEL_LABELS[this.shareLevel()]}`);
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
    /** «Есть обновление»: the copy is ready and attached, its source opens, and the author published something (all of it decided by the server). */
    readonly updateOffer = computed(() => {
        const fork = this.fork();
        return fork !== null && fork.updateAvailable && !fork.detached && !fork.preparing && fork.sourceAvailable;
    });
    /**
     * «Что нового»: an automatic update of a ready, attached copy that its owner has not looked at yet (`fork.lastUpdate`, which the server only
     * sends then). Opening the history marks it seen; the block is gone at the next read of the deck.
     */
    readonly lastUpdate = computed(() => {
        const fork = this.fork();
        const last = fork !== null && !fork.detached && !fork.preparing ? fork.lastUpdate ?? null : null;
        // Marked seen in this tab (the history page sent `:seen`): gone at once, before the next read of the deck says so.
        return last !== null && !this.seenState.seen().has(last.deckRevisionId) ? last : null;
    });
    /** The time of that update, relative and exact. */
    readonly lastUpdateWhen = computed(() => {
        const last = this.lastUpdate();
        if (last === null) return '';
        const { relative, exact } = whenOf(last.appliedAt, Date.now(), this.locale);
        return relative === null ? exact : `${relative}, ${exact}`;
    });
    /** «Проверить обновления»: a quiet link for a ready, attached copy whose source opens and that has no update waiting. */
    readonly updateCheck = computed(() => {
        const fork = this.fork();
        return fork !== null && !fork.detached && !fork.preparing && fork.sourceAvailable && !fork.updateAvailable;
    });
    /** The source is closed for the owner of an attached copy: the hub says so (`app-unavailable-note`) and offers no update link. */
    readonly sourceClosed = computed(() => {
        const fork = this.fork();
        return fork !== null && !fork.detached && !fork.sourceAvailable;
    });
    /** «Принимать безопасное автоматически» is offered for an attached, ready copy whose source opens (a closed source sends no updates to take). */
    readonly autoUpdateShown = computed(() => {
        const fork = this.fork();
        return fork !== null && !fork.detached && !fork.preparing && fork.sourceAvailable;
    });
    /** The setting as the page shows it: the stored value, replaced at once when the owner flips it (and put back if the save fails). */
    readonly autoUpdate = linkedSignal(() => this.fork()?.autoUpdate ?? false);
    readonly autoSaving = signal(false);
    readonly autoError = signal<string | null>(null);
    /** The «⋯» menu of the deck: only actions that work. */
    readonly menuItems = computed<readonly ActionMenuItem[]>(() => {
        const fork = this.fork();
        return [
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
    protected readonly updateText = UPDATE_TEXT;
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
    private readonly seenState = inject(UpdateSeenState);
    private readonly locale = inject(LOCALE_ID);
    private readonly toasts = inject(ToastService);
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
            this.autoError.set(null);
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
            // Уведомление «Запрошен доступ» открывает окно, когда колода и настройки появились.
            const block = this.publication();
            const deckId = this.store.detailState().deck?.deckId ?? null;
            if (block === undefined || deckId === null || this.fragment() !== 'access' || this.followedAccess === deckId) return;
            this.followedAccess = deckId;
            untracked(() => afterNextRender(() => this.sharingDialog()?.open(this.shareButton()?.nativeElement), { injector: this.injector }));
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

    /** Оставшиеся действия над копией в меню «⋯». */
    onMenu(id: string): void {
        if (id === 'detach') this.askDetach();
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
                    this.toasts.echo(COPY_TEXT.detachedDone);
                    afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>('h1')?.focus(), { injector: this.injector });
                });
            },
            error: error => {
                this.detachError.set(error instanceof HttpErrorResponse && error.status === 404 ? COPY_TEXT.deckMissing : COPY_TEXT.detachFailed);
                this.detaching.set(false);
            }
        });
    }

    /** A click on the setting while a save is on its way changes nothing (the group keeps its place in the focus order). */
    holdAutoUpdate(event: Event): void {
        if (this.autoSaving()) event.preventDefault();
    }

    /**
     * «Принимать безопасное автоматически»: the new value is shown at once and saved; a failure puts the old value back, says why in
     * words and, when the copy turned out to be preparing or detached, reads the deck again so the page shows its real state.
     */
    toggleAutoUpdate(deck: OwnDeck, event: Event): void {
        const input = event.target as HTMLInputElement;
        const wanted = input.checked;
        const previous = this.autoUpdate();
        if (this.autoSaving()) {
            input.checked = previous;
            return;
        }
        this.autoSaving.set(true);
        this.autoError.set(null);
        this.autoUpdate.set(wanted);
        this.forks.setAutoUpdate(deck.deckId, wanted).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: answer => {
                this.autoSaving.set(false);
                this.autoUpdate.set(answer.autoUpdate);
                input.checked = answer.autoUpdate;
                this.store.applyFork(deck.deckId, { autoUpdate: answer.autoUpdate });
                this.liveMessage.set(answer.autoUpdate ? UPDATE_TEXT.autoOn : UPDATE_TEXT.autoOff);
            },
            error: error => {
                this.autoSaving.set(false);
                this.autoUpdate.set(previous);
                input.checked = previous;
                const kind = forkFailureOf(error).kind;
                const reason = kind === 'preparing' ? UPDATE_TEXT.autoPreparing : kind === 'detached' ? UPDATE_TEXT.autoDetached
                    : kind === 'not-found' ? COPY_TEXT.deckMissing : UPDATE_TEXT.autoFailed;
                // The reason first, then what the setting is now: the reader never has to guess which way it stayed.
                this.autoError.set(`${reason} ${previous ? UPDATE_TEXT.autoStayedOn : UPDATE_TEXT.autoStayedOff}`);
                // The copy is not in the state the page thought: read it again, the real state replaces this block.
                if (kind === 'preparing' || kind === 'detached') void this.store.refreshDetail();
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

    editSharedDescription(): void {
        this.sharingDialog()?.close();
        this.openEditor('description');
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
