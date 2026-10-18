import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { QuestLauncher } from '../../features/onboarding/quest-launcher.service';
import { OwnDecksApiService } from '../../features/own-decks/own-decks-api.service';
import { JourneyStore } from './journey.store';
import { onboardingText } from './onboarding-texts';
import { PathHint } from './path-hint.service';
import { PATH_PLACES, PathPlace, RecentDecks, isQuestRoute } from './path-places';
import { MilestoneKey, MilestoneRoute, milestoneDefinition } from './path.models';

const NO_DECKS: RecentDecks = { user: null, any: null };

/** The routes whose place is inside a deck: the account's decks are looked up only for these. */
const DECK_ROUTES: ReadonlySet<MilestoneRoute> = new Set<MilestoneRoute>(['STUDY', 'MATERIAL_NEW', 'EXERCISE_NEW', 'CAPTURE']);

/**
 * What a chevron of «Путь» does: the milestone's route key becomes an address and an anchor on the client (`path-places.ts`; the server
 * never sends a URL), the router goes there, and the in-place hint stands at the anchor. A route that leads into a deck looks up the
 * account's newest decks first (the list is newest first; the sample copy does not count as «own work»); with no deck to lead into the
 * learner is taken to the deck list with the deck hint, never to a dead end. A failed lookup is the same: the list.
 *
 * The side quests (media, explanation, voice, the Workshop) are tours of their own: the chevron opens the chooser of `QuestLauncher`
 * (the deck, or why the quest cannot start), and the quest then runs on the tour engine.
 */
@Injectable({ providedIn: 'root' })
export class PathNavigation {
    private readonly router = inject(Router);
    private readonly decksApi = inject(OwnDecksApiService);
    private readonly journey = inject(JourneyStore);
    private readonly hint = inject(PathHint);
    private readonly quests = inject(QuestLauncher);

    /** A chevron was followed and the navigation is not over: a second one waits. */
    readonly busy = signal(false);

    /** Follows the chevron of milestone `key`. */
    async go(key: MilestoneKey): Promise<void> {
        if (this.busy()) return;
        this.busy.set(true);
        try {
            const route = milestoneDefinition(key).route;
            if (isQuestRoute(route)) await this.quests.open(route, key);
            else await this.open(route, key);
        } finally { this.busy.set(false); }
    }

    private async open(route: MilestoneRoute, key: MilestoneKey): Promise<void> {
        const decks = DECK_ROUTES.has(route) ? await this.recentDecks() : NO_DECKS;
        const place = PATH_PLACES[route](decks);
        if (place === null) return;
        await this.show(place, key);
    }

    private async show(place: PathPlace, key: MilestoneKey): Promise<void> {
        const started = this.hint.begin({ title: onboardingText(`m.${key}`), text: onboardingText(place.text), anchor: place.anchor });
        const arrived = await this.router.navigate([...place.commands], place.queryParams === undefined ? {} : { queryParams: place.queryParams });
        if (!started) return;
        if (arrived) this.hint.arm(); else this.hint.dismiss();
    }

    /** The newest deck of the account and the newest one that is its own work (the sample copy is not); none when the list cannot be read. */
    private async recentDecks(): Promise<RecentDecks> {
        try {
            const page = await firstValueFrom(this.decksApi.list());
            const sample = this.journey.view()?.sampleDeck.deckId?.toLowerCase() ?? null;
            const user = page.items.find(deck => deck.deckId.toLowerCase() !== sample);
            return { user: user?.deckId ?? null, any: page.items[0]?.deckId ?? null };
        } catch {
            return NO_DECKS;
        }
    }
}
