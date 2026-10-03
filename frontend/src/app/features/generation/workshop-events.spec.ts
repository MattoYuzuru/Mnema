import { ArtifactSummary, GenerationEvent, SessionDetail, parseSessionDetail } from './generation.models';
import { Arrival, WorkshopModel, applyEvents, isNewer, mergeSession } from './workshop-events';
import { artifactWith, clone, examples, ids, sessionWith } from './generation-test-data';

const block = (suffix: string, text: string) => ({ id: `00000000-0000-4000-8000-0000000001${suffix}`, type: 'paragraph', version: 1, attrs: {},
    content: [{ id: `00000000-0000-4000-8000-0000000002${suffix}`, type: 'text', version: 1, attrs: { text, marks: [] }, content: [] }] });

function model(artifacts: Record<string, unknown>[], overrides: Record<string, unknown> = {}): WorkshopModel {
    return { session: parseSessionDetail(sessionWith(artifacts, overrides)), drafts: {}, usage: null, arrival: null };
}

function event(seq: number, type: GenerationEvent['type'], payload: Record<string, unknown>, artifactId: string | null = ids.second): GenerationEvent {
    return { seq: String(seq), type, sessionId: ids.sessionId, artifactId, occurredAt: '2026-10-02T09:00:01Z', ...payload } as GenerationEvent;
}

const appended = (seq: number, generation: number, startIndex: number, ...blocks: ReturnType<typeof block>[]): GenerationEvent =>
    event(seq, 'BLOCKS_APPENDED', { generation, startIndex, blocks });
const counter = () => { let value = 0; return () => ++value; };
const generating = () => model([artifactWith(ids.first, 0, 'PROPOSED'), artifactWith(ids.second, 1, 'GENERATING', { rowVersion: '2', currentRevisionId: null })]);

describe('applyEvents', () => {
    it('compares decimal-string versions numerically, beyond 2^53', () => {
        expect(isNewer('10', '9')).toBe(true);
        expect(isNewer('9007199254740993', '9007199254740992')).toBe(true);
        expect(isNewer('5', '5')).toBe(false);
    });

    it('places blocks by index and marks where the newest arrived', () => {
        const first = applyEvents(generating(), [appended(1, 1, 0, block('01', 'Раз'))], 0n, counter());
        expect(first.model.drafts[ids.second]!.blocks).toHaveLength(1);
        expect(first.model.arrival).toEqual<Arrival>({ artifactId: ids.second, fromIndex: 0, token: 1 });
        const second = applyEvents(first.model, [appended(2, 1, 1, block('02', 'Два'), block('03', 'Три'))], first.lastSeq, counter());
        expect(second.model.drafts[ids.second]!.blocks.map(node => node.id)).toEqual([
            '00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000102', '00000000-0000-4000-8000-000000000103']);
        expect(second.model.arrival?.fromIndex).toBe(1);
        expect(second.lastSeq).toBe(2n);
    });

    it('skips events at or below the cursor, so a replay never duplicates a block', () => {
        const events = [appended(1, 1, 0, block('01', 'Раз')), appended(2, 1, 1, block('02', 'Два'))];
        const once = applyEvents(generating(), events, 0n, counter());
        const again = applyEvents(once.model, events, once.lastSeq, counter());
        expect(again.model).toEqual(once.model);
        expect(again.model.drafts[ids.second]!.blocks).toHaveLength(2);
    });

    it('rewrites from the start index inside one generation (a replay with a lower cursor is idempotent)', () => {
        const base = applyEvents(generating(), [appended(1, 1, 0, block('01', 'Раз'), block('02', 'Два'))], 0n, counter());
        const redone = applyEvents(base.model, [appended(5, 1, 1, block('09', 'Иначе'))], base.lastSeq, counter());
        expect(redone.model.drafts[ids.second]!.blocks.map(node => node.id)).toEqual([
            '00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000109']);
    });

    it('discards earlier blocks when a higher generation starts, ignores a lower one and ignores a gap', () => {
        const base = applyEvents(generating(), [appended(1, 1, 0, block('01', 'Раз'), block('02', 'Два'))], 0n, counter());
        const restarted = applyEvents(base.model, [appended(2, 2, 0, block('07', 'Заново'))], base.lastSeq, counter());
        expect(restarted.model.drafts[ids.second]).toMatchObject({ generation: 2, blocks: [{ id: '00000000-0000-4000-8000-000000000107' }] });
        const stale = applyEvents(restarted.model, [appended(3, 1, 1, block('08', 'Старое'))], restarted.lastSeq, counter());
        expect(stale.model.drafts[ids.second]!.blocks).toHaveLength(1);
        const gap = applyEvents(restarted.model, [appended(4, 2, 5, block('06', 'Дыра'))], restarted.lastSeq, counter());
        expect(gap.model.drafts[ids.second]!.blocks).toHaveLength(1);
        const newGenerationGap = applyEvents(base.model, [appended(5, 3, 2, block('05', 'Дыра'))], base.lastSeq, counter());
        expect(newGenerationGap.model.drafts[ids.second]!.blocks).toHaveLength(2);
    });

    it('ignores blocks of an artifact that is not being written and asks to reconcile for an unknown one', () => {
        const done = applyEvents(generating(), [appended(1, 1, 0, block('01', 'Раз'))].map(e => ({ ...e, artifactId: ids.first }) as GenerationEvent), 0n, counter());
        expect(done.model.drafts).toEqual({});
        const unknown = applyEvents(generating(), [appended(1, 1, 0, block('01', 'Раз'))].map(e => ({ ...e, artifactId: 'a7a70000-0000-4000-8000-0000000000ff' }) as GenerationEvent), 0n, counter());
        expect(unknown.reconcile).toBe(true);
    });

    it('applies a newer artifact state and asks for the detail and a reconcile; an old or equal version changes nothing', () => {
        const proposed = event(3, 'ARTIFACT_STATE', { state: 'PROPOSED', artifactVersion: '4', currentRevisionId: ids.revision, errorCode: null, repinStatus: null });
        const applied = applyEvents(generating(), [proposed], 0n, counter());
        expect(applied.model.session.artifacts[1]).toMatchObject({ state: 'PROPOSED', rowVersion: '4', currentRevisionId: ids.revision });
        expect(applied.reconcile).toBe(true);
        expect(applied.staleArtifacts).toEqual([ids.second]);
        const replay = applyEvents(applied.model, [{ ...proposed, seq: '9' } as GenerationEvent], applied.lastSeq, counter());
        expect(replay.reconcile).toBe(false);
        expect(replay.model.session).toBe(applied.model.session);
    });

    it('drops a draft when the artifact is queued again or fails, and records the error code', () => {
        const base = applyEvents(generating(), [appended(1, 1, 0, block('01', 'Раз'))], 0n, counter());
        const failed = applyEvents(base.model, [event(2, 'ARTIFACT_STATE', { state: 'FAILED', artifactVersion: '3', currentRevisionId: null,
            errorCode: 'PROVIDER_UNAVAILABLE', repinStatus: null })], base.lastSeq, counter());
        expect(failed.model.drafts).toEqual({});
        expect(failed.model.session.artifacts[1]).toMatchObject({ state: 'FAILED', errorCode: 'PROVIDER_UNAVAILABLE' });
        const queued = applyEvents(base.model, [event(3, 'ARTIFACT_STATE', { state: 'QUEUED', artifactVersion: '5', currentRevisionId: null,
            errorCode: null, repinStatus: null })], base.lastSeq, counter());
        expect(queued.model.drafts).toEqual({});
    });

    it('asks to reconcile for an event of an unknown artifact, a media slot move, and takes usage and session rows', () => {
        const unknown = applyEvents(generating(), [event(1, 'ARTIFACT_STATE', { state: 'PROPOSED', artifactVersion: '4', currentRevisionId: ids.revision,
            errorCode: null, repinStatus: null }, 'a7a70000-0000-4000-8000-0000000000ff')], 0n, counter());
        expect(unknown.reconcile).toBe(true);
        const slot = applyEvents(generating(), [event(2, 'MEDIA_SLOT_STATE', { slotKey: 'a1', kind: 'AUDIO', state: 'READY',
            assetId: '00000000-0000-4000-a000-000000000001', errorCode: null }, ids.first)], 0n, counter());
        expect(slot).toMatchObject({ reconcile: true, staleArtifacts: [ids.first] });
        const usage = applyEvents(generating(), [event(3, 'USAGE_UPDATED', { reservedCredits: 21, spentCredits: 11, balanceRemainingCredits: 297,
            deferredUntil: '2026-10-03T21:00:00Z' }, null)], 0n, counter());
        expect(usage.model.usage).toEqual({ reservedCredits: 21, spentCredits: 11, balanceRemainingCredits: 297, deferredUntil: '2026-10-03T21:00:00Z' });
        const counts = clone(examples['sessionDetail'].artifactCounts);
        const sessionRow = applyEvents(generating(), [event(4, 'SESSION_STATE', { state: 'REVIEW', rowVersion: '13', artifactCounts: counts }, null)], 0n, counter());
        expect(sessionRow.model.session).toMatchObject({ state: 'REVIEW', rowVersion: '13' });
        const older = applyEvents(generating(), [event(5, 'SESSION_STATE', { state: 'CLOSED', rowVersion: '3', artifactCounts: counts }, null)], 0n, counter());
        expect(older.model.session.state).toBe('RUNNING');
    });
});

describe('mergeSession', () => {
    const detail = (artifacts: Record<string, unknown>[], rowVersion = '12'): SessionDetail => parseSessionDetail(sessionWith(artifacts, { rowVersion }));
    const proposed = (rowVersion: string, extra: Record<string, unknown> = {}): Record<string, unknown> => artifactWith(ids.first, 0, 'PROPOSED', { rowVersion, ...extra });

    it('takes the first read as it is', () => {
        const fresh = detail([proposed('4')]);
        expect(mergeSession(null, fresh)).toBe(fresh);
    });

    it('never goes back in time: a newer held artifact or session row wins over an older read', () => {
        const held = detail([proposed('6', { state: 'PUBLISHED' })], '14');
        const fresh = detail([proposed('4')], '12');
        const merged = mergeSession(held, fresh);
        expect(merged.artifacts[0]).toMatchObject({ state: 'PUBLISHED', rowVersion: '6' });
        expect(merged.rowVersion).toBe('14');
    });

    it('takes the newer read and keeps the artifacts in ordinal order', () => {
        const held = detail([proposed('4')], '12');
        const second = artifactWith(ids.second, 1, 'PROPOSED', { rowVersion: '2' });
        const first = proposed('5');
        const merged = mergeSession(held, detail([second, first], '13'));
        expect(merged.artifacts.map((artifact: ArtifactSummary) => artifact.artifactId)).toEqual([ids.first, ids.second]);
        expect(merged.artifacts[0]!.rowVersion).toBe('5');
        expect(merged.rowVersion).toBe('13');
    });
});
