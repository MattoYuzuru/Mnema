import { HttpErrorResponse } from '@angular/common/http';
import {
    AdminProtocolError, array, day, parseAccess, parseAuditPage, parseConversation, parseDetail, parseDirectory,
    parseFeature, parsePromo, parsePromoPage, parseReceipt, parseReport, parseSupportPage, parseUserReport
} from './admin.models';
import {
    addDays, bytes, dateTime, errorText, isForbidden, moneyUsd, number, percent, reportPeriod, unknownOutcome
} from './admin-presenters';
import { randomPromoCode } from './admin-promos-page.component';
import { clone, promo, wire } from './admin-test-data';
describe('administrative wire and interpretation', () => {
    it('reads the shared Learning and Identity contract fixtures with nullable empty samples', () => {
        expect(parseAccess(wire.access).owner).toBe(true);
        const report = parseReport(wire.report);
        expect(report.ai.currency).toBe('USD');
        expect(report.ai.latency.p50Ms).toBeNull();
        expect(report.usage.creditsPerUser.sample).toBe(0);
        expect(report.usage.creditsPerUser.p99).toBeNull();
        expect(report.financial.revenue.status).toBe('AVAILABLE');
        expect(report.financial.revenue.paidKopecks).toBe(0);
        expect(report.ai.latency.sampleCount).toBe(0);
        expect(parseDirectory(wire.directory).accounts[0].lastLoginAt).toBeNull();
        expect(parseDetail(wire.directory.accounts[0]).banReason).toBeNull();
        expect(parseUserReport(wire.user).usage.operations[0].units).toBe(60);
        expect(parseAuditPage(wire.audit).entries[0].action).toBe('PROMO_CREATE');
        expect(parsePromo(promo).redemptions).toBe(2);
        expect(parsePromoPage({
            codes: [promo],
            next: null
        }).codes).toHaveLength(1);
    });
    it('preserves large Telegram IDs as strings, internal notes and attachment metadata', () => {
        expect(parseSupportPage(wire.support.tickets).entries[0].userId).toBe('9007199254740995');
        const conversation = parseConversation(wire.support.conversation);
        expect(conversation.messages[1].direction).toBe('note');
        expect(conversation.messages[0].attachment?.name).toBe('fixture.txt');
        expect(parseReceipt(wire.support.acknowledgement).delivery).toBe('queued');
    });
    it('rejects missing fields, unauthorized access, malformed identifiers and unbounded pages', () => {
        expect(() => parseAccess({
            owner: false
        })).toThrow(AdminProtocolError);
        expect(() => parseAccess({
            owner: true,
            permissions: {}
        })).toThrow(AdminProtocolError);
        expect(() => parseReport({})).toThrow(AdminProtocolError);
        expect(() => parseDirectory({
            accounts: [{
                    ...wire.directory.accounts[0],
                    accountId: 'x'
                }],
            next: null
        })).toThrow(AdminProtocolError);
        expect(() => parseSupportPage({
            ...wire.support.tickets,
            entries: [{
                    ...wire.support.tickets.entries[0],
                    id: 9007199254740992
                }]
        })).toThrow(AdminProtocolError);
        expect(() => parsePromo({
            ...promo,
            type: 'FOREVER'
        })).toThrow(AdminProtocolError);
        expect(() => array(new Array(201).fill(null), x => x)).toThrow(AdminProtocolError);
    });
    it('rejects negative and nonfinite metrics and out-of-range shares', () => {
        const report = clone(wire.report);
        report.ai.calls = -1;
        expect(() => parseReport(report)).toThrow(AdminProtocolError);
        const feature = {
            operation: 'STT',
            users: 1,
            events: 1,
            creditsDebited: 0,
            units: 12,
            share: 1
        };
        expect(() => parseFeature({
            ...feature,
            share: 1.1
        })).toThrow(AdminProtocolError);
        expect(() => parseFeature({
            ...feature,
            users: Infinity
        })).toThrow(AdminProtocolError);
        expect(parseFeature({
            ...feature,
            share: null
        }).share).toBeNull();
    });
    it('maps inclusive UTC dates and bounds periods without accepting impossible dates', () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date('2026-10-09T12:00:00Z'));
        expect(reportPeriod('2026-10-01', '2026-10-09')).toEqual({
            from: '2026-10-01',
            to: '2026-10-10'
        });
        expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
        expect(() => day('2026-02-30')).toThrow();
        expect(() => reportPeriod('2026-01-01', '2026-10-09')).toThrow();
        expect(() => reportPeriod('2026-10-09', '2026-10-08')).toThrow();
        expect(() => reportPeriod('2026-10-01', '2026-10-10')).toThrow();
        vi.useRealTimers();
    });
    it('distinguishes unknown command outcomes from refused commands and formats absence honestly', () => {
        expect(unknownOutcome(new HttpErrorResponse({
            status: 0
        }))).toBe(true);
        expect(unknownOutcome(new HttpErrorResponse({
            status: 503
        }))).toBe(true);
        expect(unknownOutcome(new AdminProtocolError())).toBe(true);
        expect(unknownOutcome(new Error())).toBe(true);
        expect(unknownOutcome(new HttpErrorResponse({
            status: 409
        }))).toBe(false);
        expect(isForbidden(new HttpErrorResponse({
            status: 403
        }))).toBe(true);
        expect(errorText(new HttpErrorResponse({
            status: 429
        }), 'x')).toContain('Подождите');
        expect(errorText(new HttpErrorResponse({
            status: 401
        }), 'x')).toContain('Доступ');
        expect(number(null)).toBe('Нет данных');
        expect(percent(null)).toBe('Нет данных');
        expect(dateTime(null)).toBe('Нет данных');
        expect(moneyUsd(1000000)).toContain('1');
        expect(bytes(1024 * 1024)).toBe('1 МиБ');
    });
    it('generates unambiguous twelve-character promo codes with rejection sampling', () => {
        const random = vi.spyOn(crypto, 'getRandomValues').mockImplementation(array => {
            const values = array as Uint8Array;
            values.fill(255);
            values.fill(0, 12);
            return array;
        });
        expect(randomPromoCode()).toBe('AAAAAAAAAAAA');
        expect(random).toHaveBeenCalledOnce();
    });
});
