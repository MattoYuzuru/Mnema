import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page } from '../models/page.models';
import { appConfig } from '../../app.config';

export type ReportTargetType = 'DECK' | 'CARD' | 'TEMPLATE';
export type ReportReason =
    | 'INAPPROPRIATE_LANGUAGE'
    | 'OFFENSIVE_CONTENT'
    | 'FACTUAL_ERROR'
    | 'MISLEADING_METADATA'
    | 'SPAM'
    | 'BROKEN_FORMATTING'
    | 'OTHER';
export type ReportStatus = 'OPEN' | 'CLOSED';

export interface CreateReportRequest {
    targetType: ReportTargetType;
    targetId: string;
    reason: ReportReason;
    details?: string | null;
}

export interface ModerationReportEntry {
    reportId: string;
    targetType: ReportTargetType;
    targetId: string;
    targetParentId?: string | null;
    targetTitle: string;
    contentOwnerId: string;
    reporterId: string;
    reporterUsername: string;
    reason: ReportReason;
    details?: string | null;
    status: ReportStatus;
    createdAt: string;
    updatedAt: string;
    closedAt?: string | null;
    closedByUserId?: string | null;
    closedByUsername?: string | null;
    resolutionNote?: string | null;
}

export interface ReportCountSlice {
    key: string;
    count: number;
}

export interface ResolverReportStats {
    adminId: string;
    username: string;
    resolvedCount: number;
}

export interface ModerationReportStats {
    totalOpen: number;
    totalClosed: number;
    targetBreakdown: ReportCountSlice[];
    reasonBreakdown: ReportCountSlice[];
    resolverBreakdown: ResolverReportStats[];
}

@Injectable({ providedIn: 'root' })
export class ReportApiService {
    private readonly baseUrl = `${appConfig.coreApiBaseUrl}/moderation/reports`;

    constructor(private http: HttpClient) {}

    createReport(request: CreateReportRequest): Observable<ModerationReportEntry> {
        return this.http.post<ModerationReportEntry>(this.baseUrl, request);
    }

    getOpenReports(page = 1, limit = 20): Observable<Page<ModerationReportEntry>> {
        return this.http.get<Page<ModerationReportEntry>>(`${this.baseUrl}/admin/open`, {
            params: {
                page,
                limit
            }
        });
    }

    getClosedReports(page = 1, limit = 20): Observable<Page<ModerationReportEntry>> {
        return this.http.get<Page<ModerationReportEntry>>(`${this.baseUrl}/admin/closed`, {
            params: {
                page,
                limit
            }
        });
    }

    getStats(): Observable<ModerationReportStats> {
        return this.http.get<ModerationReportStats>(`${this.baseUrl}/admin/stats`);
    }

    closeReport(reportId: string, resolutionNote?: string | null): Observable<ModerationReportEntry> {
        return this.http.post<ModerationReportEntry>(`${this.baseUrl}/admin/${reportId}/close`, {
            resolutionNote: resolutionNote || null
        });
    }
}
