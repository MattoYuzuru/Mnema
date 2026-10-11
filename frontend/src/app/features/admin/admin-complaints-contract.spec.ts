import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import contract from '../../../../../contracts/moderation/complaints.json';
import { COMPLAINT_REASONS } from '../../shared/complaint.models';
import { AdminApiService } from './admin-api.service';
import {
    COMPLAINT_DECISIONS, COMPLAINT_STATUSES, COMPLAINT_TARGETS, JOURNAL_ACTIONS, REASONS, UNIT_KINDS, parseComplaintDetail, parseComplaintPage, parseJournalPage,
    parseUnitsPage
} from './admin-complaints.models';
import { MAX_TAKEDOWN_UNITS } from './admin-takedown-picker.component';
import { AdminProtocolError } from './admin.models';
import { clone } from './admin-test-data';

/** contracts/moderation/complaints.json is the wire contract shared with the backend: every example must parse strictly and carry exactly the documented properties. */
describe('Complaint console wire contract (contracts/moderation/complaints.json)', () => {
    it('knows the closed lists of the contract', () => {
        expect([...COMPLAINT_TARGETS]).toEqual(contract.constants.targetKinds);
        expect([...COMPLAINT_STATUSES]).toEqual(contract.constants.statuses);
        expect([...COMPLAINT_DECISIONS]).toEqual(contract.constants.decisions);
        expect([...JOURNAL_ACTIONS]).toEqual(contract.constants.journalActions);
        expect([...UNIT_KINDS]).toEqual(contract.constants.unitKinds);
        expect([...REASONS]).toEqual(contract.constants.reasons);
        expect([...COMPLAINT_REASONS]).toEqual(contract.constants.reasons);
        expect(MAX_TAKEDOWN_UNITS).toBe(contract.constants.limits.unitsPerDecision);
        expect(contract.constants.limits.queuePage.default).toBe(50);
    });

    it('parses the queue example with exactly the documented item properties', () => {
        const page = parseComplaintPage(contract.queue.answer);
        expect(page.next).toBeNull();
        expect(page.items).toHaveLength(2);
        for (const item of page.items) expect(Object.keys(item).sort()).toEqual([...contract.queue.itemProperties].sort());
        expect(page.items[1]).toMatchObject({ targetKind: 'PROFILE', profileUsername: 'ivan.k', deckId: null });
    });

    it('parses the complaint example with exactly the documented property sets', () => {
        const detail = parseComplaintDetail(contract.complaint.answer);
        expect(Object.keys(detail).sort()).toEqual([...contract.complaint.properties].sort());
        expect(Object.keys(detail.deck!).sort()).toEqual([...contract.complaint.deckProperties].sort());
        expect(Object.keys(detail.takedowns).sort()).toEqual([...contract.complaint.takedownsProperties].sort());
        expect(Object.keys(detail.takedowns.items[0]).sort()).toEqual([...contract.complaint.takedownProperties].sort());
        expect(detail.changed).toBe(true);
        const { changed: _omitted, ...fetched } = clone(contract.complaint.answer);
        expect(parseComplaintDetail(fetched).changed).toBeNull();
        const profile = { ...clone(contract.complaint.answer), targetKind: 'PROFILE', deck: null, profile: { accountId: '22222222-2222-4222-8222-222222222222', username: 'ivan.k' } };
        expect(Object.keys(parseComplaintDetail(profile).profile!).sort()).toEqual([...contract.complaint.profileProperties].sort());
    });

    it('parses the unit picker example for materials and for exercises', () => {
        const items = parseUnitsPage(contract.units.answer);
        expect(Object.keys(contract.units.answer).sort()).toEqual([...contract.units.properties].sort());
        expect(Object.keys(contract.units.answer.units[0]).sort()).toEqual([...contract.units.itemUnitProperties].sort());
        expect(items.units[0]).toMatchObject({ kind: 'ITEM', label: 'Памятка туриста', type: null, takenDown: true, ordinal: 0 });
        expect(Object.keys(contract.units.exerciseUnit).sort()).toEqual([...contract.units.exerciseUnitProperties].sort());
        const exercises = parseUnitsPage({ ...contract.units.answer, kind: 'EXERCISE', units: [contract.units.exerciseUnit] });
        expect(exercises.units[0]).toMatchObject({ kind: 'EXERCISE', label: 'Как сказать «где вокзал»?', type: 'FREE_RESPONSE', takenDown: false });
    });

    it('parses the journal example with exactly the documented properties', () => {
        const page = parseJournalPage(contract.journal.answer);
        expect(Object.keys(page.items[0]).sort()).toEqual([...contract.journal.itemProperties].sort());
        expect(page.items[0]).toMatchObject({ action: 'TAKEN_DOWN', unitCount: 2 });
    });

    it('refuses what the contract does not allow: a decision outside the list, a kind mixed with its answer, a status without its decision, a missing target', () => {
        const detail = clone(contract.complaint.answer) as Record<string, unknown>;
        expect(() => parseComplaintDetail({ ...detail, decision: 'DELETED' })).toThrow(AdminProtocolError);
        expect(() => parseComplaintDetail({ ...detail, status: 'OPEN' })).toThrow(AdminProtocolError);
        expect(() => parseComplaintDetail({ ...detail, deck: null })).toThrow(AdminProtocolError);
        expect(() => parseComplaintDetail({ ...detail, reason: 'RUDE' })).toThrow(AdminProtocolError);
        expect(() => parseComplaintDetail({ ...detail, complaintId: 'x' })).toThrow(AdminProtocolError);
        expect(() => parseUnitsPage({ ...contract.units.answer, kind: 'EXERCISE' })).toThrow(AdminProtocolError);
        expect(() => parseJournalPage({ items: [{ ...contract.journal.answer.items[0], action: 'DELETED' }], nextCursor: null })).toThrow(AdminProtocolError);
        expect(() => parseComplaintPage({ items: [{ ...contract.queue.answer.items[0], status: 'DECIDED' }], nextCursor: null })).toThrow(AdminProtocolError);
        expect(() => parseComplaintPage({ items: [], nextCursor: 5 })).toThrow(AdminProtocolError);
    });

    describe('routes', () => {
        let api: AdminApiService;
        let http: HttpTestingController;
        beforeEach(() => {
            TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
            api = TestBed.inject(AdminApiService);
            http = TestBed.inject(HttpTestingController);
        });
        afterEach(() => http.verify());
        const id = contract.queue.answer.items[0].complaintId;

        it('uses the documented paths, parameters and decision bodies', async () => {
            const queue = firstValueFrom(api.complaints('OPEN', 'opaque/+cursor'));
            const queueRequest = http.expectOne(request => request.url === '/api/admin/complaints');
            expect(queueRequest.request.params.get('status')).toBe('OPEN');
            expect(queueRequest.request.params.get('limit')).toBe('50');
            expect(queueRequest.request.params.get('cursor')).toBe('opaque/+cursor');
            queueRequest.flush(contract.queue.answer);
            await queue;
            const detail = firstValueFrom(api.complaint(id));
            http.expectOne(`/api/admin/complaints/${id}`).flush(contract.complaint.answer);
            await detail;
            const units = firstValueFrom(api.complaintUnits(id, 'EXERCISE', null));
            const unitsRequest = http.expectOne(request => request.url === `/api/admin/complaints/${id}/units`);
            expect(unitsRequest.request.params.get('kind')).toBe('EXERCISE');
            expect(unitsRequest.request.params.has('cursor')).toBe(false);
            unitsRequest.flush({ ...contract.units.answer, kind: 'EXERCISE', units: [contract.units.exerciseUnit] });
            await units;
            for (const request of Object.values(contract.decide.requests)) {
                const decided = firstValueFrom(api.decideComplaint(id, request as never));
                const sent = http.expectOne(`/api/admin/complaints/${id}:decide`);
                expect(sent.request.method).toBe('POST');
                expect(sent.request.body).toEqual(request);
                sent.flush(contract.complaint.answer);
                await decided;
            }
            const unhidden = firstValueFrom(api.unhideComplaint(id));
            const unhide = http.expectOne(`/api/admin/complaints/${id}:unhide`);
            expect(unhide.request.body).toBeNull();
            unhide.flush(contract.complaint.answer);
            await unhidden;
            const journal = firstValueFrom(api.complaintJournal(null));
            const journalRequest = http.expectOne(request => request.url === '/api/admin/complaints/journal');
            expect(journalRequest.request.params.get('limit')).toBe('50');
            journalRequest.flush(contract.journal.answer);
            await journal;
        });
    });
});
