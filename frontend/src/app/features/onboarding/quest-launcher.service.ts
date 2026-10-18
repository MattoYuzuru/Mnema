import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AttentionCoordinator } from '../../core/onboarding/attention-coordinator';
import { JourneyStore } from '../../core/onboarding/journey.store';
import { onboardingText } from '../../core/onboarding/onboarding-texts';
import { QuestRoute } from '../../core/onboarding/path-places';
import { MilestoneKey } from '../../core/onboarding/path.models';
import { QuestAction, QuestDeckOption, QuestNotice, QuestPhase } from '../../core/onboarding/quest.models';
import { SAMPLE_QUESTS, budgetExhausted, capabilityOn, needsOwnDeck } from '../../core/onboarding/quest-conditions';
import { TourEngine } from '../../core/onboarding/tour-engine.service';
import { ToastService } from '../../core/notifications/toast.service';
import { CAPABILITIES_UNAVAILABLE, CapabilitiesApiService, LearningCapabilities } from '../authoring/capabilities-api.service';
import { ItemApiService } from '../authoring/item-api.service';
import { OwnDecksApiService } from '../own-decks/own-decks-api.service';
import { DeckHubApiService } from '../own-decks/hub/deck-hub-api.service';
import { UsageApiService } from '../usage/usage-api.service';
import { UsageSnapshot } from '../usage/usage.models';
import { OnboardingFlow } from './onboarding-flow.service';
import { QuestContext, questDefinition } from './quest-definitions';

interface Request {
    readonly route: QuestRoute;
    readonly milestone: MilestoneKey;
}

/**
 * The start of a side quest (OB-304): what happens between the chevron of a milestone and the first step. It reads what the quest
 * depends on (the capability, the account's AI budget, the decks), says at once why a quest cannot start and what to do instead (never a
 * dead end), lets the learner choose the deck (their own, and the sample copy only where the quest may demonstrate on it), checks that
 * deck for what the quest needs, and then starts the quest on the same engine as the main tour. It never makes a deck and never writes
 * to the journey. A quest does not start while the main tour is on the screen (running, or folded into its plaque) or the first entry is
 * open: the coordinator and the flow say so. When a quest ends with its milestone the view is read again, so «Путь» shows it.
 */
@Injectable({ providedIn: 'root' })
export class QuestLauncher {
    private readonly engine = inject(TourEngine);
    private readonly attention = inject(AttentionCoordinator);
    private readonly flow = inject(OnboardingFlow);
    private readonly journey = inject(JourneyStore);
    private readonly decksApi = inject(OwnDecksApiService);
    private readonly capabilitiesApi = inject(CapabilitiesApiService);
    private readonly usageApi = inject(UsageApiService);
    private readonly hubApi = inject(DeckHubApiService);
    private readonly itemsApi = inject(ItemApiService);
    private readonly toasts = inject(ToastService);

    private readonly state = signal<Request | null>(null);
    /** The quest being prepared, or `null` (no chooser on the screen). */
    readonly request = this.state.asReadonly();
    readonly phase = signal<QuestPhase>('loading');
    readonly decks = signal<readonly QuestDeckOption[]>([]);
    readonly selected = signal<string | null>(null);
    readonly notice = signal<QuestNotice | null>(null);
    /** The title of the quest: the milestone's. */
    readonly milestone = computed(() => this.state()?.milestone ?? null);

    private epoch = 0;
    /** The account's sample copy, read with the decks. */
    private sample: QuestDeckOption | null = null;

    constructor() {
        // A quest that ended with its thing done: say so, and read «Путь» again (the milestone is written in the transaction of the action).
        let seen: unknown = null;
        effect(() => {
            const end = this.engine.lastEnd();
            if (end === seen) return;
            seen = end;
            if (end === null || end.reason !== 'completed' || !end.definitionId.startsWith('quest.')) return;
            untracked(() => {
                this.toasts.echo(onboardingText('quest.done'));
                void this.journey.load({ refresh: true });
            });
        });
    }

    /** Whether the main tour or the first entry is on the screen, so a quest would sit over it. */
    blockedByTour(): boolean {
        const mainTour = this.engine.active() && !this.engine.isQuest();
        return mainTour || this.flow.card() !== null || this.flow.pausedCard() !== null || !this.attention.canShow('tour');
    }

    /** Opens the chooser for a quest: reads what it depends on and lands on the choice, or on the reason it cannot start. */
    async open(route: QuestRoute, milestone: MilestoneKey): Promise<void> {
        const epoch = ++this.epoch;
        this.state.set({ route, milestone });
        this.decks.set([]);
        this.selected.set(null);
        this.notice.set(null);
        this.sample = null;
        this.phase.set('loading');
        if (this.blockedByTour()) { this.block({ text: onboardingText('quest.blocked.tour'), actions: [] }); return; }
        try {
            const [capabilities, usage, page] = await Promise.all([
                firstValueFrom(this.capabilitiesApi.read()).catch((): LearningCapabilities => CAPABILITIES_UNAVAILABLE),
                needsBudget(route) ? firstValueFrom(this.usageApi.load()).catch((): UsageSnapshot | null => null) : Promise.resolve(null),
                firstValueFrom(this.decksApi.list())
            ]);
            if (epoch !== this.epoch) return;
            if (!capabilityOn(route, capabilities)) { this.block({ text: onboardingText('quest.blocked.unavailable'), actions: [] }); return; }
            const sample = this.sample = this.sampleOption(page.items);
            const own = page.items.filter(deck => deck.deckId.toLowerCase() !== sample?.deckId.toLowerCase())
                .map((deck): QuestDeckOption => ({ deckId: deck.deckId, title: deck.metadata.title, sample: false }));
            if (budgetExhausted(route, usage)) { this.block(this.budgetNotice(route, sample)); return; }
            const options = [...own, ...(sample !== null && SAMPLE_QUESTS.includes(route) ? [sample] : [])];
            if (options.length === 0 || (needsOwnDeck(route) && own.length === 0)) {
                this.block({ text: onboardingText('quest.nodeck'), actions: [{ kind: 'link', label: onboardingText('replay.own.new'), commands: ['/decks', 'new'] }] });
                return;
            }
            this.decks.set(options);
            this.selected.set(options[0].deckId);
            this.phase.set('choose');
        } catch {
            if (epoch === this.epoch) this.phase.set('error');
        }
    }

    choose(deckId: string): void { this.selected.set(deckId); }

    /** «Начать»: checks the chosen deck for what the quest needs, then starts the quest. */
    async start(deckId: string | null = this.selected()): Promise<void> {
        const request = this.state();
        const option = this.decks().find(candidate => candidate.deckId === deckId);
        if (request === null || option === undefined) return;
        const epoch = this.epoch;
        this.selected.set(option.deckId);
        this.phase.set('checking');
        try {
            const context = await this.check(request.route, option);
            if (epoch !== this.epoch) return;
            if ('notice' in context) { this.block(context.notice); return; }
            // The tour may have come up while the learner chose.
            if (this.blockedByTour()) { this.block({ text: onboardingText('quest.blocked.tour'), actions: [] }); return; }
            this.close();
            this.engine.start(questDefinition(request.route, context));
        } catch {
            if (epoch === this.epoch) this.phase.set('error');
        }
    }

    /** «Не сейчас», Escape: no quest. */
    close(): void {
        this.epoch++;
        this.state.set(null);
        this.notice.set(null);
        this.decks.set([]);
        this.selected.set(null);
    }

    /** The action a refusal offers: the sample deck starts the quest there, a link leaves the chooser for the page. */
    async act(action: QuestAction): Promise<void> {
        if (action.kind === 'link') { this.close(); return; }
        this.decks.set([this.sample ?? { deckId: action.deckId, title: '', sample: true }]);
        await this.start(action.deckId);
    }

    retry(): void {
        const request = this.state();
        if (request !== null) void this.open(request.route, request.milestone);
    }

    private block(notice: QuestNotice): void {
        this.notice.set(notice);
        this.phase.set('blocked');
    }

    /** The account's sample copy among its decks, when it has one. */
    private sampleOption(items: readonly { readonly deckId: string; readonly metadata: { readonly title: string } }[]): QuestDeckOption | null {
        const id = this.journey.view()?.sampleDeck.deckId ?? null;
        if (id === null) return null;
        const found = items.find(deck => deck.deckId.toLowerCase() === id.toLowerCase());
        return { deckId: id, title: found?.metadata.title ?? '', sample: true };
    }

    private budgetNotice(route: QuestRoute, sample: QuestDeckOption | null): QuestNotice {
        const left = this.journey.view()?.sampleDeck.freeChecksLeft ?? 0;
        if (SAMPLE_QUESTS.includes(route) && sample !== null && left > 0) {
            return {
                text: onboardingText('quest.blocked.budget.sample', { n: left }),
                actions: [{ kind: 'sample', label: onboardingText('quest.sample.use'), deckId: sample.deckId }]
            };
        }
        return { text: onboardingText('quest.blocked.budget'), actions: [{ kind: 'link', label: onboardingText('ai.plans'), commands: ['/plans'] }] };
    }

    /** What the chosen deck must have for the quest, and the context the quest's definition needs; or the notice that says what is missing. */
    private async check(route: QuestRoute, option: QuestDeckOption): Promise<QuestContext | { readonly notice: QuestNotice }> {
        const base: QuestContext = { deckId: option.deckId, sample: option.sample, memberKey: null };
        const deck = ['/decks', option.deckId];
        switch (route) {
            case 'QUEST_WORKSHOP': {
                const insights = await firstValueFrom(this.hubApi.insights(option.deckId));
                return insights.captures.open > 0 ? base : { notice: { text: onboardingText('quest.nonotes'),
                    actions: [{ kind: 'link', label: onboardingText('quest.nonotes.action'), commands: [...deck, 'capture'] }] } };
            }
            case 'QUEST_EXPLAIN': {
                const insights = await firstValueFrom(this.hubApi.insights(option.deckId));
                if (insights.exercisesByMechanic.FREE_RESPONSE > 0) return base;
                const sample = this.sample;
                const left = this.journey.view()?.sampleDeck.freeChecksLeft ?? 0;
                return { notice: { text: onboardingText('quest.noai'), actions: [
                    ...(sample !== null && !option.sample && left > 0 ? [{ kind: 'sample' as const, label: onboardingText('quest.sample.use'), deckId: sample.deckId }] : []),
                    { kind: 'link', label: onboardingText('quest.noai.action'), commands: deck }
                ] } };
            }
            case 'QUEST_MEDIA': {
                const page = await firstValueFrom(this.itemsApi.list(option.deckId, { limit: 20 }));
                const material = page.items.find(item => !item.unavailable);
                return material !== undefined ? { ...base, memberKey: material.memberKey } : { notice: { text: onboardingText('quest.nomaterial'),
                    actions: [{ kind: 'link', label: onboardingText('quest.nomaterial.action'), commands: [...deck, 'materials', 'new'] }] } };
            }
            case 'QUEST_VOICE': return base;
        }
    }
}

function needsBudget(route: QuestRoute): boolean {
    return route !== 'QUEST_MEDIA';
}
