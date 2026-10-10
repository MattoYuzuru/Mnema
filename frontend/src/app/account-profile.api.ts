import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { AUTH_BROWSER, BROWSER_IDENTITY_CONFIG, accountsApiBase } from './auth-browser';
import { IdentityProfile, AuthFailure, objectValue, parseProfile } from './auth-protocol';
import { isProfileBio, normalizeProfileBio } from './profile-bio';
import { PublicProfileConsent, PublicProfileConsentUpdate, parsePublicProfileConsent } from './public-profile';

export interface AccountProfile extends IdentityProfile {
    bio: string | null;
    avatarPresent: boolean;
}

export interface AccountProfileEdit {
    profileUsername: string;
    displayName: string;
    bio: string;
}

function parseAccountProfile(value: unknown): AccountProfile {
    const identity = parseProfile(value);
    const fields = objectValue(value);
    if (!(fields['bio'] === null || isProfileBio(fields['bio'])) ||
        typeof fields['avatarPresent'] !== 'boolean') throw new AuthFailure('protocol');
    return { ...identity, bio: fields['bio'] === null ? null : normalizeProfileBio(fields['bio']),
        avatarPresent: fields['avatarPresent'] };
}

@Injectable({ providedIn: 'root' })
export class AccountProfileApi {
    private readonly http = inject(HttpClient);
    private readonly config = inject(BROWSER_IDENTITY_CONFIG);
    private readonly browser = inject(AUTH_BROWSER);

    private base(): string { return accountsApiBase(this.config, this.browser.origin); }

    load(): Observable<AccountProfile> {
        return this.http.get<unknown>(`${this.base()}/me`, { withCredentials: false }).pipe(map(parseAccountProfile));
    }

    update(edit: AccountProfileEdit): Observable<AccountProfile> {
        return this.http.put<unknown>(`${this.base()}/me`, edit, { withCredentials: false })
            .pipe(map(parseAccountProfile));
    }

    uploadAvatar(file: File): Observable<void> {
        const body = new FormData();
        body.append('file', file, file.name);
        return this.http.put<void>(`${this.base()}/me/avatar`, body, { withCredentials: false });
    }

    /** The owner's own photo, with the bearer: it stays visible to the owner without a public-profile consent. */
    loadAvatar(): Observable<Blob> {
        return this.http.get(`${this.base()}/me/avatar`, { responseType: 'blob', withCredentials: false });
    }

    loadPublicProfile(): Observable<PublicProfileConsent> {
        return this.http.get<unknown>(`${this.base()}/me/public-profile`, { withCredentials: false })
            .pipe(map(parsePublicProfileConsent));
    }

    savePublicProfile(update: PublicProfileConsentUpdate): Observable<PublicProfileConsent> {
        return this.http.put<unknown>(`${this.base()}/me/public-profile`, update, { withCredentials: false })
            .pipe(map(parsePublicProfileConsent));
    }
}
