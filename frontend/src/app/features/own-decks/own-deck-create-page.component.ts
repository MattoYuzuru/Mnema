import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { DeckMetadata, validateDeckMetadata } from './own-deck.models';
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
    selector: 'app-own-deck-create-page',
    imports: [ReactiveFormsModule, RouterLink],
    providers: [OwnDecksStore],
    templateUrl: './own-deck-create-page.component.html',
    styleUrl: './own-decks-page.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class OwnDeckCreatePageComponent {
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

    private readonly router = inject(Router);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    private readonly recovery = inject(OwnDeckRecoveryService);
    private readonly recoveryContext: DeckRecoveryContext = { operation: 'create' };
    private navigatedDeckId: string | null = null;

    constructor() {
        const restored = this.recovery.restore(this.recoveryContext);
        if (restored !== null) {
            this.recovered.set(true);
            this.form.setValue(restored.draft, { emitEvent: false });
            this.draft.set({ ...restored.draft });
            this.form.markAsDirty();
            if (restored.pending !== null) this.store.recoverMutation(restored.pending);
        }

        effect(() => {
            const mutation = this.store.mutationState();
            if (mutation.phase === 'completed' && mutation.operation === 'create') {
                this.recovered.set(false);
                this.recovery.clear(this.recoveryContext);
                if (mutation.deck.deckId !== this.navigatedDeckId) {
                    this.navigatedDeckId = mutation.deck.deckId;
                    void this.router.navigate(['/decks', mutation.deck.deckId]);
                }
                return;
            }
            const pending = recoverablePendingCommand(mutation);
            if (pending !== null) this.recovery.save(this.recoveryContext, this.draft(), pending);
            else if (mutation.phase === 'error') this.persistRecovery();
        });
    }

    syncDraft(): void {
        this.draft.set(this.form.getRawValue());
        this.persistRecovery();
    }

    submit(): void {
        this.syncDraft();
        this.submitted.set(true);
        if (!this.validation().valid) {
            queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
            return;
        }
        this.store.startCreate(this.draft());
        this.persistRecovery();
    }

    retryMutation(): void {
        this.store.retryMutation();
        this.persistRecovery();
    }

    retryAsNewCommand(): void {
        this.store.retryAsNewCommand();
        this.persistRecovery();
    }

    private persistRecovery(): void {
        const pending = recoverablePendingCommand(this.store.mutationState());
        const draft = this.draft();
        if (draft.title.length === 0 && draft.description.length === 0 && pending === null) {
            this.recovery.clear(this.recoveryContext);
            this.recovered.set(false);
            return;
        }
        this.recovery.save(this.recoveryContext, draft, pending);
    }
}
