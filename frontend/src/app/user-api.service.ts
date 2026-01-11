import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { appConfig } from './app.config';
import { BehaviorSubject, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';

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
        return this.http.get<UserProfile>(`${appConfig.apiBaseUrl}/me`).pipe(
            tap(profile => this.profileSubject.next(profile))
        );
    }

    updateMe(update: MeUpdateRequest): Observable<UserProfile> {
        return this.http.patch<UserProfile>(`${appConfig.apiBaseUrl}/me`, update).pipe(
            tap(profile => this.profileSubject.next(profile))
        );
    }

    deleteMe(): Observable<void> {
        return this.http.delete<void>(`${appConfig.apiBaseUrl}/me`).pipe(
            tap(() => this.profileSubject.next(null))
        );
    }
}
