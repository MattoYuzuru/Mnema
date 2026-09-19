import { appRoutes } from './app.routes';
import { authGuard } from './core/guards/auth.guard';
import { OwnDeckCreatePageComponent } from './features/own-decks/own-deck-create-page.component';
import { OwnDeckDetailPageComponent } from './features/own-decks/own-deck-detail-page.component';
import { OwnDecksListPageComponent } from './features/own-decks/own-decks-list-page.component';

describe('appRoutes', () => {
    it('keeps the Identity callback and exposes only canonical private deck routes', () => {
        const paths = appRoutes.map(route => route.path);
        expect(paths).toContain('auth/callback');
        expect(paths).not.toContain('create-deck');
        expect(paths).not.toContain('decks/:userDeckId');
        expect(paths).not.toContain('decks/:userDeckId/browse');
        expect(paths).not.toContain('decks/:userDeckId/duplicates-review');
        expect(paths).not.toContain('decks/:userDeckId/review');

        const deckPaths = paths.filter(path => path?.startsWith('decks'));
        expect(deckPaths).toEqual(['decks', 'decks/new', 'decks/:deckId']);
        for (const path of deckPaths) {
            const route = appRoutes.find(candidate => candidate.path === path)!;
            expect(route.component).toBeUndefined();
            expect(route.loadComponent).toBeDefined();
            expect(route.canActivate).toEqual([authGuard]);
        }
    });

    it('loads each own-deck page through its lazy route', async () => {
        const load = async (path: string) => appRoutes.find(route => route.path === path)!.loadComponent!();
        expect(await load('decks')).toBe(OwnDecksListPageComponent);
        expect(await load('decks/new')).toBe(OwnDeckCreatePageComponent);
        expect(await load('decks/:deckId')).toBe(OwnDeckDetailPageComponent);
    });
});
