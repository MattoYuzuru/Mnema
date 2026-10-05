import { CanDeactivateFn, Routes } from '@angular/router';
import { HomePageComponent } from './home-page.component';
import { LoginPageComponent } from './login-page.component';
import { PrivacyPageComponent } from './privacy-page.component';
import { TermsPageComponent } from './terms-page.component';
import { authGuard } from './core/guards/auth.guard';
import type { ItemEditorPageComponent } from './features/authoring/item-editor-page.component';
import type { ExerciseAuthoringPageComponent } from './features/authoring/exercise-authoring-page.component';
import type { StudySessionPageComponent } from './features/study/study-session-page.component';
import type { NewMaterialPageComponent } from './features/generation/new-material-page.component';

const lazyCanLeaveItemEditor: CanDeactivateFn<ItemEditorPageComponent> = (...args) =>
    import('./features/authoring/item-editor-page.component').then(module => module.canLeaveItemEditor(args[0]));
const lazyCanLeaveNewMaterial: CanDeactivateFn<NewMaterialPageComponent> = (...args) =>
    import('./features/generation/new-material-page.component').then(module => module.canLeaveNewMaterial(args[0]));
const lazyCanLeaveExerciseAuthoring: CanDeactivateFn<ExerciseAuthoringPageComponent> = (...args) =>
    import('./features/authoring/exercise-authoring-page.component')
        .then(module => module.canLeaveExerciseAuthoring(args[0]));
const lazyCanLeaveStudySession: CanDeactivateFn<StudySessionPageComponent> = (...args) =>
    import('./features/study/study-session-page.component').then(module => module.canLeaveStudySession(args[0]));

/**
 * The living styleguide exists in development builds only. `ng build --configuration production` (optimization.scripts) makes the
 * application builder define `ngDevMode` as `false` (angular.dev/reference/configs/workspace-config#optimization-configuration,
 * @angular/build `application-code-bundle`), esbuild folds the condition and drops the dynamic `import()`, so neither the route
 * nor the lazy chunk reaches the bundle. `isDevMode()` is a runtime call and would keep the code in production, which is why
 * the build-time constant is used directly. `scripts/verify-no-styleguide.mjs` proves it on the built output; the owner opens
 * the page with `npm start` (`ng serve`) at /styleguide. See docs/frontend/styleguide.md.
 */
const developmentOnlyRoutes: Routes = typeof ngDevMode === 'undefined' || ngDevMode
    ? [{
        path: 'styleguide',
        loadComponent: () => import('./styleguide/styleguide-page.component').then(module => module.StyleguidePageComponent)
    }]
    : [];

export const appRoutes: Routes = [
    { path: '', component: HomePageComponent },
    { path: 'login', component: LoginPageComponent },
    { path: 'register', component: LoginPageComponent },
    { path: 'auth/callback', loadComponent: () => import('./auth-callback.component').then(module => module.AuthCallbackComponent) },
    {
        path: 'profile',
        loadComponent: () => import('./profile-page.component').then(module => module.ProfilePageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks',
        loadComponent: () => import('./features/own-decks/own-decks-list-page.component')
            .then(module => module.OwnDecksListPageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/new',
        loadComponent: () => import('./features/own-decks/own-deck-create-page.component')
            .then(module => module.OwnDeckCreatePageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:deckId/study',
        loadComponent: () => import('./features/study/study-session-page.component')
            .then(module => module.StudySessionPageComponent),
        canActivate: [authGuard],
        canDeactivate: [lazyCanLeaveStudySession]
    },
    {
        // The composer where AI generation is available, the plain editor (also reachable with ?write=1) where it is not.
        path: 'decks/:deckId/materials/new',
        loadComponent: () => import('./features/generation/new-material-page.component')
            .then(module => module.NewMaterialPageComponent),
        canActivate: [authGuard],
        canDeactivate: [lazyCanLeaveNewMaterial]
    },
    {
        // The exercise builder (AI-13): `?members=<key>,<key>` or `?all=1&except=<key>`; the page reads the revisions itself.
        path: 'decks/:deckId/exercises/generate',
        loadComponent: () => import('./features/generation/exercise-builder-page.component')
            .then(module => module.ExerciseBuilderPageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:deckId/materials/:memberKey/exercises/new',
        loadComponent: () => import('./features/authoring/exercise-authoring-page.component')
            .then(module => module.ExerciseAuthoringPageComponent),
        canActivate: [authGuard],
        canDeactivate: [lazyCanLeaveExerciseAuthoring]
    },
    {
        path: 'decks/:deckId/exercises/:exerciseId/edit',
        loadComponent: () => import('./features/authoring/exercise-authoring-page.component')
            .then(module => module.ExerciseAuthoringPageComponent),
        canActivate: [authGuard],
        canDeactivate: [lazyCanLeaveExerciseAuthoring]
    },
    {
        path: 'decks/:deckId/materials/:memberKey/edit',
        loadComponent: () => import('./features/authoring/item-editor-page.component')
            .then(module => module.ItemEditorPageComponent),
        canActivate: [authGuard],
        canDeactivate: [lazyCanLeaveItemEditor]
    },
    {
        path: 'decks/:deckId/materials/:memberKey',
        loadComponent: () => import('./features/authoring/browse-page.component')
            .then(module => module.BrowsePageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:deckId/capture',
        loadComponent: () => import('./features/authoring/capture-page.component')
            .then(module => module.CapturePageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:deckId/workshop/:sessionId',
        loadComponent: () => import('./features/generation/workshop-page.component')
            .then(module => module.WorkshopPageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:deckId',
        loadComponent: () => import('./features/own-decks/own-deck-detail-page.component')
            .then(module => module.OwnDeckDetailPageComponent),
        canActivate: [authGuard]
    },
    { path: 'privacy', component: PrivacyPageComponent },
    { path: 'terms', component: TermsPageComponent },
    ...developmentOnlyRoutes,
    { path: '**', redirectTo: '' }
];
