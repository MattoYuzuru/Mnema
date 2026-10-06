import { Injectable, Injector, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LearningGoal } from './goal.models';
import { LearningProfileApiService } from './learning-profile-api.service';

export type GoalState = 'idle' | 'loading' | 'ready' | 'error';

/**
 * The owner's learning goal for the whole session. It reads as a signal (`goal`) wherever copy changes; only the shell's
 * onboarding and the plans page call `load()`, so a screen that merely reads the goal needs no HTTP. The API is resolved on
 * first use for the same reason.
 */
@Injectable({ providedIn: 'root' })
export class LearningGoalStore {
    private readonly injector = inject(Injector);
    private pending: Promise<void> | null = null;
    /** Bumped by every reset: a fetch that started before a sign-out must not write the previous account's profile. */
    private generation = 0;

    readonly state = signal<GoalState>('idle');
    /** The chosen goal; null when unanswered or skipped. */
    readonly goal = signal<LearningGoal | null>(null);
    /** Whether the owner has answered or skipped; the question is asked while this is false and the state is ready. */
    readonly answered = signal(false);
    readonly saving = signal(false);
    readonly saveFailed = signal(false);

    private api(): LearningProfileApiService { return this.injector.get(LearningProfileApiService); }

    /** Loads once; a repeated call joins the one in flight and a ready state is not fetched again. */
    load(): Promise<void> {
        if (this.state() === 'ready') return Promise.resolve();
        if (this.pending === null) {
            const request: Promise<void> = this.fetch().finally(() => { if (this.pending === request) this.pending = null; });
            this.pending = request;
        }
        return this.pending;
    }

    private async fetch(): Promise<void> {
        const generation = this.generation;
        this.state.set('loading');
        try {
            const profile = await firstValueFrom(this.api().load());
            if (generation !== this.generation) return;
            this.goal.set(profile.goal);
            this.answered.set(profile.answeredAt !== null);
            this.state.set('ready');
        } catch {
            if (generation === this.generation) this.state.set('error');
        }
    }

    /** Stores a goal, or (with `null`) a skip. Resolves to whether it was stored. */
    async answer(goal: LearningGoal | null): Promise<boolean> {
        if (this.saving()) return false;
        const generation = this.generation;
        this.saving.set(true);
        this.saveFailed.set(false);
        try {
            const profile = await firstValueFrom(this.api().answer(goal));
            if (generation !== this.generation) return false;
            this.goal.set(profile.goal);
            this.answered.set(true);
            this.state.set('ready');
            return true;
        } catch {
            if (generation === this.generation) this.saveFailed.set(true);
            return false;
        } finally {
            if (generation === this.generation) this.saving.set(false);
        }
    }

    /** Signing out forgets the previous account's answer. */
    reset(): void {
        this.generation++;
        this.pending = null;
        this.saving.set(false);
        this.state.set('idle');
        this.goal.set(null);
        this.answered.set(false);
        this.saveFailed.set(false);
    }
}
