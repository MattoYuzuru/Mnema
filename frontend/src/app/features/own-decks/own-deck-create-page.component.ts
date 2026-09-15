import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { DeckMetadata, validateDeckMetadata } from './own-deck.models';
import {
    OwnDecksStore,
    canStartNewMutation,
    deckFailureMessage,
    mayRetrySameCommand,
    mutationLocksDraft
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
    readonly failureMessage = deckFailureMessage;
    readonly mayRetrySameCommand = mayRetrySameCommand;
    readonly mutationLocksDraft = mutationLocksDraft;
    readonly canStartNewMutation = canStartNewMutation;

    private readonly router = inject(Router);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    private navigatedDeckId: string | null = null;

    constructor() {
        effect(() => {
            const mutation = this.store.mutationState();
            if (mutation.phase !== 'completed' || mutation.operation !== 'create'
                || mutation.deck.deckId === this.navigatedDeckId) return;
            this.navigatedDeckId = mutation.deck.deckId;
            void this.router.navigate(['/decks', mutation.deck.deckId]);
        });
    }

    syncDraft(): void {
        this.draft.set(this.form.getRawValue());
    }

    submit(): void {
        this.syncDraft();
        this.submitted.set(true);
        if (!this.validation().valid) {
            queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
            return;
        }
        this.store.startCreate(this.draft());
    }
}
