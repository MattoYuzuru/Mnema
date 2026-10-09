import { CanDeactivateFn, Routes } from '@angular/router';
import { isAdminHost } from './app.config';
import type { AdminPromosPageComponent } from './features/admin/admin-promos-page.component';
import type { AdminUsersPageComponent } from './features/admin/admin-users-page.component';
import type { AdminSupportPageComponent } from './features/admin/admin-support-page.component';
import { HomePageComponent } from './home-page.component';
import { LoginPageComponent } from './login-page.component';
import { PrivacyPageComponent } from './privacy-page.component';
import { TermsPageComponent } from './terms-page.component';
import { authGuard } from './core/guards/auth.guard';
import type { ItemEditorPageComponent } from './features/authoring/item-editor-page.component';
import type { ExerciseAuthoringPageComponent } from './features/authoring/exercise-authoring-page.component';
import type { StudySessionPageComponent } from './features/study/study-session-page.component';
import type { NewMaterialPageComponent } from './features/generation/new-material-page.component';
import type { ManageEventsPageComponent } from './features/events/manage-events-page.component';

const lazyCanLeaveEventEditor: CanDeactivateFn<ManageEventsPageComponent> = (...args) =>
    import('./features/events/manage-events-page.component').then(module => module.canLeaveEventEditor(args[0]));

const lazyCanLeaveAdminPromos: CanDeactivateFn<AdminPromosPageComponent> = component => import('./features/admin/admin-promos-page.component').then(module => module.canLeaveAdminPromos(component));
const lazyCanLeaveAdminUsers: CanDeactivateFn<AdminUsersPageComponent> = component => import('./features/admin/admin-users-page.component').then(module => module.canLeaveAdminUsers(component));
const lazyCanLeaveAdminSupport: CanDeactivateFn<AdminSupportPageComponent> = component => import('./features/admin/admin-support-page.component').then(module => module.canLeaveAdminSupport(component));

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
    isAdminHost ? { path: '', pathMatch: 'full', redirectTo: 'manage' } : { path: '', component: HomePageComponent },
    { path: 'events', loadComponent: () => import('./features/events/events-page.component').then(module => module.EventsPageComponent) },
    // The learner host keeps the event editor as it was before the console: no owner shell, no admin client, only the events-owner check.
    ...(isAdminHost ? [] : [{ path: 'manage/events', canActivate: [authGuard], canDeactivate: [lazyCanLeaveEventEditor], data: { standalone: true },
        loadComponent: () => import('./features/events/manage-events-page.component').then(module => module.ManageEventsPageComponent) }]),
    { path: 'manage', canActivate: [authGuard], loadComponent: () => import('./features/admin/admin-shell.component').then(module => module.AdminShellComponent),
        children: [
            { path: '', pathMatch: 'full', loadComponent: () => import('./features/admin/admin-report-page.component').then(module => module.AdminReportPageComponent) },
            { path: 'users', canDeactivate: [lazyCanLeaveAdminUsers], loadComponent: () => import('./features/admin/admin-users-page.component').then(module => module.AdminUsersPageComponent) },
            { path: 'users/:accountId', canDeactivate: [lazyCanLeaveAdminUsers], loadComponent: () => import('./features/admin/admin-users-page.component').then(module => module.AdminUsersPageComponent) },
            { path: 'promos', canDeactivate: [lazyCanLeaveAdminPromos], loadComponent: () => import('./features/admin/admin-promos-page.component').then(module => module.AdminPromosPageComponent) },
            { path: 'events', canDeactivate: [lazyCanLeaveEventEditor], loadComponent: () => import('./features/events/manage-events-page.component').then(module => module.ManageEventsPageComponent) },
            { path: 'support', canDeactivate: [lazyCanLeaveAdminSupport], loadComponent: () => import('./features/admin/admin-support-page.component').then(module => module.AdminSupportPageComponent) },
            { path: 'support/:ticketId', canDeactivate: [lazyCanLeaveAdminSupport], loadComponent: () => import('./features/admin/admin-support-page.component').then(module => module.AdminSupportPageComponent) },
            { path: 'audit', loadComponent: () => import('./features/admin/admin-audit-page.component').then(module => module.AdminAuditPageComponent) }
        ] },
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
    {
        path: 'plans',
        loadComponent: () => import('./features/plans/plans-page.component').then(module => module.PlansPageComponent),
        canActivate: [authGuard]
    },
    {
        // Where the bank sends the reader back; it reads the order and ignores everything the bank appended to the address.
        path: 'plans/payment/:orderId',
        loadComponent: () => import('./features/billing/payment-return.component').then(module => module.PaymentReturnComponent),
        canActivate: [authGuard]
    },
    // Public: the footer and the «Что это?» toggletips of the AI actions link here, signed in or not.
    {
        path: 'ai',
        loadComponent: () => import('./features/ai-info/ai-page.component').then(module => module.AiPageComponent)
    },
    { path: 'privacy', component: PrivacyPageComponent },
    { path: 'terms', component: TermsPageComponent },
    ...developmentOnlyRoutes,
    { path: '**', redirectTo: '' }
];
