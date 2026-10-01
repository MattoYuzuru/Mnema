import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { AUTH_BROWSER, BROWSER_IDENTITY_CONFIG, validateIdentityConfig } from './auth-browser';
import { IdentityProfile, AuthFailure, boundedText, objectValue, parseProfile } from './auth-protocol';

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
    if (!(fields['bio'] === null || boundedText(fields['bio'], 200)) ||
        typeof fields['avatarPresent'] !== 'boolean') throw new AuthFailure('protocol');
    return { ...identity, bio: fields['bio'], avatarPresent: fields['avatarPresent'] };
}

@Injectable({ providedIn: 'root' })
export class AccountProfileApi {
    private readonly http = inject(HttpClient);
    private readonly config = inject(BROWSER_IDENTITY_CONFIG);
    private readonly browser = inject(AUTH_BROWSER);

    private base(): string {
        validateIdentityConfig(this.config, this.browser.origin);
        return `${this.config.authServerUrl}/api/accounts`;
    }

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

    avatarUrl(accountId: string, version: number): string {
        if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(accountId))
            throw new AuthFailure('protocol');
        return `${this.base()}/profiles/${accountId}/avatar?v=${version}`;
    }
}
