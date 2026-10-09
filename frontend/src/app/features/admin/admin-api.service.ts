import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { Observable, catchError, map, throwError, timeout } from 'rxjs';
import { AuthService } from '../../auth.service';
import { appConfig } from '../../app.config';
import {
    AdminAccess, AdminPromo, AdminReport, AuditPage, CommandReceipt, Conversation, DirectoryDetail, DirectoryPage,
    PromoCreate, PromoPage, SupportPage, TicketCommand, UserReport, object, parseAccess, parseAuditPage,
    parseConversation, parseDetail, parseDirectory, parsePromo, parsePromoPage, parseReceipt, parseReport,
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
