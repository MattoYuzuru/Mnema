import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { appConfig } from './app.config';
import { Observable } from 'rxjs';

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
    constructor(private http: HttpClient) {}

    getMe(): Observable<UserProfile> {
        return this.http.get<UserProfile>(`${appConfig.apiBaseUrl}/me`);
    }

    updateMe(update: MeUpdateRequest): Observable<UserProfile> {
        return this.http.patch<UserProfile>(`${appConfig.apiBaseUrl}/me`, update);
    }

    deleteMe(): Observable<void> {
        return this.http.delete<void>(`${appConfig.apiBaseUrl}/me`);
    }
}
