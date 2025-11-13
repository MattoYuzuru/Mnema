import { Routes } from '@angular/router';
import { HomePageComponent } from './home-page.component';
import { LoginPageComponent } from './login-page.component';
import { ProfilePageComponent } from './profile-page.component';

export const appRoutes: Routes = [
    { path: '', component: HomePageComponent },
    { path: 'login', component: LoginPageComponent },
    // при желании /register можно повесить на тот же компонент
    { path: 'register', component: LoginPageComponent },
    { path: 'profile', component: ProfilePageComponent },
    { path: '**', redirectTo: '' }
];
