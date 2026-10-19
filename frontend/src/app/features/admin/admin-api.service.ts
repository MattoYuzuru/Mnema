import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { Observable, catchError, map, throwError, timeout } from 'rxjs';
import { AuthService } from '../../auth.service';
import { appConfig } from '../../app.config';
import {
    AnomalyPage, EditorialCommand, EditorialState, parseAnomalyPage, parseEditorial
} from './admin-community.models';
import {
    ComplaintDetail, ComplaintPage, ComplaintStatus, DecideRequest, JournalPage, UnitKind, UnitsPage, parseComplaintDetail,
    parseComplaintPage, parseJournalPage, parseUnitsPage
} from './admin-complaints.models';
import {
    AdminAccess, AdminCampaign, AdminPromo, AdminReport, CampaignCreate, CampaignPage, IssuancePage, OnboardingExperimentReport, parseOnboardingReport, AuditPage, CommandReceipt, Conversation, DirectoryDetail, DirectoryPage,
    PromoCreate, PromoPage, SupportPage, TicketCommand, UserReport, object, parseAccess, parseAuditPage,
    parseCampaign, parseCampaignPage, parseConversation, parseDetail, parseIssuancePage, parseDirectory, parsePromo, parsePromoPage, parseReceipt, parseReport,
    parseSupportPage, parseUserReport, str
} from './admin.models';
@Injectable({
    providedIn: 'root'
})
export class AdminSession {
    private readonly auth = inject(AuthService);
    readonly access = signal<AdminAccess | null>(null);
    readonly generation = signal(0);
    private observedStatus = this.auth.status();
    private observedAccount = this.auth.user()?.accountId ?? null;
    constructor() {
        effect(() => {
            const status = this.auth.status(), accountId = this.auth.user()?.accountId ?? null;
            const changed = status !== this.observedStatus || accountId !== this.observedAccount;
            this.observedStatus = status;
            this.observedAccount = accountId;
            if (changed || this.access() !== null && status !== 'authenticated')
                this.revoke();
        });
    }
    revoke(): void {
        this.access.set(null);
        this.generation.update(value => value + 1);
    }
}

@Injectable({
    providedIn: 'root'
})
export class AdminApiService {
    private readonly http = inject(HttpClient);
    private readonly session = inject(AdminSession);
    private readonly learning = `${appConfig.learningApiBaseUrl.replace(/\/$/u, '')}/admin`;
    private readonly identity = `${appConfig.authServerUrl}/api/accounts/admin`;
    access(): Observable<AdminAccess> {
        return this.get(`${this.learning}/console/access`, {}, parseAccess);
    }
    report(from: string, to: string): Observable<AdminReport> {
        return this.get(`${this.learning}/console/report`, {
            from,
            to
        }, parseReport);
    }
    onboardingReport(from: string, to: string): Observable<OnboardingExperimentReport> {
        return this.get(`${this.learning}/onboarding/experiment`, { from, to }, parseOnboardingReport);
    }
    directory(query: string, status: string, after: string | null): Observable<DirectoryPage> {
        return this.get(`${this.identity}/directory`, {
            query,
            status,
            after
        }, parseDirectory);
    }
    account(id: string): Observable<DirectoryDetail> {
        return this.get(`${this.identity}/directory/${id}`, {}, parseDetail);
    }
    userReport(id: string, from: string, to: string): Observable<UserReport> {
        return this.get(`${this.learning}/console/users/${id}`, {
            from,
            to
        }, parseUserReport);
    }
    ban(id: string, reason: string): Observable<void> {
        return this.protect(this.http.post<void>(`${this.identity}/accounts/${id}/ban`, {
            reason
        }).pipe(timeout(10000)));
    }
    unban(id: string): Observable<void> {
        return this.protect(this.http.post<void>(`${this.identity}/accounts/${id}/unban`, null).pipe(timeout(10000)));
    }
    promos(after: string | null): Observable<PromoPage> {
        return this.get(`${this.learning}/promo-codes`, {
            after
        }, parsePromoPage);
    }
    createPromo(body: PromoCreate, commandId: string): Observable<AdminPromo & {
        readonly code: string;
    }> {
        return this.protect(this.http.post<unknown>(`${this.learning}/promo-codes`, body, {
            headers: {
                'Idempotency-Key': commandId
            }
        }).pipe(timeout(10000), map(value => {
            const code = str(object(value)['code'], 64);
            return {
                ...parsePromo(value),
                code
            };
        })));
    }
    switchPromo(id: string, enabled: boolean): Observable<AdminPromo> {
        return this.protect(this.http.patch<unknown>(`${this.learning}/promo-codes/${id}`, {
            enabled
        }).pipe(timeout(10000), map(parsePromo)));
    }
    /** The campaigns of the reward for a finished «Путь», newest first (at most 100): the owner's console of OB-402. */
    campaigns(): Observable<CampaignPage> {
        return this.get(`${this.learning}/promo-campaigns`, {}, parseCampaignPage);
    }
    /** A new campaign is created disabled. The same `commandId` is the same command: a retry after an unknown outcome returns the same campaign. */
    createCampaign(body: CampaignCreate, commandId: string): Observable<AdminCampaign> {
        return this.protect(this.http.post<unknown>(`${this.learning}/promo-campaigns`, body, {
            headers: {
                'Idempotency-Key': commandId
            }
        }).pipe(timeout(10000), map(parseCampaign)));
    }
    /** Enabling is refused with a 409 `PROMO_CAMPAIGN_ACTIVE` while another campaign of the type is enabled; disabling is the kill switch and revokes no code. */
    switchCampaign(id: string, enabled: boolean): Observable<AdminCampaign> {
        return this.protect(this.http.patch<unknown>(`${this.learning}/promo-campaigns/${id}`, {
            enabled
        }).pipe(timeout(10000), map(parseCampaign)));
    }
    /** The codes a campaign issued, newest first, paged by an opaque cursor. */
    issuances(id: string, after: string | null): Observable<IssuancePage> {
        return this.get(`${this.learning}/promo-campaigns/${id}/issuances`, {
            after
        }, parseIssuancePage);
    }
    audit(source: 'learning' | 'identity', before: string | null): Observable<AuditPage> {
        return this.get(source === 'learning' ? `${this.learning}/console/audit` : `${this.identity}/audit`, {
            before
        }, parseAuditPage);
    }
    tickets(filters: Readonly<Record<string, string | null>>): Observable<SupportPage> {
        return this.get(`${this.learning}/support/tickets`, {
            ...filters,
            limit: '30'
        }, parseSupportPage);
    }
    conversation(id: string, afterMessage: string | null = null): Observable<Conversation> {
        return this.get(`${this.learning}/support/tickets/${id}`, {
            afterMessage,
            limit: '50'
        }, parseConversation);
    }
    ticketCommand(id: string, command: TicketCommand): Observable<CommandReceipt> {
        return this.protect(this.http.post<unknown>(`${this.learning}/support/tickets/${id}/commands`, command).pipe(timeout(15000), map(parseReceipt)));
    }
    /** The complaint queue: OPEN oldest due date first, DECIDED newest decision first (Share/15). */
    complaints(status: ComplaintStatus, cursor: string | null): Observable<ComplaintPage> {
        return this.get(`${this.learning}/complaints`, {
            status,
            limit: '50',
            cursor
        }, parseComplaintPage);
    }
    complaint(id: string): Observable<ComplaintDetail> {
        return this.get(`${this.learning}/complaints/${id}`, {}, parseComplaintDetail);
    }
    /** The materials or exercises of the reported deck's published revision, for the legal takedown picker. */
    complaintUnits(id: string, kind: UnitKind, cursor: string | null): Observable<UnitsPage> {
        return this.get(`${this.learning}/complaints/${id}/units`, {
            kind,
            limit: '50',
            cursor
        }, parseUnitsPage);
    }
    /** One decision per complaint; the API acts at once, so the confirmation is the caller's. */
    decideComplaint(id: string, request: DecideRequest): Observable<ComplaintDetail> {
        return this.protect(this.http.post<unknown>(`${this.learning}/complaints/${id}:decide`, request).pipe(timeout(15000), map(parseComplaintDetail)));
    }
    unhideComplaint(id: string): Observable<ComplaintDetail> {
        return this.protect(this.http.post<unknown>(`${this.learning}/complaints/${id}:unhide`, null).pipe(timeout(15000), map(parseComplaintDetail)));
    }
    complaintJournal(cursor: string | null): Observable<JournalPage> {
        return this.get(`${this.learning}/complaints/journal`, {
            limit: '50',
            cursor
        }, parseJournalPage);
    }
    /** The shelf «Выбор Мнемы»: the ordered list and its row version (Community/7). */
    editorial(): Observable<EditorialState> {
        return this.get(`${this.learning}/community/editorial`, {}, parseEditorial);
    }
    /** Replaces the whole list. `If-Match` is the row version the list was read at; the same `commandId` makes a repeat harmless. */
    saveEditorial(command: EditorialCommand, rowVersion: string): Observable<void> {
        return this.protect(this.http.put<unknown>(`${this.learning}/community/editorial`, command, {
            headers: { 'If-Match': `"${rowVersion}"` }
        }).pipe(timeout(15000), map(() => undefined)));
    }
    /** Anomaly flags: open ones first, then dismissed ones, newest first; the server pages by 50 (Community/3). */
    anomalies(cursor: string | null): Observable<AnomalyPage> {
        return this.get(`${this.learning}/community/anomalies`, { cursor }, parseAnomalyPage);
    }
    dismissAnomaly(code: string, commandId: string): Observable<void> {
        return this.protect(this.http.post<unknown>(`${this.learning}/community/anomalies/${encodeURIComponent(code)}:dismiss`, { commandId }).pipe(timeout(15000), map(() => undefined)));
    }
    /** A response from an ended authorization generation must never repopulate a private screen. */
    private protect<T>(request: Observable<T>): Observable<T> {
        const generation = this.session.generation();
        return request.pipe(map(value => {
            if (generation !== this.session.generation())
                throw new HttpErrorResponse({
                    status: 401,
                    statusText: 'Session ended'
                });
            return value;
        }), catchError((error: unknown) => {
            if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403) && generation === this.session.generation())
                this.session.revoke();
            return throwError(() => error);
        }));
    }

    private get<T>(url: string, values: Readonly<Record<string, string | null>>, parse: (value: unknown) => T): Observable<T> {
        let params = new HttpParams();
        for (const [key, value] of Object.entries(values))
            if (value !== null && value !== '')
                params = params.set(key, value);
        return this.protect(this.http.get<unknown>(url, {
            params
        }).pipe(timeout(15000), map(parse)));
    }
}
