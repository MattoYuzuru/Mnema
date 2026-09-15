import { ChangeDetectionStrategy, Component, ElementRef, computed, effect, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { DeckMetadata, OwnDeck, validateDeckMetadata } from './own-deck.models';
import {
    OwnDecksStore,
    canStartNewMutation,
    deckFailureMessage,
    mayRetrySameCommand,
    mutationLocksDraft
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
    readonly failureMessage = deckFailureMessage;
    readonly mayRetrySameCommand = mayRetrySameCommand;
    readonly mutationLocksDraft = mutationLocksDraft;
    readonly canStartNewMutation = canStartNewMutation;

    private readonly route = inject(ActivatedRoute);
    private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
    private loadedRevisionId: string | null = null;

    constructor() {
        this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(params => {
            const deckId = params.get('deckId');
            if (deckId !== null) this.store.openDeck(deckId);
        });

        effect(() => {
            const detail = this.store.detailState();
            const mutation = this.store.mutationState();
            if (detail.phase !== 'ready' || detail.deck === null || mutation.phase === 'conflict') return;
            if (detail.deck.revisionId === this.loadedRevisionId) return;
            this.loadForm(detail.deck);
        });
    }

    syncDraft(): void {
        if (this.store.mutationState().phase === 'completed') this.store.clearMutation();
        this.draft.set(this.form.getRawValue());
    }

    save(deck: OwnDeck): void {
        this.syncDraft();
        this.submitted.set(true);
        if (!this.validation().valid) {
            queueMicrotask(() => this.element.nativeElement.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus());
            return;
        }
        this.store.startSave(deck, this.draft());
    }

    chooseServerVersion(): void {
        const latest = this.store.useServerVersion();
        if (latest !== null) this.loadForm(latest);
    }

    private loadForm(deck: OwnDeck): void {
        this.loadedRevisionId = deck.revisionId;
        this.form.setValue(deck.metadata, { emitEvent: false });
        this.draft.set({ ...deck.metadata });
        this.submitted.set(false);
        this.form.markAsPristine();
    }
}
