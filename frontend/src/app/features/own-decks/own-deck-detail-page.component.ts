import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { DeckMetadata, OwnDeck, validateDeckMetadata } from './own-deck.models';
import { DeckRecoveryContext, OwnDeckRecoveryService } from './own-deck-recovery.service';
import {
    OwnDecksStore,
    canStartNewMutation,
    deckFailureMessage,
    mayRetrySameCommand,
    mutationLocksDraft,
    recoverablePendingCommand
} from './own-decks.store';

@Component({
    selector: 'app-own-deck-detail-page',
    imports: [DatePipe, ReactiveFormsModule, RouterLink],
    providers: [OwnDecksStore],
    templateUrl: './own-deck-detail-page.component.html',
    styleUrl: './own-decks-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
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
    readonly failureMessage = deckFailureMessage;
    readonly mayRetrySameCommand = mayRetrySameCommand;
    readonly mutationLocksDraft = mutationLocksDraft;
    readonly canStartNewMutation = canStartNewMutation;

    private readonly route = inject(ActivatedRoute);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    private readonly recovery = inject(OwnDeckRecoveryService);
    private recoveryContext: DeckRecoveryContext | null = null;
    private recoveredDeckId: string | null = null;
    private loadedRevisionId: string | null = null;

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
            const deckId = params.get('deckId');
            if (deckId === null) return;
            const normalizedDeckId = deckId.toLowerCase();
            this.recoveryContext = { operation: 'save', deckId: normalizedDeckId };
            this.recoveredDeckId = null;
            this.recovered.set(false);
            this.loadedRevisionId = null;
            this.store.openDeck(normalizedDeckId);
            const restored = this.recovery.restore(this.recoveryContext);
            if (restored !== null) {
                this.recoveredDeckId = normalizedDeckId;
                this.recovered.set(true);
                this.loadDraft(restored.draft, true);
                if (restored.pending !== null) this.store.recoverMutation(restored.pending);
            }
        });

        effect(() => {
            const detail = this.store.detailState();
            const mutation = this.store.mutationState();
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
