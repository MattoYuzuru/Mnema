import { appRoutes } from './app.routes';
import { authGuard } from './core/guards/auth.guard';
import { OwnDeckCreatePageComponent } from './features/own-decks/own-deck-create-page.component';
import { OwnDeckDetailPageComponent } from './features/own-decks/own-deck-detail-page.component';
import { PaymentReturnComponent } from './features/billing/payment-return.component';
import { OwnDecksListPageComponent } from './features/own-decks/own-decks-list-page.component';
import { BrowsePageComponent } from './features/authoring/browse-page.component';
import { CapturePageComponent } from './features/authoring/capture-page.component';
import { ItemEditorPageComponent } from './features/authoring/item-editor-page.component';
import { ExerciseAuthoringPageComponent } from './features/authoring/exercise-authoring-page.component';
import { NewMaterialPageComponent } from './features/generation/new-material-page.component';
import { ExerciseBuilderPageComponent } from './features/generation/exercise-builder-page.component';
import { WorkshopPageComponent } from './features/generation/workshop-page.component';
import { StudySessionPageComponent } from './features/study/study-session-page.component';
import { StyleguidePageComponent } from './styleguide/styleguide-page.component';

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
            'decks', 'decks/new', 'decks/:deckId/study', 'decks/:deckId/materials/new', 'decks/:deckId/exercises/generate',
            'decks/:deckId/materials/:memberKey/exercises/new', 'decks/:deckId/exercises/:exerciseId/edit',
            'decks/:deckId/materials/:memberKey/edit',
            'decks/:deckId/materials/:memberKey', 'decks/:deckId/capture', 'decks/:deckId/workshop/:sessionId', 'decks/:deckId'
        ]);
        // The material list lives in the Deck hub; no separate list route may come back (greenfield: no redirect either).
        expect(paths).not.toContain('decks/:deckId/materials');
        for (const path of deckPaths) {
            const route = appRoutes.find(candidate => candidate.path === path)!;
            expect(route.component).toBeUndefined();
            expect(route.loadComponent).toBeDefined();
            expect(route.canActivate).toEqual([authGuard]);
        }
        expect(appRoutes.find(route => route.path === 'decks/:deckId/materials/new')?.canDeactivate).toHaveLength(1);
        expect(appRoutes.find(route => route.path === 'decks/:deckId/materials/:memberKey/edit')?.canDeactivate).toHaveLength(1);
        expect(appRoutes.find(route => route.path === 'decks/:deckId/materials/:memberKey/exercises/new')?.canDeactivate).toHaveLength(1);
        expect(appRoutes.find(route => route.path === 'decks/:deckId/exercises/:exerciseId/edit')?.canDeactivate).toHaveLength(1);
        expect(appRoutes.find(route => route.path === 'decks/:deckId/study')?.canDeactivate).toHaveLength(1);
    });

    it('guards the paywall and leaves the AI explanation public', () => {
        const plans = appRoutes.find(route => route.path === 'plans')!;
        expect(plans.loadComponent).toBeDefined();
        expect(plans.canActivate).toEqual([authGuard]);
        expect(appRoutes.find(route => route.path === 'ai')?.canActivate).toBeUndefined();
    });

    it('guards the payment return page and loads it lazily', async () => {
        const payment = appRoutes.find(route => route.path === 'plans/payment/:orderId')!;
        expect(payment.canActivate).toEqual([authGuard]);
        expect(await payment.loadComponent!()).toBe(PaymentReturnComponent);
    });

    it('guards a lazy owner shell and protects transactional children before leaving', () => {
        const manage = appRoutes.find(route => route.path === 'manage')!;
        expect(manage.canActivate).toEqual([authGuard]); expect(manage.loadComponent).toBeDefined();
        expect(manage.children?.map(child => child.path)).toEqual(['', 'users', 'users/:accountId', 'promos', 'events', 'support', 'support/:ticketId', 'audit']);
        for (const path of ['users', 'users/:accountId', 'promos', 'events', 'support', 'support/:ticketId']) expect(manage.children?.find(child => child.path === path)?.canDeactivate).toHaveLength(1);
    });

    it('loads each own-deck page through its lazy route', async () => {
        const load = async (path: string) => appRoutes.find(route => route.path === path)!.loadComponent!();
        expect(await load('decks')).toBe(OwnDecksListPageComponent);
        expect(await load('decks/new')).toBe(OwnDeckCreatePageComponent);
        expect(await load('decks/:deckId/study')).toBe(StudySessionPageComponent);
        expect(await load('decks/:deckId/materials/new')).toBe(NewMaterialPageComponent);
        expect(await load('decks/:deckId/exercises/generate')).toBe(ExerciseBuilderPageComponent);
        expect(await load('decks/:deckId/workshop/:sessionId')).toBe(WorkshopPageComponent);
        expect(await load('decks/:deckId/materials/:memberKey/edit')).toBe(ItemEditorPageComponent);
        expect(await load('decks/:deckId/materials/:memberKey/exercises/new')).toBe(ExerciseAuthoringPageComponent);
        expect(await load('decks/:deckId/exercises/:exerciseId/edit')).toBe(ExerciseAuthoringPageComponent);
        expect(await load('decks/:deckId/materials/:memberKey')).toBe(BrowsePageComponent);
        expect(await load('decks/:deckId/capture')).toBe(CapturePageComponent);
        expect(await load('decks/:deckId')).toBe(OwnDeckDetailPageComponent);
    });

    it('registers the styleguide as an open lazy route in a development build, before the catch-all', async () => {
        // Tests run on the development build (ngDevMode is on). The production build drops the route and its chunk:
        // `scripts/verify-no-styleguide.mjs` checks the built output.
        const paths = appRoutes.map(route => route.path);
        expect(paths).toContain('styleguide');
        expect(paths.indexOf('styleguide')).toBeLessThan(paths.indexOf('**'));
        const route = appRoutes.find(candidate => candidate.path === 'styleguide')!;
        expect(route.component).toBeUndefined();
        expect(route.canActivate).toBeUndefined();
        expect(await route.loadComponent!()).toBe(StyleguidePageComponent);
    });
});
