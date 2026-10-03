import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';
import { AuthoringApiService } from '../authoring/authoring-api.service';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService } from '../authoring/capabilities-api.service';

import { DeckMetadata, OwnDeck, validateDeckMetadata } from './own-deck.models';
import { DeckDescriptionComponent } from './deck-description.component';
import { OwnDecksApiService } from './own-decks-api.service';
import { HoldToDeleteButtonComponent } from '../../shared/hold-to-delete-button.component';
import { DeckHubApiService } from './hub/deck-hub-api.service';
import { DeckInsightsComponent, InsightsState } from './hub/deck-insights.component';
import { DeckMaterialsComponent } from './hub/deck-materials.component';
import { DeckWorkshopsComponent } from '../generation/deck-workshops.component';
import { DeckRecoveryContext, OwnDeckRecoveryService } from './own-deck-recovery.service';
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
        DeckInsightsComponent, DeckMaterialsComponent, DeckWorkshopsComponent],
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
    readonly editButton = viewChild<ElementRef<HTMLButtonElement>>('editButton');
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
    private readonly capabilities = inject(CapabilitiesApiService);
    private readonly router = inject(Router);
    private readonly destroyRef = inject(DestroyRef);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    private readonly recovery = inject(OwnDeckRecoveryService);
    private recoveryContext: DeckRecoveryContext | null = null;
    private recoveredDeckId: string | null = null;
    private loadedRevisionId: string | null = null;
    private insightsLoad: Subscription | null = null;
    private insightsDeckId: string | null = null;

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
            const deckId = params.get('deckId');
            if (deckId === null) return;
            const normalizedDeckId = deckId.toLowerCase();
            this.captureCount.set(null);
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
            if (detail.phase !== 'ready' || detail.deck === null || mutation.phase === 'conflict') return;
            if (this.recoveredDeckId === detail.deck.deckId && mutation.phase !== 'completed') return;
            if (detail.deck.revisionId === this.loadedRevisionId) return;
            this.loadForm(detail.deck);
        });
    }

    openEditor(): void {
        this.editing.set(true);
        queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('#detail-title')?.focus());
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
