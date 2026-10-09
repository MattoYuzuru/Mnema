import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { appConfig } from '../../app.config';
import { AdminApiService } from './admin-api.service';
import { promo, wire } from './admin-test-data';
describe('administrative HTTP boundary', () => {
    let api: AdminApiService;
    let http: HttpTestingController;
    const identity = `${appConfig.authServerUrl}/api/accounts/admin`;
    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
        });
        api = TestBed.inject(AdminApiService);
        http = TestBed.inject(HttpTestingController);
    });
    afterEach(() => http.verify());
    it('uses separate Identity and Learning sources and serializes bounded cursors literally', async () => {
        const access = firstValueFrom(api.access());
        http.expectOne('/api/admin/console/access').flush(wire.access);
        await access;
        const report = firstValueFrom(api.report('2026-10-01', '2026-10-10'));
        http.expectOne('/api/admin/console/report?from=2026-10-01&to=2026-10-10').flush(wire.report);
        await report;
        const directory = firstValueFrom(api.directory('a+%_ test', 'ACTIVE', 'opaque/+cursor'));
        const request = http.expectOne(r => r.url === `${identity}/directory`);
        expect(request.request.params.get('query')).toBe('a+%_ test');
        expect(request.request.params.get('after')).toBe('opaque/+cursor');
        request.flush(wire.directory);
        await directory;
        const account = firstValueFrom(api.account(wire.directory.accounts[0].accountId));
        http.expectOne(`${identity}/directory/${wire.directory.accounts[0].accountId}`).flush(wire.directory.accounts[0]);
        await account;
        const user = firstValueFrom(api.userReport(wire.user.accountId, wire.user.from, wire.user.to));
        http.expectOne(`/api/admin/console/users/${wire.user.accountId}?from=${wire.user.from}&to=${wire.user.to}`).flush(wire.user);
        await user;
    });
    it('sends moderation targets to Identity and promo UUID receipts to Learning', async () => {
        const ban = firstValueFrom(api.ban(wire.user.accountId, 'Причина'));
        const b = http.expectOne(`${identity}/accounts/${wire.user.accountId}/ban`);
        expect(b.request.body).toEqual({
            reason: 'Причина'
        });
        b.flush(null);
        await ban;
        const unban = firstValueFrom(api.unban(wire.user.accountId));
        const u = http.expectOne(`${identity}/accounts/${wire.user.accountId}/unban`);
        expect(u.request.body).toBeNull();
        u.flush(null);
        await unban;
        const body = {
            type: 'TIER_DAYS' as const,
            plan: 'PLUS' as const,
            days: 7,
            months: null,
            percent: null,
            validFrom: null,
            validUntil: null,
            maxRedemptions: 10,
            oncePerAccount: true,
            channel: null,
            code: 'ABCDEFGH2345'
        };
        const created = firstValueFrom(api.createPromo(body, '10000000-0000-4000-8000-000000000001'));
        const c = http.expectOne('/api/admin/promo-codes');
        expect(c.request.body).toEqual(body);
        expect(c.request.headers.get('Idempotency-Key')).toBe('10000000-0000-4000-8000-000000000001');
        c.flush({
            ...promo,
            code: body.code
        });
        expect((await created).code).toBe(body.code);
        const switched = firstValueFrom(api.switchPromo(promo.codeId, false));
        const s = http.expectOne(`/api/admin/promo-codes/${promo.codeId}`);
        expect(s.request.method).toBe('PATCH');
        expect(s.request.body).toEqual({
            enabled: false
        });
        s.flush({
            ...promo,
            enabled: false
        });
        await switched;
        const list = firstValueFrom(api.promos(null));
        http.expectOne('/api/admin/promo-codes').flush({
            codes: [promo],
            next: null
        });
        await list;
    });
    it('keeps independent audit sources and ticket IDs as strings without a browser actor', async () => {
        for (const source of ['learning', 'identity'] as const) {
            const result = firstValueFrom(api.audit(source, null));
            http.expectOne(source === 'learning' ? '/api/admin/console/audit' : `${identity}/audit`).flush(wire.audit);
            await result;
        }
        const tickets = firstValueFrom(api.tickets({
            status: 'working',
            userId: '9007199254740995'
        }));
        const t = http.expectOne(r => r.url === '/api/admin/support/tickets');
        expect(t.request.params.get('userId')).toBe('9007199254740995');
        expect(t.request.params.get('limit')).toBe('30');
        t.flush(wire.support.tickets);
        await tickets;
        const id = wire.support.conversation.ticket.id;
        const conversation = firstValueFrom(api.conversation(id, null));
        http.expectOne(`/api/admin/support/tickets/${id}?limit=50`).flush(wire.support.conversation);
        await conversation;
        const command = {
            commandId: wire.support.acknowledgement.commandId,
            expectedVersion: 2,
            type: 'reply' as const,
            text: 'Ответ'
        };
        const result = firstValueFrom(api.ticketCommand(id, command));
        const c = http.expectOne(`/api/admin/support/tickets/${id}/commands`);
        expect(c.request.body).toEqual(command);
        expect(c.request.body.actor).toBeUndefined();
        c.flush(wire.support.acknowledgement);
        await result;
    });
});
