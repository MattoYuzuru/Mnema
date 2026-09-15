import { Routes } from '@angular/router';
import { HomePageComponent } from './home-page.component';
import { LoginPageComponent } from './login-page.component';
import { PrivacyPageComponent } from './privacy-page.component';
import { TermsPageComponent } from './terms-page.component';
import { authGuard } from './core/guards/auth.guard';

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
        loadComponent: () => import('./features/decks/decks-list.component').then(module => module.DecksListComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:userDeckId',
        loadComponent: () => import('./features/decks/deck-profile.component').then(module => module.DeckProfileComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:userDeckId/browse',
        loadComponent: () => import('./features/decks/card-browser.component').then(module => module.CardBrowserComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:userDeckId/duplicates-review',
        loadComponent: () => import('./features/decks/duplicate-review-page.component').then(module => module.DuplicateReviewPageComponent),
        canActivate: [authGuard]
    },
    {
        path: 'decks/:userDeckId/review',
        loadComponent: () => import('./features/decks/review-session.component').then(module => module.ReviewSessionComponent),
        canActivate: [authGuard]
    },
    {
        path: 'create-deck',
        loadComponent: () => import('./features/wizard/deck-wizard.component').then(module => module.DeckWizardComponent),
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
