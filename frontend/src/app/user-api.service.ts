import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { appConfig } from './app.config';
import { BehaviorSubject, Observable } from 'rxjs';
import { map, tap } from 'rxjs/operators';

export interface UserProfile {
    id: string;
    email: string;
    username: string;
    bio?: string | null;
    avatarUrl?: string | null;
    avatarMediaId?: string | null;
    admin: boolean;
    createdAt: string;
    updatedAt: string;
}

type UserProfileApiResponse = Omit<UserProfile, 'admin'> & {
    admin?: boolean;
    isAdmin?: boolean;
};

export interface MeUpdateRequest {
    username?: string;
    bio?: string | null;
    avatarUrl?: string | null;
    avatarMediaId?: string | null;
}

@Injectable({ providedIn: 'root' })
export class UserApiService {
    private profileSubject = new BehaviorSubject<UserProfile | null>(null);
    profile$ = this.profileSubject.asObservable();

    constructor(private http: HttpClient) {}

    getMe(): Observable<UserProfile> {
        return this.http.get<UserProfileApiResponse>(`${appConfig.apiBaseUrl}/me`).pipe(
            map(profile => this.normalizeProfile(profile)),
            tap(profile => this.profileSubject.next(profile))
        );
    }

    updateMe(update: MeUpdateRequest): Observable<UserProfile> {
        return this.http.patch<UserProfileApiResponse>(`${appConfig.apiBaseUrl}/me`, update).pipe(
            map(profile => this.normalizeProfile(profile)),
            tap(profile => this.profileSubject.next(profile))
        );
    }

    deleteMe(): Observable<void> {
        return this.http.delete<void>(`${appConfig.apiBaseUrl}/me`).pipe(
            tap(() => this.profileSubject.next(null))
        );
    }

    private normalizeProfile(profile: UserProfileApiResponse): UserProfile {
        return {
            ...profile,
            admin: profile.admin ?? profile.isAdmin ?? false
        };
    }
}
