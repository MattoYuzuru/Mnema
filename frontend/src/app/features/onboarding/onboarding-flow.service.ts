import { EffectRef, Injectable, Injector, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, firstValueFrom, of } from 'rxjs';

import { COMMUNITY_LAUNCH } from '../../shared/community-launch';
import { ToastService } from '../../core/notifications/toast.service';
import { AttentionCoordinator } from '../../core/onboarding/attention-coordinator';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { Branch, Experience, StepKey, isApplied } from '../../core/onboarding/onboarding.models';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { TourEventPayload, TourEvents } from '../../core/onboarding/tour-events';
import { TourHints } from '../../core/onboarding/tour-hints';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService } from '../authoring/capabilities-api.service';
import { CommunityApiService } from '../community/community-api.service';
import { InterestStore } from '../community/interest-store.service';
import { LearningGoal } from '../goal/goal.models';
import { LearningGoalStore } from '../goal/learning-goal.store';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import {
    FIRST_ENTRY_KEYS,
    FIRST_STEP,
    FirstEntryKey,
    PATH_TARGET,
    STUDY_STEP,
    isFirstEntryKey
} from './onboarding-targets';
import { TOUR_EVENTS, TourContext, finaleKey, tourFor } from './tour-definitions';

/** The cards of the first-entry surface: the welcome, the survey, the branch choice, and the choice of a deck for a replay of «Своя колода». */
export type FirstEntryCard = FirstEntryKey | 'replay.deck';

/** The catalogue must hold at least this many listed decks before «Найти готовую колоду» is offered. */
export const MINIMUM_CATALOGUE = 4;
/** How long a learner browses the catalogue before the hint at the search, and before the offer to make their own deck. */
export const BROWSE_HINT_MS = 45_000;
export const BROWSE_STUCK_MS = 120_000;
/** How long the survey waits for the interests of the account before it goes on without that question. */
export const INTERESTS_WAIT_MS = 3_000;
/** The decks a replay of «Своя колода» offers to continue with. */
const REPLAY_DECKS = 6;

export interface ReplayDeck {
    readonly deckId: string;
    readonly title: string;
}

/**
 * The story of the first entry and of the tours, above the generic engine: when the welcome opens, which survey cards a learner still
 * has, what the branch choice offers, how a branch tour starts, pauses for the study session and ends with the finale, how a paused
 * tour is picked up on another device and how «Пройти знакомство заново» begins. The journey on the server is the source of the
 * position (`stepKey`), so a reload continues at the same card.
 *
 * Nothing here blocks a page: a failed call degrades to «no tour», and nothing of the survey is ever sent to an AI provider.
 */
@Injectable({ providedIn: 'root' })
export class OnboardingFlow {
    private readonly store = inject(JourneyStore);
    private readonly engine = inject(TourEngine);
    private readonly events = inject(TourEvents);
    private readonly attention = inject(AttentionCoordinator);
    private readonly goal = inject(LearningGoalStore);
    private readonly interests = inject(InterestStore);
    private readonly toasts = inject(ToastService);
    private readonly router = inject(Router);
    private readonly injector = inject(Injector);
    private readonly launched = inject(COMMUNITY_LAUNCH);
    private readonly hints = inject(TourHints);

    /** The card on the screen, or `null` when the first-entry surface is closed. */
    readonly card = signal<FirstEntryCard | null>(null);
    /** A replay: the survey is not asked again and the branch choice has no way back. */
    readonly replaying = signal(false);
    readonly busy = signal(false);
    private readonly history = signal<readonly FirstEntryKey[]>([]);
    readonly aiAvailable = signal(false);
    private readonly catalogueListed = signal<number | null>(null);
    readonly replayDecks = signal<readonly ReplayDeck[]>([]);
    private readonly deck = signal<string | null>(null);
    private readonly member = signal<string | null>(null);
    private capabilities: Promise<void> | null = null;
    private resumedFor: string | null = null;

    /** The card a learner should be on when the surface opens by itself: the welcome of a new account, or the card a reload interrupted. */
    readonly pendingCard = computed<FirstEntryKey | null>(() => {
        const journey = this.store.journey();
        if (journey === null || !this.store.enabled()) return null;
        if (journey.state === 'OFFERED') return journey.autoOffer ? 'welcome' : null;
        return journey.state === 'IN_PROGRESS' && isFirstEntryKey(journey.stepKey) ? journey.stepKey : null;
    });

    /** What the branch choice offers; a hidden option is simply not in the list. */
    readonly branches = computed<readonly Branch[]>(() => {
        const options: Branch[] = [];
        if (this.store.view()?.sampleDeck.available === true) options.push('SAMPLE');
        if (this.launched && (this.catalogueListed() ?? 0) >= MINIMUM_CATALOGUE) options.push('COMMUNITY');
        options.push('OWN');
        return options;
    });

    private readonly context: TourContext;

    /** The deck the tour works with (made, copied or chosen), for the screens that lead back to it. */
    currentDeck(): string | null { return this.deck(); }

    constructor() {
        const journey = this.store.journey;
        const aiAvailable = this.aiAvailable;
        this.context = {
            // Read when a step is entered, so a definition always sees the answers and the capabilities as they are by then.
            get experience() { return journey()?.experience ?? null; },
            get aiAvailable() { return aiAvailable(); },
            deckId: () => this.deck(),
            memberKey: () => this.member()
        };
        this.events.listen((event, payload) => this.onEvent(event, payload));
        effect(onCleanup => {
            const step = this.engine.step();
            const watching = this.engine.active() && !this.engine.paused() && step?.key === 'community.search';
            untracked(() => { this.hints.browse.set(false); this.hints.stuck.set(false); });
            if (!watching) return;
            const hint = setTimeout(() => this.hints.browse.set(true), BROWSE_HINT_MS);
            const stuck = setTimeout(() => this.hints.stuck.set(true), BROWSE_STUCK_MS);
            onCleanup(() => { clearTimeout(hint); clearTimeout(stuck); });
        });
    }

    // ---- the first entry -------------------------------------------------------------------------------------------------------------

    /** Opens the surface at the card the journey says (the host calls it when the coordinator and the page allow). */
    open(card: FirstEntryKey): void {
        if (this.card() !== null) return;
        this.history.set([]);
        this.card.set(card);
        void this.prepare();
    }

    /** Closes the surface without touching the journey (the feature went off, the learner signed out). */
    close(): void {
        this.card.set(null);
        this.replaying.set(false);
        this.history.set([]);
    }

    /** The number of the question a survey card asks, out of four; a skipped question leaves a gap, not a renumbering. */
    questionNumber(card: FirstEntryCard): number {
        return Math.max(1, FIRST_ENTRY_KEYS.indexOf(card as FirstEntryKey));
    }

    /** «Познакомиться — 2 минуты»: the journey starts at the first card that still has a question. */
    async start(): Promise<void> {
        if (this.busy() || this.card() !== 'welcome') return;
        this.busy.set(true);
        try {
            await this.prepare();
            const first = this.following('welcome');
            // Not taken (the server moved the journey, or could not be reached): the surface closes, and the host reopens it at the
            // card the server says if there is one.
            if (!isApplied(await this.store.act('START', { stepKey: first }))) { this.close(); return; }
            this.card.set(first);
        } finally { this.busy.set(false); }
    }

    /** «Мне это знакомо — сразу к делу»: SKIP, and a toast that says where the tour is. */
    async skipAll(): Promise<void> {
        if (this.busy() || this.card() !== 'welcome') return;
        this.busy.set(true);
        try {
            const skipped = isApplied(await this.store.act('SKIP'));
            this.close();
            if (skipped) {
                this.toasts.notify('onboarding-skipped', onboardingText('skip.toast'), 'INFO',
                    { label: onboardingText('skip.toast.link'), commands: [PATH_TARGET] });
            }
        } finally { this.busy.set(false); }
    }

    /**
     * The next card after the current one; the learner is never sent back to the welcome. `from` is the card the move was asked on: an
     * answer or a skip that arrives after the card has already moved on (a double click, a slow save) is ignored, not applied twice.
     */
    advance(from: FirstEntryCard | null = this.card()): void {
        const current = this.card();
        if (current === null || current === 'replay.deck' || from !== current) return;
        this.go(this.following(current), current);
    }

    back(): void {
        const current = this.card();
        if (current === 'replay.deck') { this.card.set('choose'); return; }
        const previous = this.history().at(-1);
        if (current === null || previous === undefined || previous === 'welcome') return;
        this.history.update(list => list.slice(0, -1));
        this.card.set(previous);
        void this.store.act('STEP', { stepKey: previous });
    }

    /** Whether there is a card to go back to (the first survey card and the replayed choice have none). */
    canGoBack(): boolean {
        const current = this.card();
        if (current === 'replay.deck') return true;
        const previous = this.history().at(-1);
        return previous !== undefined && previous !== 'welcome';
    }

    async answerGoal(goal: LearningGoal | null): Promise<void> {
        if (this.busy()) return;
        const from = this.card();
        this.busy.set(true);
        try { if (await this.goal.answer(goal)) this.advance(from); } finally { this.busy.set(false); }
    }

    /** «Пропустить»: the question is left unanswered. Ignored while an answer is being saved, so one press never moves the survey twice. */
    skip(): void {
        if (this.busy()) return;
        switch (this.card()) {
            case 'survey.goal': void this.answerGoal(null); break;
            case 'survey.interests': this.skipInterests(); break;
            default: this.advance();
        }
    }

    /** «Пропустить» on the interests card records the skip the way the old question did, so the Community does not ask again. */
    skipInterests(): void {
        // A save that is still on its way owns the card: the skip waits for it (the picker's own button was pressed).
        if (this.interests.busy()) return;
        const from = this.card();
        this.interests.save([]);
        this.advance(from);
    }

    async answerExperience(experience: Experience): Promise<void> {
        await this.saveAnswer({ experience });
    }

    async answerDays(weeklyTarget: number): Promise<void> {
        await this.saveAnswer({ weeklyTarget });
    }

    private async saveAnswer(changes: { experience?: Experience; weeklyTarget?: number }): Promise<void> {
        if (this.busy()) return;
        const from = this.card();
        this.busy.set(true);
        try {
            // A failed save leaves the answer unsent and the survey going: the question may be asked again from the profile.
            await this.store.setPreferences(changes);
            this.advance(from);
        } finally { this.busy.set(false); }
    }

    // ---- the branch choice -----------------------------------------------------------------------------------------------------------

    /** «Начать» on the branch choice. */
    async chooseBranch(branch: Branch): Promise<void> {
        if (this.busy()) return;
        if (branch === 'OWN' && this.replaying() && this.replayDecks().length > 0) { this.card.set('replay.deck'); return; }
        await this.beginBranch(branch, FIRST_STEP[branch]);
    }

    /** The replay of «Своя колода»: a new deck, or the deck the learner already has (the tour starts at its material). */
    async chooseReplayDeck(deckId: string | null): Promise<void> {
        if (this.busy()) return;
        if (deckId === null) { await this.beginBranch('OWN', FIRST_STEP.OWN); return; }
        this.deck.set(deckId);
        await this.beginBranch('OWN', 'own.material');
    }

    private async beginBranch(branch: Branch, from: StepKey): Promise<void> {
        this.busy.set(true);
        try {
            const result = await this.store.act('STEP', { stepKey: from, branch });
            // A command that was not delivered keeps the card for another try; an answer from the server (taken, or the journey is
            // elsewhere) closes it, and only a move the server took starts the tour.
            if (result.status === 'failed') return;
            this.close();
            if (isApplied(result)) await this.startTour(branch, from);
        } finally { this.busy.set(false); }
    }

    /** Starts the tour of `branch` (at `from`, or at its first step). A branch without a tour (the sample, until OB-203) starts nothing. */
    async startTour(branch: Branch, from?: StepKey): Promise<void> {
        await this.ensureCapabilities();
        const definition = tourFor(branch, this.context);
        if (definition === null) return;
        this.engine.start(definition, from);
    }

    /** «Не нашли подходящую?» → «Создать свою»: the tour moves to the own-deck branch. */
    async switchToOwn(): Promise<void> {
        this.hints.stuck.set(false);
        // The tour is replaced only when the server took the move: a refusal leaves the learner in the tour they are in.
        if (!isApplied(await this.store.act('STEP', { stepKey: 'own.details', branch: 'OWN' }))) return;
        this.engine.stop();
        await this.startTour('OWN', 'own.details');
    }

    // ---- the profile: replay ---------------------------------------------------------------------------------------------------------

    /** «Пройти знакомство заново»: the branch choice, with no survey and no change of its answers. */
    async replay(): Promise<void> {
        if (this.busy()) return;
        this.busy.set(true);
        try {
            // REPLAY is not allowed from a journey in progress (the table): a tour in progress is paused first, then replayed.
            if (this.store.journey()?.state === 'IN_PROGRESS' && !isApplied(await this.store.act('PAUSE'))) return;
            if (!isApplied(await this.store.act('REPLAY', { stepKey: 'choose' }))) return;
            this.engine.stop();
            this.history.set([]);
            this.replaying.set(true);
            this.card.set('choose');
            void this.prepare();
            void this.loadReplayDecks();
        } finally { this.busy.set(false); }
    }

    // ---- another device, and the end -------------------------------------------------------------------------------------------------

    /**
     * A journey that was paused (or left on a branch step) on another device or before a reload: the pause plaque, not an automatic jump
     * into the tour. Once per account per page load; an in-progress journey is first paused on the server, so the plaque's «Продолжить»
     * is a valid RESUME.
     */
    async resumeIfPaused(accountId: string): Promise<void> {
        if (this.resumedFor === accountId || this.engine.active()) return;
        const journey = this.store.journey();
        if (journey === null || !this.store.enabled()) return;
        // Decided once, from the journey as the page found it: a tour the learner starts later in this page is not «picked up».
        this.resumedFor = accountId;
        const key = journey.stepKey;
        if ((journey.state !== 'PAUSED' && journey.state !== 'IN_PROGRESS') || key === null || isFirstEntryKey(key)) return;
        const branch = journey.branch;
        if (branch === null) return;
        if (journey.state === 'IN_PROGRESS' && !isApplied(await this.store.act('PAUSE', { stepKey: key }))) return;
        await this.ensureCapabilities();
        const definition = tourFor(branch, this.context);
        if (definition === null || this.engine.active()) return;
        const onFinale = key === 'finale.ai' || key === 'finale.done';
        const known = definition.steps.some(step => step.key === key);
        this.engine.start(definition, known ? key : onFinale ? finaleKey(false) : STUDY_STEP[branch], { paused: true });
    }

    /** Signing out forgets the previous account's flow. */
    reset(): void {
        this.close();
        this.engine.stop();
        this.deck.set(null);
        this.member.set(null);
        this.resumedFor = null;
        this.capabilities = null;
        this.catalogueListed.set(null);
        this.replayDecks.set([]);
        this.store.reset();
    }

    private onEvent(event: string, payload: TourEventPayload | undefined): void {
        switch (event) {
            case TOUR_EVENTS.deckCreated:
            case TOUR_EVENTS.deckCopied:
                if (payload?.['deckId'] !== undefined) { this.deck.set(payload['deckId']); this.member.set(null); }
                break;
            case TOUR_EVENTS.materialPublished:
                if (payload?.['deckId'] !== undefined) this.deck.set(payload['deckId']);
                if (payload?.['memberKey'] !== undefined) this.member.set(payload['memberKey']);
                break;
            case TOUR_EVENTS.studyCompleted: this.onStudyCompleted(); break;
        }
    }

    /** The session is over: a tour that paused for it goes on at the finale (a tour the learner resumed meanwhile is left alone). */
    private onStudyCompleted(): void {
        const step = this.engine.step();
        if (this.engine.active() && this.engine.paused() && step !== null && Object.values(STUDY_STEP).includes(step.key)) {
            this.engine.resumeAt(finaleKey(this.aiAvailable()));
        }
    }

    // ---- what the first entry needs to know ------------------------------------------------------------------------------------------

    /** The survey's stores and the branch choice's facts, read once the surface opens. Failures leave a question or an option out. */
    private async prepare(): Promise<void> {
        this.interests.ensure();
        await Promise.all([this.goal.load(), this.ensureCapabilities(), this.loadCatalogue(), this.interestsReady()]);
    }

    /**
     * The interests are read when the account is known; the survey decides whether to ask about them only after that, or after a wait.
     * The watching effect is destroyed as soon as the wait ends, either way, so nothing is left running on the root injector.
     */
    private interestsReady(): Promise<void> {
        if (this.interests.ready()) return Promise.resolve();
        // Without the interests the card is left out: the survey never waits on one question.
        return new Promise<void>(resolve => {
            let watcher: EffectRef | null = null;
            const finish = (): void => { clearTimeout(timer); watcher?.destroy(); resolve(); };
            const timer = setTimeout(finish, INTERESTS_WAIT_MS);
            watcher = effect(() => { if (this.interests.ready()) finish(); }, { injector: this.injector });
        });
    }

    private ensureCapabilities(): Promise<void> {
        this.capabilities ??= firstValueFrom(this.injector.get(CapabilitiesApiService).read().pipe(catchError(() => of(CAPABILITIES_UNAVAILABLE))))
            .then(result => this.aiAvailable.set(result.aiGeneration.available));
        return this.capabilities;
    }

    private async loadCatalogue(): Promise<void> {
        if (!this.launched || this.catalogueListed() !== null) return;
        try {
            this.catalogueListed.set((await firstValueFrom(this.injector.get(CommunityApiService).facets())).listedCount);
        } catch { /* The catalogue could not be read: the option stays hidden. */ }
    }

    private async loadReplayDecks(): Promise<void> {
        try {
            const page = await firstValueFrom(this.injector.get(OwnDecksApiService).list());
            this.replayDecks.set(page.items.filter(deck => deck.fork?.preparing !== true).slice(0, REPLAY_DECKS)
                .map(deck => ({ deckId: deck.deckId, title: deck.metadata.title })));
        } catch { this.replayDecks.set([]); }
    }

    // ---- the survey's cards ----------------------------------------------------------------------------------------------------------

    /** Whether the learner still has a question on `card`: an answered question is not asked again. */
    private applicable(card: FirstEntryKey): boolean {
        switch (card) {
            case 'survey.goal': return this.goal.state() === 'ready' && !this.goal.answered();
            case 'survey.interests': return this.interests.onboarding();
            case 'survey.experience': return this.store.journey()?.experience === null;
            case 'survey.days': return this.store.journey()?.weeklyTarget === null;
            default: return true;
        }
    }

    /** The card after `card` that still has a question; the branch choice always remains. A replay goes straight to the choice. */
    private following(card: FirstEntryKey): FirstEntryKey {
        if (this.replaying()) return 'choose';
        return FIRST_ENTRY_KEYS.slice(FIRST_ENTRY_KEYS.indexOf(card) + 1).find(next => this.applicable(next)) ?? 'choose';
    }

    private go(next: FirstEntryKey, from: FirstEntryKey): void {
        this.history.update(list => [...list, from]);
        this.card.set(next);
        void this.store.act('STEP', { stepKey: next });
    }
}
