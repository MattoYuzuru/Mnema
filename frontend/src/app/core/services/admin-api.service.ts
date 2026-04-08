import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page } from '../models/page.models';
import { appConfig } from '../../app.config';

export interface AdminOverview {
    totalAdmins: number;
    bannedUsers: number;
    deckReports: number;
    templateReports: number;
    cardReports: number;
}

export interface AdminUserEntry {
    id: string;
    email: string;
    username: string;
    bio?: string | null;
    avatarUrl?: string | null;
    avatarMediaId?: string | null;
    admin: boolean;
    adminGrantedBy?: string | null;
    adminGrantedAt?: string | null;
    banned: boolean;
    bannedBy?: string | null;
    bannedAt?: string | null;
    banReason?: string | null;
    assignedByCurrentAdmin: boolean;
    revocableByCurrentAdmin: boolean;
    bannableByCurrentAdmin: boolean;
    unbannableByCurrentAdmin: boolean;
    canPromoteToAdmin: boolean;
    createdAt: string;
    updatedAt: string;
}

@Injectable({ providedIn: 'root' })
export class AdminApiService {
    private readonly baseUrl = `${appConfig.apiBaseUrl}/admin`;

    constructor(private http: HttpClient) {}

    getOverview(): Observable<AdminOverview> {
        return this.http.get<AdminOverview>(`${this.baseUrl}/overview`);
    }

    searchUsers(query = '', page = 1, limit = 20): Observable<Page<AdminUserEntry>> {
        const params = this.withPaging(query, page, limit);
        return this.http.get<Page<AdminUserEntry>>(`${this.baseUrl}/users/search`, { params });
    }

    getAdmins(query = '', page = 1, limit = 20): Observable<Page<AdminUserEntry>> {
        const params = this.withPaging(query, page, limit);
        return this.http.get<Page<AdminUserEntry>>(`${this.baseUrl}/admins`, { params });
    }

    getBannedUsers(query = '', page = 1, limit = 20): Observable<Page<AdminUserEntry>> {
        const params = this.withPaging(query, page, limit);
        return this.http.get<Page<AdminUserEntry>>(`${this.baseUrl}/banned-users`, { params });
    }

    grantAdmin(userId: string): Observable<AdminUserEntry> {
        return this.http.post<AdminUserEntry>(`${this.baseUrl}/users/${userId}/grant-admin`, {});
    }

    revokeAdmin(userId: string): Observable<AdminUserEntry> {
        return this.http.post<AdminUserEntry>(`${this.baseUrl}/users/${userId}/revoke-admin`, {});
    }

    banUser(userId: string, reason?: string | null): Observable<AdminUserEntry> {
        return this.http.post<AdminUserEntry>(`${this.baseUrl}/users/${userId}/ban`, { reason: reason || null });
    }

    unbanUser(userId: string): Observable<AdminUserEntry> {
        return this.http.post<AdminUserEntry>(`${this.baseUrl}/users/${userId}/unban`, {});
    }

    private withPaging(query: string, page: number, limit: number): HttpParams {
        let params = new HttpParams()
            .set('page', page.toString())
            .set('limit', limit.toString());
        if (query.trim()) {
            params = params.set('query', query.trim());
        }
        return params;
    }
}
