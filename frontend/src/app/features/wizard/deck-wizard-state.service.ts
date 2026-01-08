import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { UserDeckDTO } from '../../core/models/user-deck.models';

export interface PendingCard {
    content: Record<string, string>;
    tags?: string[];
}

export interface DeckWizardState {
    currentStep: number;
    templateId: string | null;
    deckMetadata: {
        name: string;
        description: string;
        language: string;
        tags: string[];
        isPublic: boolean;
        isListed: boolean;
        iconMediaId?: string;
    };
    createdDeck: UserDeckDTO | null;
    pendingCards: PendingCard[];
}

const initialState: DeckWizardState = {
    currentStep: 1,
    templateId: null,
    deckMetadata: {
        name: '',
        description: '',
        language: 'en',
        tags: [],
        isPublic: false,
        isListed: false,
        iconMediaId: undefined
    },
    createdDeck: null,
    pendingCards: []
};

@Injectable({ providedIn: 'root' })
export class DeckWizardStateService {
    private state$ = new BehaviorSubject<DeckWizardState>({ ...initialState });

    getState() {
        return this.state$.asObservable();
    }

    getCurrentState(): DeckWizardState {
        return this.state$.value;
    }

    setTemplateId(templateId: string): void {
        this.state$.next({
            ...this.state$.value,
            templateId
        });
    }

    setDeckMetadata(metadata: Partial<DeckWizardState['deckMetadata']>): void {
        this.state$.next({
            ...this.state$.value,
            deckMetadata: {
                ...this.state$.value.deckMetadata,
                ...metadata
            }
        });
    }

    setCreatedDeck(deck: UserDeckDTO): void {
        this.state$.next({
            ...this.state$.value,
            createdDeck: deck
        });
    }

    addPendingCard(card: PendingCard): void {
        this.state$.next({
            ...this.state$.value,
            pendingCards: [...this.state$.value.pendingCards, card]
        });
    }

    clearPendingCards(): void {
        this.state$.next({
            ...this.state$.value,
            pendingCards: []
        });
    }

    setCurrentStep(step: number): void {
        this.state$.next({
            ...this.state$.value,
            currentStep: step
        });
    }

    nextStep(): void {
        this.setCurrentStep(this.state$.value.currentStep + 1);
    }

    previousStep(): void {
        this.setCurrentStep(Math.max(1, this.state$.value.currentStep - 1));
    }

    reset(): void {
        this.state$.next({ ...initialState });
    }
}
