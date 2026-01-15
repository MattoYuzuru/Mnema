import { Routes } from '@angular/router';
import { HomePageComponent } from './home-page.component';
import { LoginPageComponent } from './login-page.component';
import { ProfilePageComponent } from './profile-page.component';
import { PrivacyPageComponent } from './privacy-page.component';
import { TermsPageComponent } from './terms-page.component';
import { MyStudyComponent } from './features/my-study/my-study.component';
import { DecksListComponent } from './features/decks/decks-list.component';
import { DeckProfileComponent } from './features/decks/deck-profile.component';
import { CardBrowserComponent } from './features/decks/card-browser.component';
import { ReviewSessionComponent } from './features/decks/review-session.component';
import { PublicDecksCatalogComponent } from './features/public-decks/public-decks-catalog.component';
import { PublicCardBrowserComponent } from './features/public-decks/public-card-browser.component';
import { TemplatesListComponent } from './features/templates/templates-list.component';
import { PublicTemplatesComponent } from './features/templates/public-templates.component';
import { TemplateProfileComponent } from './features/templates/template-profile.component';
import { DeckWizardComponent } from './features/wizard/deck-wizard.component';
import { VisualTemplateBuilderComponent } from './features/wizard/visual-template-builder.component';
import { SettingsComponent } from './features/settings/settings.component';
import { authGuard } from './core/guards/auth.guard';

export const appRoutes: Routes = [
    { path: '', component: HomePageComponent },
    { path: 'login', component: LoginPageComponent },
    { path: 'register', component: LoginPageComponent },
    { path: 'profile', component: ProfilePageComponent, canActivate: [authGuard] },
    { path: 'my-study', component: MyStudyComponent, canActivate: [authGuard] },
    { path: 'decks', component: DecksListComponent, canActivate: [authGuard] },
    { path: 'decks/:userDeckId', component: DeckProfileComponent, canActivate: [authGuard] },
    { path: 'decks/:userDeckId/browse', component: CardBrowserComponent, canActivate: [authGuard] },
    { path: 'decks/:userDeckId/review', component: ReviewSessionComponent, canActivate: [authGuard] },
    { path: 'create-deck', component: DeckWizardComponent, canActivate: [authGuard] },
    { path: 'wizard/visual-template-builder', component: VisualTemplateBuilderComponent, canActivate: [authGuard] },
    { path: 'public-decks', component: PublicDecksCatalogComponent },
    { path: 'public-decks/:deckId/browse', component: PublicCardBrowserComponent },
    { path: 'templates/:templateId', component: TemplateProfileComponent, canActivate: [authGuard] },
    { path: 'templates', component: TemplatesListComponent, canActivate: [authGuard] },
    { path: 'public-templates', component: PublicTemplatesComponent, canActivate: [authGuard] },
    { path: 'settings', component: SettingsComponent, canActivate: [authGuard] },
    { path: 'privacy', component: PrivacyPageComponent },
    { path: 'terms', component: TermsPageComponent },
    { path: '**', redirectTo: '' }
];
