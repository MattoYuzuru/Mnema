import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import updates from '../../../../../../contracts/decks/updates.json';
import { UPDATE_TEXT, UpdateUnitClass } from '../../../shared/copy-text';
import { choicesFor } from '../../../shared/update-unit-row.component';
import { OwnDeckProtocolError } from '../own-deck.models';
import { sideLines } from './exercise-projection';
import { UpdatePlanApiService } from './update-plan-api.service';
import {
    ApplyCommand,
    INVALID_REASONS,
    PLAN_PAGE_LIMIT,
    PLAN_STATES,
    WIRE_CLASSES,
    WIRE_CHOICES,
    WIRE_DEFAULT_CHOICES,
    WIRE_KINDS,
    WireClass,
    choiceToWire,
    invalidChoiceOf,
    isDefaultChoice,
    parseApplyAnswer,
    parseUnitSides,
    parseUpdatePlan,
    retryAfterSeconds,
    toReviewUnit,
    updateFailureOf
} from './update-plan.models';

/**
 * contracts/decks/updates.json is the one wire contract of the plan the review screen reads and of the apply command (Updates/3 and
 * Updates/4). Every fixture must parse, every request must be the one the contract names, the contract's constants (states, choices per
 * class, reasons) must be the ones the client mirrors, and its problems must be what the client does.
 */
describe('Update plan wire contract (contracts/decks/updates.json)', () => {
    const headers = { 'Cache-Control': 'private, no-store' };
    const deckId = updates.plan.body.forkDeckId;
    const planVersion = updates.plan.body.planVersion;
    let http: HttpTestingController;
    let api: UpdatePlanApiService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
        http = TestBed.inject(HttpTestingController);
        api = TestBed.inject(UpdatePlanApiService);
    });
    afterEach(() => http.verify());

    describe('constants', () => {
        it('mirrors the states, kinds, classes, default choices and the page size of the contract', () => {
            expect([...updates.constants.states].sort()).toEqual(PLAN_STATES.filter(state => state !== 'APPLYING').sort());
            expect([...WIRE_KINDS]).toEqual(updates.constants.kinds);
            expect([...WIRE_CLASSES]).toEqual(updates.constants.classes);
            expect(WIRE_DEFAULT_CHOICES).toEqual(updates.constants.defaultChoices);
            expect(PLAN_PAGE_LIMIT).toBe(updates.constants.defaultLimit);
            for (const choice of Object.values(updates.constants.defaultChoices)) expect(WIRE_CHOICES as readonly string[]).toContain(choice);
        });

        it('mirrors the apply constants: the sixth state, the path, the choices and the reasons of the 400', () => {
            expect(PLAN_STATES).toContain(updates.constants.applyingState);
            expect(updates.constants.applyPath).toBe('/api/decks/{forkDeckId}/update-plan:apply');
            expect([...updates.constants.applyChoices].sort()).toEqual([...WIRE_CHOICES].sort());
            expect([...updates.constants.applyReasons]).toEqual([...INVALID_REASONS]);
            expect(updates.constants.applyStates).toEqual(['APPLIED', 'APPLYING']);
        });

        it('offers exactly the choices per class of the contract, with the default among them', () => {
            const viewOf: Readonly<Record<WireClass, UpdateUnitClass>> = { NEW: 'new', SAFE: 'fix', CONFLICT: 'conflict', ASK_AGAIN: 'changed-again', DELETED: 'deleted' };
            for (const wireClass of WIRE_CLASSES) {
                const unitClass = viewOf[wireClass];
                const offered = choicesFor(unitClass).map(option => choiceToWire(unitClass, option.value));
                expect([...offered].sort(), wireClass).toEqual([...updates.constants.choicesPerClass[wireClass]].sort());
                expect(offered, wireClass).toContain(updates.constants.defaultChoices[wireClass]);
                // A class with a default on screen stands on it; a new or corrected unit waits for the reader (or «Принять всё безопасное»).
                const standing = choicesFor(unitClass).filter(option => isDefaultChoice(unitClass, option.value)).map(option => choiceToWire(unitClass, option.value));
                expect(standing, wireClass).toEqual(wireClass === 'NEW' || wireClass === 'SAFE' ? ['ACCEPT'] : [updates.constants.defaultChoices[wireClass]]);
            }
        });

        it('keeps the texts to the frontend: the tab names of the contract are the words of the screen', () => {
            expect(updates.texts.note).toContain(`«${UPDATE_TEXT.tabs.new}», «${UPDATE_TEXT.tabs.fix}», «${UPDATE_TEXT.tabs.conflict}», «${UPDATE_TEXT.tabs.deleted}»`);
            expect(updates.texts.note).toContain(`«${UPDATE_TEXT.mine}» | «${UPDATE_TEXT.author}»`);
        });
    });

    describe('the plan', () => {
        it('asks for the first page with the default limit and parses the READY example exactly', async () => {
            const reading = firstValueFrom(api.plan(deckId));
            const request = http.expectOne(candidate => candidate.url === `/api/decks/${deckId}/update-plan`);
            expect(request.request.method).toBe('GET');
            expect(request.request.params.get('limit')).toBe('50');
            expect(request.request.params.has('cursor')).toBe(false);
            request.flush(updates.plan.body, { headers });
            const page = await reading;
            expect(page.state).toBe('READY');
            expect(page.planVersion).toBe(planVersion);
            expect(page.releaseNote).toBe(updates.plan.body.releaseNote);
            expect(page.summary).toEqual(updates.plan.body.summary);
            expect(page.units.map(unit => [unit.unitId, unit.kind, unit.class, unit.defaultChoice])).toEqual(
                updates.plan.body.units.map(unit => [unit.unitId, unit.kind, unit.class, unit.defaultChoice]));
            expect(page.units[1]!.dependsOn).toEqual(updates.plan.body.units[1]!.dependsOn);
            expect(page.nextCursor).toBeNull();
            expect(page.lastApplyFailed).toBeNull();
        });

        it('sends the opaque cursor as given', async () => {
            const reading = firstValueFrom(api.plan(deckId, 'opaque.cursor-1', 25));
            const request = http.expectOne(candidate => candidate.url === `/api/decks/${deckId}/update-plan`);
            expect(request.request.params.get('cursor')).toBe('opaque.cursor-1');
            expect(request.request.params.get('limit')).toBe('25');
            request.flush({ ...updates.plan.body, nextCursor: 'opaque.cursor-2' }, { headers });
            expect((await reading).nextCursor).toBe('opaque.cursor-2');
        });

        it('parses the up-to-date example and the closed example with no pins and no summary', () => {
            const upToDate = parseUpdatePlan(updates.upToDate.body, deckId);
            expect(upToDate.state).toBe('UP_TO_DATE');
            expect(upToDate.units).toEqual([]);
            expect(upToDate.summary).toEqual(updates.upToDate.body.summary);
            for (const state of ['SOURCE_UNAVAILABLE', 'DETACHED', 'PREPARING']) {
                const closed = parseUpdatePlan({ ...updates.closed.body, state }, deckId);
                expect(closed).toEqual({ state, forkDeckId: deckId, planVersion: null, releaseNote: null, summary: null, units: [], nextCursor: null, lastApplyFailed: null, commandId: null });
            }
        });

        it('reads APPLYING with the command the job runs, and lastApplyFailed on READY and UP_TO_DATE', () => {
            const applying = parseUpdatePlan(updates.applying.body, deckId);
            expect(applying).toEqual({ state: 'APPLYING', forkDeckId: deckId, planVersion: null, releaseNote: null, summary: null, units: [], nextCursor: null, lastApplyFailed: null, commandId: updates.applying.body.commandId });
            const failure = updates.lastApplyFailed.member;
            const failed = parseUpdatePlan({ ...updates.plan.body, ...failure }, deckId);
            expect(failed.state).toBe('READY');
            expect(failed.lastApplyFailed).toBe(failure.lastApplyFailed.code);
            expect(parseUpdatePlan({ ...updates.upToDate.body, ...failure }, deckId)).toMatchObject({ state: 'UP_TO_DATE', lastApplyFailed: 'stale_head' });
            for (const code of updates.constants.jobFailureCodes) {
                expect(parseUpdatePlan({ ...updates.plan.body, lastApplyFailed: { code } }, deckId).lastApplyFailed).toBe(code);
            }
        });

        it('refuses an APPLYING body without its command or with more than the closed shape, and a failure that is not a short code', () => {
            const applying = updates.applying.body;
            const { commandId: _commandId, ...withoutCommand } = applying;
            for (const value of [
                withoutCommand,
                { ...applying, commandId: 'x' },
                { ...applying, commandId: 7 },
                { ...applying, units: [updates.plan.body.units[0]] },
                { ...applying, nextCursor: 'c' },
                { ...applying, planVersion },
                { ...updates.closed.body, commandId: applying.commandId },
                { ...updates.plan.body, commandId: applying.commandId },
                { ...updates.plan.body, lastApplyFailed: { code: '' } },
                { ...updates.plan.body, lastApplyFailed: { code: 'x'.repeat(101) } },
                { ...updates.plan.body, lastApplyFailed: 'stale_head' }
            ]) expect(() => parseUpdatePlan(value, deckId), JSON.stringify(value)).toThrowError(OwnDeckProtocolError);
        });

        it('refuses a plan that breaks the invariants of the contract', () => {
            const body = updates.plan.body;
            const unit = body.units[0]!;
            const broken: unknown[] = [
                { ...body, state: 'DONE' },
                { ...body, forkDeckId: '55555555-5555-4555-8555-555555555555' },
                { ...body, planVersion: 'sha256:xyz' },
                { ...body, extra: 1 },
                { ...body, summary: { ...body.summary, fixes: -1 } },
                { ...body, summary: { ...body.summary, extra: 1 } },
                { ...body, releaseNote: 'x'.repeat(501) },
                { ...body, units: [{ ...unit, class: 'WEIRD' }] },
                { ...body, units: [{ ...unit, unitId: 'MATERIAL:00000000-0000-4000-8000-000000000000' }] },
                { ...body, units: [{ ...unit, reason: 'BOTH_CHANGED' }] },
                { ...body, units: [{ ...unit, defaultChoice: 'KEEP' }] },
                { ...body, units: [{ ...unit, dependsOn: ['x'] }] },
                { ...body, units: [{ ...unit, title: 'x'.repeat(161) }] },
                { ...body, units: [unit, unit] },
                { ...updates.upToDate.body, units: [unit] },
                { ...updates.closed.body, units: [unit] },
                { ...updates.closed.body, planVersion },
                'plan', 7, null
            ];
            for (const value of broken) expect(() => parseUpdatePlan(value, deckId), JSON.stringify(value)).toThrowError(OwnDeckProtocolError);
        });

        it('refuses an answer a cache may keep', async () => {
            const reading = firstValueFrom(api.plan(deckId));
            http.expectOne(candidate => candidate.url === `/api/decks/${deckId}/update-plan`).flush(updates.plan.body);
            await expect(reading).rejects.toBeInstanceOf(OwnDeckProtocolError);
        });

        it('gives the screen the words of its own vocabulary and a title for every unit', () => {
            const units = parseUpdatePlan(updates.plan.body, deckId).units.map(toReviewUnit);
            expect(units.map(unit => [unit.kind, unit.class])).toEqual([['material', 'new'], ['exercise', 'new'], ['material', 'conflict'], ['material', 'deleted']]);
            expect(units[0]!.title).toBe('hablar — говорить');
            expect(toReviewUnit({ ...parseUpdatePlan(updates.plan.body, deckId).units[0]!, title: '' }).title).toBe(UPDATE_TEXT.untitled.material);
            expect(toReviewUnit({ ...parseUpdatePlan(updates.plan.body, deckId).units[0]!, kind: 'ORDER', title: null }).title).toBe(UPDATE_TEXT.untitled.order);
        });
    });

    describe('the sides of a unit', () => {
        const material = updates.unit.material;
        const exercise = updates.unit.exercise;
        const materialKey = material.body.key;
        const materialBody = material.body;
        const exerciseKey = exercise.body.key;

        it('asks for exactly one unit of the named plan and parses the material example', async () => {
            const reading = firstValueFrom(api.unit(deckId, 'MATERIAL', materialKey, planVersion));
            const request = http.expectOne(candidate => candidate.url === `/api/decks/${deckId}/update-plan/units/MATERIAL/${materialKey}`);
            expect(request.request.method).toBe('GET');
            expect(request.request.params.get('planVersion')).toBe(planVersion);
            request.flush(materialBody, { headers });
            const sides = await reading;
            expect(sides.unitId).toBe(material.body.unitId);
            expect(sides.class).toBe('CONFLICT');
            expect(sides.mine).toMatchObject({ kind: 'MATERIAL', memberKey: materialKey, unavailable: false });
            expect(sides.author).toMatchObject({ kind: 'MATERIAL', memberKey: materialKey });
            expect(sides.changedNodeIds).toEqual(material.body.changedNodeIds);
        });

        it('parses the exercise example with a missing own side, and projects it into words', async () => {
            const reading = firstValueFrom(api.unit(deckId, 'EXERCISE', exerciseKey, planVersion));
            http.expectOne(candidate => candidate.url === `/api/decks/${deckId}/update-plan/units/EXERCISE/${exerciseKey}`).flush(exercise.body, { headers });
            const sides = await reading;
            expect(sides.mine).toBeNull();
            expect(sides.author).toMatchObject({ kind: 'EXERCISE', type: 'FREE_RESPONSE', enabled: true, objectiveTitle: 'Спрягать hablar' });
            expect(sideLines(sides.author!)).toEqual([
                { label: 'Тип', values: ['Ввести ответ'] },
                { label: 'Вопрос', values: ['Что значит hablar?'] },
                { label: 'Допустимые ответы', values: ['говорить'] },
                { label: 'Цель', values: ['Спрягать hablar'] }
            ]);
        });

        it('parses the objective, metadata and order examples', () => {
            const envelope = (kind: string, side: unknown) => ({ unitId: `${kind}:${deckId}`, kind, key: deckId, class: 'SAFE', reason: null, planVersion, mine: side, author: side, changedNodeIds: [] });
            const objective = parseUnitSides(envelope('OBJECTIVE', updates.unit.objective.side), { kind: 'OBJECTIVE', key: deckId, planVersion });
            expect(objective.author).toEqual({ kind: 'OBJECTIVE', title: 'Спрягать hablar' });
            const metadata = parseUnitSides(envelope('METADATA', updates.unit.metadata.side), { kind: 'METADATA', key: deckId, planVersion });
            expect(sideLines(metadata.mine!).map(line => line.values[0])).toEqual(['Испанский язык: базовые глаголы', 'Сорок глаголов на каждый день']);
            const order = parseUnitSides(envelope('ORDER', updates.unit.order.side), { kind: 'ORDER', key: deckId, planVersion });
            expect(sideLines(order.author!)).toEqual([{ label: 'Передвинутые материалы', values: ['«comer — есть»: было место 7, стало 2'] }]);
        });

        it('reads a taken-down revision of the copy as unavailable, without content', () => {
            const taken = { ...materialBody, mine: { memberKey: materialKey, itemRevisionId: material.body.mine.itemRevisionId, unavailable: true } };
            const sides = parseUnitSides(taken, { kind: 'MATERIAL', key: materialKey, planVersion });
            expect(sides.mine).toEqual({ kind: 'MATERIAL', memberKey: materialKey, document: null, unavailable: true });
        });

        it('refuses an answer for another unit or another plan, and sides that break the invariants', () => {
            const request = { kind: 'MATERIAL' as const, key: materialKey, planVersion };
            const broken: unknown[] = [
                { ...materialBody, kind: 'EXERCISE' },
                { ...materialBody, key: exerciseKey, unitId: `MATERIAL:${exerciseKey}` },
                { ...materialBody, planVersion: `sha256:${'1'.repeat(64)}` },
                { ...materialBody, changedNodeIds: [''] },
                { ...materialBody, extra: 1 },
                { ...materialBody, mine: { ...materialBody.mine, document: { formatVersion: 2 } } },
                { ...materialBody, mine: { ...materialBody.mine, document: { ...materialBody.mine.document, root: { ...materialBody.mine.document.root, content: [] } } } },
                { ...materialBody, mine: { ...materialBody.mine, extra: 1 } },
                'sides', null
            ];
            for (const value of broken) expect(() => parseUnitSides(value, request), JSON.stringify(value)).toThrowError(OwnDeckProtocolError);
            expect(() => parseUnitSides({ ...exercise.body, changedNodeIds: ['x'] }, { kind: 'EXERCISE', key: exerciseKey, planVersion })).toThrowError(OwnDeckProtocolError);
        });

        it('refuses to ask for a malformed unit', () => {
            expect(() => http.verify()).not.toThrow();
            const attempts = [api.unit(deckId, 'MATERIAL', 'x', planVersion), api.unit(deckId, 'MATERIAL', materialKey, 'sha256:1'), api.unit('x', 'MATERIAL', materialKey, planVersion)];
            for (const attempt of attempts) attempt.subscribe({ error: error => expect(error).toBeInstanceOf(OwnDeckProtocolError) });
        });
    });

    describe('problems', () => {
        const problem = (fixture: { status: number; body: unknown }, headers: Record<string, string> = {}) => new HttpErrorResponse({
            status: fixture.status, error: fixture.body, headers: new HttpHeaders(headers)
        });
        const p = updates.problems;

        it('names every failure of the contract', () => {
            expect(updateFailureOf(problem(p.notFound))).toBe('not-found');
            expect(updateFailureOf(problem(p.invalidRequest))).toBe('invalid');
            expect(updateFailureOf(problem(p.invalidChoice))).toBe('invalid');
            expect(updateFailureOf(problem(p.dependencyDeclined))).toBe('invalid');
            expect(updateFailureOf(problem(p.stale))).toBe('stale');
            expect(updateFailureOf(problem(p.preparing))).toBe('preparing');
            expect(updateFailureOf(problem(p.detached))).toBe('detached');
            expect(updateFailureOf(problem(p.sourceUnavailable))).toBe('source-unavailable');
            expect(updateFailureOf(problem(p.idempotencyConflict))).toBe('other');
            expect(updateFailureOf(problem(p.limit))).toBe('limit');
            expect(updateFailureOf(problem(p.busy, { 'Retry-After': '1' }))).toBe('busy');
            expect(updateFailureOf(problem({ status: 409, body: { ...p.stale.body, code: 'SOMETHING_ELSE' } }))).toBe('other');
            expect(updateFailureOf(problem({ status: 422, body: { code: 'SOMETHING_ELSE' } }))).toBe('other');
            expect(updateFailureOf(new HttpErrorResponse({ status: 429 }))).toBe('rate-limited');
            expect(updateFailureOf(new HttpErrorResponse({ status: 0 }))).toBe('unknown');
            expect(updateFailureOf(new HttpErrorResponse({ status: 503 }))).toBe('unknown');
            expect(updateFailureOf(new HttpErrorResponse({ status: 403 }))).toBe('other');
            expect(updateFailureOf(new Error('shape'))).toBe('unknown');
        });

        it('reads the pause of a busy server from Retry-After, else from the retryAfter member, and refuses nonsense', () => {
            expect(retryAfterSeconds(problem(p.busy, { 'Retry-After': '1' }))).toBe(1);
            expect(retryAfterSeconds(problem(p.busy))).toBe(p.busy.body.retryAfter);
            expect(retryAfterSeconds(problem(p.busy, { 'Retry-After': '7' }))).toBe(7);
            expect(retryAfterSeconds(problem({ status: 503, body: { retryAfter: 0 } }))).toBeNull();
            expect(retryAfterSeconds(problem({ status: 503, body: { retryAfter: 'soon' } }), )).toBeNull();
            expect(retryAfterSeconds(problem(p.busy, { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' }))).toBe(p.busy.body.retryAfter);
            expect(retryAfterSeconds(new Error('x'))).toBeNull();
        });

        it('names the unit and the reason of a 400 the apply command refuses', () => {
            expect(invalidChoiceOf(problem(p.invalidChoice))).toEqual({ reason: 'choice_not_allowed', unitId: p.invalidChoice.body.unitId, dependency: null });
            expect(invalidChoiceOf(problem(p.dependencyDeclined))).toEqual({
                reason: 'dependency_declined', unitId: p.dependencyDeclined.body.unitId, dependency: p.dependencyDeclined.body.dependency
            });
            expect(invalidChoiceOf(problem(p.invalidRequest))).toBeNull();
            expect(invalidChoiceOf(problem({ status: 400, body: { code: 'INVALID_REQUEST', reason: 'something_new' } }))).toBeNull();
            expect(invalidChoiceOf(problem({ status: 400, body: { reason: 'unknown_unit', unitId: 'not a unit id' } }))).toEqual({ reason: 'unknown_unit', unitId: null, dependency: null });
            expect(invalidChoiceOf(problem({ status: 409, body: p.invalidChoice.body }))).toBeNull();
            for (const reason of INVALID_REASONS) expect(invalidChoiceOf(problem({ status: 400, body: { reason } }))?.reason).toBe(reason);
        });

        it('asks a busy server once more after its pause, then gives up with the busy failure', async () => {
            vi.useFakeTimers();
            try {
                const reading = firstValueFrom(api.plan(deckId));
                const path = (candidate: { url: string }) => candidate.url === `/api/decks/${deckId}/update-plan`;
                http.expectOne(path).flush(p.busy.body, { status: 503, statusText: 'Unavailable', headers: { 'Retry-After': '2' } });
                await vi.advanceTimersByTimeAsync(1999);
                http.expectNone(path);
                await vi.advanceTimersByTimeAsync(1);
                http.expectOne(path).flush(updates.plan.body, { headers });
                expect((await reading).state).toBe('READY');

                const failing = firstValueFrom(api.plan(deckId)).catch((error: unknown) => error);
                http.expectOne(path).flush(p.busy.body, { status: 503, statusText: 'Unavailable', headers: { 'Retry-After': '1' } });
                await vi.advanceTimersByTimeAsync(1000);
                http.expectOne(path).flush(p.busy.body, { status: 503, statusText: 'Unavailable', headers: { 'Retry-After': '1' } });
                expect(updateFailureOf(await failing)).toBe('busy');
                await vi.advanceTimersByTimeAsync(60_000);
                http.expectNone(path);
            } finally {
                vi.useRealTimers();
            }
        });

        it('waits no longer than ten seconds, uses a second without a hint, and retries the unit request the same way', async () => {
            vi.useFakeTimers();
            try {
                const key = updates.unit.material.body.key;
                const path = (candidate: { url: string }) => candidate.url === `/api/decks/${deckId}/update-plan/units/MATERIAL/${key}`;
                const reading = firstValueFrom(api.unit(deckId, 'MATERIAL', key, planVersion));
                http.expectOne(path).flush(p.busy.body, { status: 503, statusText: 'Unavailable', headers: { 'Retry-After': '600' } });
                await vi.advanceTimersByTimeAsync(10_000);
                http.expectOne(path).flush(updates.unit.material.body, { headers });
                expect((await reading).unitId).toBe(updates.unit.material.body.unitId);

                const hintless = firstValueFrom(api.unit(deckId, 'MATERIAL', key, planVersion));
                http.expectOne(path).flush({ code: 'UPDATE_PLAN_BUSY' }, { status: 503, statusText: 'Unavailable' });
                await vi.advanceTimersByTimeAsync(1000);
                http.expectOne(path).flush(updates.unit.material.body, { headers });
                await hintless;
            } finally {
                vi.useRealTimers();
            }
        });

        it('does not retry any other failure of a read', async () => {
            const reading = firstValueFrom(api.plan(deckId)).catch((error: unknown) => error);
            http.expectOne(candidate => candidate.url === `/api/decks/${deckId}/update-plan`).flush(p.stale.body, { status: 409, statusText: 'Conflict' });
            expect(updateFailureOf(await reading)).toBe('stale');
        });
    });

    describe('apply', () => {
        const commandId = updates.apply.request.body.commandId;
        const command: ApplyCommand = updates.apply.request.body as ApplyCommand;

        it('posts the strict body of the contract to the apply path and reads 200 APPLIED exactly', async () => {
            const applying = firstValueFrom(api.apply(deckId, command));
            const request = http.expectOne(`/api/decks/${deckId}/update-plan:apply`);
            expect(request.request.method).toBe('POST');
            expect(request.request.body).toEqual(updates.apply.request.body);
            expect(Object.keys(request.request.body)).toEqual(['commandId', 'planVersion', 'acceptSafe', 'choices']);
            request.flush(updates.apply.applied.body, { status: 200, statusText: 'OK', headers });
            const applied = updates.apply.applied.body;
            expect(await applying).toEqual({ status: 'applied', applied: applied.applied, declined: applied.declined, pending: applied.pending, alsoAccepted: applied.alsoAccepted, deckRevisionId: applied.deckRevisionId });
        });

        it('reads the replay of the same command as the same answer, and an applied command that changed nothing (deckRevisionId null)', () => {
            const applied = updates.apply.applied.body;
            expect(parseApplyAnswer(applied, 200, commandId)).toMatchObject({ status: 'applied', alsoAccepted: 1 });
            expect(parseApplyAnswer({ ...applied, deckRevisionId: null }, 200, commandId)).toMatchObject({ status: 'applied', deckRevisionId: null });
            expect(parseApplyAnswer({ ...applied, commandId: commandId.toUpperCase() }, 200, commandId).status).toBe('applied');
        });

        it('reads 202 APPLYING for a job and refuses an answer for another command or one that breaks the shape', () => {
            expect(parseApplyAnswer(updates.apply.queued.body, 202, commandId)).toEqual({ status: 'applying' });
            const applied = updates.apply.applied.body;
            const { alsoAccepted: _alsoAccepted, ...withoutAlso } = applied;
            const { commandId: _commandId, ...withoutCommand } = applied;
            for (const [value, status] of [
                [{ ...updates.apply.queued.body, commandId: '1b6e0a58-3c5e-4d1b-8f55-2e4b79f1b5a1' }, 202],
                [{ ...updates.apply.queued.body, extra: 1 }, 202],
                [applied, 202],
                [updates.apply.queued.body, 200],
                [withoutAlso, 200],
                [withoutCommand, 200],
                [{ ...applied, alsoAccepted: '1' }, 200],
                [{ ...applied, alsoAccepted: -1 }, 200],
                [{ ...applied, applied: 1.5 }, 200],
                [{ ...applied, baseRevisionId: 'x' }, 200],
                [{ ...applied, deckRevisionId: 'x' }, 200],
                [{ ...applied, extra: 1 }, 200],
                [{ ...applied, state: 'DONE' }, 200],
                ['x', 200]
            ] as const) expect(() => parseApplyAnswer(value, status, commandId), JSON.stringify(value)).toThrowError(OwnDeckProtocolError);
        });

        it('sends the choices of the contract example in the wire words of its classes', () => {
            const classOf = new Map(updates.plan.body.units.map(unit => [unit.unitId, unit.class as WireClass] as const));
            const allowed = updates.constants.choicesPerClass as Record<string, readonly string[]>;
            for (const choice of updates.apply.request.body.choices) {
                expect(allowed[classOf.get(choice.unitId)!]!, choice.unitId).toContain(choice.choice);
            }
        });

        it('refuses to send a malformed command', () => {
            api.apply(deckId, { ...command, commandId: 'x' }).subscribe({ error: error => expect(error).toBeInstanceOf(OwnDeckProtocolError) });
            api.apply(deckId, { ...command, planVersion: 'x' }).subscribe({ error: error => expect(error).toBeInstanceOf(OwnDeckProtocolError) });
        });
    });
});
