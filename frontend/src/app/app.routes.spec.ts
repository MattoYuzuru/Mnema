import { appRoutes } from './app.routes';
import { authGuard } from './core/guards/auth.guard';
import { OwnDeckCreatePageComponent } from './features/own-decks/own-deck-create-page.component';
import { OwnDeckDetailPageComponent } from './features/own-decks/own-deck-detail-page.component';
import { OwnDecksListPageComponent } from './features/own-decks/own-decks-list-page.component';
import { BrowsePageComponent } from './features/authoring/browse-page.component';
import { CapturePageComponent } from './features/authoring/capture-page.component';
import { ItemEditorPageComponent } from './features/authoring/item-editor-page.component';
import { ExerciseAuthoringPageComponent } from './features/authoring/exercise-authoring-page.component';
import { StudySessionPageComponent } from './features/study/study-session-page.component';

describe('appRoutes', () => {
    it('keeps the Identity callback and exposes only canonical private deck routes', () => {
        const paths = appRoutes.map(route => route.path);
        expect(paths).toContain('auth/callback');
        expect(paths).not.toContain('create-deck');
        expect(paths).not.toContain('decks/:userDeckId');
        expect(paths).not.toContain('decks/:userDeckId/browse');
        expect(paths).not.toContain('decks/:userDeckId/duplicates-review');
        expect(paths).not.toContain('decks/:userDeckId/review');
        expect(paths).not.toContain('wizard/visual-template-builder');
        expect(paths).not.toContain('templates/:templateId');
        expect(paths).not.toContain('templates');
        expect(paths).not.toContain('public-templates');

        const deckPaths = paths.filter(path => path?.startsWith('decks'));
        expect(deckPaths).toEqual([
            'decks', 'decks/new', 'decks/:deckId/study', 'decks/:deckId/materials/new',
            'decks/:deckId/materials/:memberKey/exercises/new', 'decks/:deckId/exercises/:exerciseId/edit',
            'decks/:deckId/materials/:memberKey/edit',
            'decks/:deckId/materials/:memberKey', 'decks/:deckId/materials', 'decks/:deckId/capture', 'decks/:deckId'
        ]);
        for (const path of deckPaths) {
            const route = appRoutes.find(candidate => candidate.path === path)!;
            expect(route.component).toBeUndefined();
            expect(route.loadComponent).toBeDefined();
            expect(route.canActivate).toEqual([authGuard]);
        }
        expect(appRoutes.find(route => route.path === 'decks/:deckId/materials/new')?.canDeactivate).toHaveSize(1);
        expect(appRoutes.find(route => route.path === 'decks/:deckId/materials/:memberKey/edit')?.canDeactivate).toHaveSize(1);
        expect(appRoutes.find(route => route.path === 'decks/:deckId/materials/:memberKey/exercises/new')?.canDeactivate).toHaveSize(1);
        expect(appRoutes.find(route => route.path === 'decks/:deckId/exercises/:exerciseId/edit')?.canDeactivate).toHaveSize(1);
        expect(appRoutes.find(route => route.path === 'decks/:deckId/study')?.canDeactivate).toHaveSize(1);
    });

    it('loads each own-deck page through its lazy route', async () => {
        const load = async (path: string) => appRoutes.find(route => route.path === path)!.loadComponent!();
        expect(await load('decks')).toBe(OwnDecksListPageComponent);
        expect(await load('decks/new')).toBe(OwnDeckCreatePageComponent);
        expect(await load('decks/:deckId/study')).toBe(StudySessionPageComponent);
        expect(await load('decks/:deckId/materials/new')).toBe(ItemEditorPageComponent);
        expect(await load('decks/:deckId/materials/:memberKey/edit')).toBe(ItemEditorPageComponent);
        expect(await load('decks/:deckId/materials/:memberKey/exercises/new')).toBe(ExerciseAuthoringPageComponent);
        expect(await load('decks/:deckId/exercises/:exerciseId/edit')).toBe(ExerciseAuthoringPageComponent);
        expect(await load('decks/:deckId/materials/:memberKey')).toBe(BrowsePageComponent);
        expect(await load('decks/:deckId/materials')).toBe(BrowsePageComponent);
        expect(await load('decks/:deckId/capture')).toBe(CapturePageComponent);
        expect(await load('decks/:deckId')).toBe(OwnDeckDetailPageComponent);
    });
});
