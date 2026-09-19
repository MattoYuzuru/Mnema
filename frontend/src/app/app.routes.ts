import { CanDeactivateFn, Routes } from '@angular/router';
import { HomePageComponent } from './home-page.component';
import { LoginPageComponent } from './login-page.component';
import { PrivacyPageComponent } from './privacy-page.component';
import { TermsPageComponent } from './terms-page.component';
import { authGuard } from './core/guards/auth.guard';
import type { ItemEditorPageComponent } from './features/authoring/item-editor-page.component';

const lazyCanLeaveItemEditor: CanDeactivateFn<ItemEditorPageComponent> = (...args) =>
    import('./features/authoring/item-editor-page.component').then(module => module.canLeaveItemEditor(args[0]));

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
        path: 'my-study',
        loadComponent: () => import('./features/my-study/my-study.component').then(module => module.MyStudyComponent),
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
        path: 'decks/:deckId/materials/new',
        loadComponent: () => import('./features/authoring/item-editor-page.component')
            .then(module => module.ItemEditorPageComponent),
        canActivate: [authGuard],
        canDeactivate: [lazyCanLeaveItemEditor]
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
        path: 'decks/:deckId/materials',
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
        path: 'decks/:deckId',
        loadComponent: () => import('./features/own-decks/own-deck-detail-page.component')
            .then(module => module.OwnDeckDetailPageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'wizard/visual-template-builder',
        loadComponent: () => import('./features/wizard/visual-template-builder.component').then(module => module.VisualTemplateBuilderComponent),
        canActivate: [authGuard]
    },
    {
        path: 'public-decks',
        loadComponent: () => import('./features/public-decks/public-decks-catalog.component').then(module => module.PublicDecksCatalogComponent)
    },
    {
        path: 'public-decks/:deckId/browse',
        loadComponent: () => import('./features/public-decks/public-card-browser.component').then(module => module.PublicCardBrowserComponent)
    },
    {
        path: 'templates/:templateId',
        loadComponent: () => import('./features/templates/template-profile.component').then(module => module.TemplateProfileComponent),
        canActivate: [authGuard]
    },
    {
        path: 'templates',
        loadComponent: () => import('./features/templates/templates-list.component').then(module => module.TemplatesListComponent),
        canActivate: [authGuard]
    },
    {
        path: 'public-templates',
        loadComponent: () => import('./features/templates/public-templates.component').then(module => module.PublicTemplatesComponent),
        canActivate: [authGuard]
    },
    {
        path: 'settings',
        loadComponent: () => import('./features/settings/settings.component').then(module => module.SettingsComponent),
        canActivate: [authGuard]
    },
    {
        path: 'admin',
        loadComponent: () => import('./features/admin/admin-panel.component').then(module => module.AdminPanelComponent),
        canActivate: [authGuard]
    },
    { path: 'privacy', component: PrivacyPageComponent },
    { path: 'terms', component: TermsPageComponent },
    { path: '**', redirectTo: '' }
];
