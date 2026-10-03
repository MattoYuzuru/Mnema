import { NativeNode } from '../../content/native-document';
import { ArtifactSummary, GenerationEvent, SessionDetail, UsageUpdate } from './generation.models';

/** Draft blocks of one artifact: the preview that grows while it is written. The stored revision stays the truth. */
export interface DraftBlocks { readonly generation: number; readonly blocks: readonly NativeNode[]; }

/** The newest blocks arrived at `fromIndex` of `artifactId`; `token` makes every arrival a distinct signal value. */
export interface Arrival { readonly artifactId: string; readonly fromIndex: number; readonly token: number; }

export interface WorkshopModel {
    readonly session: SessionDetail;
    readonly drafts: Readonly<Record<string, DraftBlocks>>;
    readonly usage: UsageUpdate | null;
    readonly arrival: Arrival | null;
}

export interface AppliedEvents {
    readonly model: WorkshopModel;
    /** The highest `seq` seen; events at or below it are skipped on any replay. */
    readonly lastSeq: bigint;
    /** The summaries (title, media counts, published reference) are out of date: read the session again. */
    readonly reconcile: boolean;
    /** Artifacts whose loaded detail is out of date (a new revision, or a media slot changed). */
    readonly staleArtifacts: readonly string[];
}

/** `a` is a newer decimal-string version than `b`. */
export function isNewer(a: string, b: string): boolean {
    return BigInt(a) > BigInt(b);
}

const DRAFTING = ['QUEUED', 'GENERATING'];

/**
 * Folds polled events into the model. Pure and replay-tolerant: events at or below `lastSeq` are skipped, artifact and
 * session updates apply only when their version is newer than what is held, and draft blocks are placed by index inside a
 * `generation` (a higher generation discards the earlier blocks, a lower one is ignored, a gap is ignored). Events of an
 * unknown artifact ask for a reconcile instead of guessing.
 */
export function applyEvents(model: WorkshopModel, events: readonly GenerationEvent[], lastSeq: bigint,
                            nextToken: () => number): AppliedEvents {
    let { session, drafts, usage, arrival } = model;
    let seen = lastSeq;
    let reconcile = false;
    const stale = new Set<string>();
    const patch = (artifactId: string, change: (artifact: ArtifactSummary) => ArtifactSummary): ArtifactSummary | null => {
        const index = session.artifacts.findIndex(artifact => artifact.artifactId === artifactId);
        if (index < 0) return null;
        const updated = change(session.artifacts[index]!);
        session = { ...session, artifacts: session.artifacts.map((artifact, position) => position === index ? updated : artifact) };
        return updated;
    };

    for (const event of events) {
        if (BigInt(event.seq) <= lastSeq) continue;
        if (BigInt(event.seq) > seen) seen = BigInt(event.seq);
        switch (event.type) {
            case 'ARTIFACT_STATE': {
                const known = session.artifacts.find(artifact => artifact.artifactId === event.artifactId);
                if (known === undefined) { reconcile = true; break; }
                if (!isNewer(event.artifactVersion, known.rowVersion)) break;
                reconcile = true;
                if (event.state === 'PROPOSED' || event.currentRevisionId !== known.currentRevisionId) stale.add(event.artifactId);
                patch(event.artifactId, artifact => ({ ...artifact, state: event.state, rowVersion: event.artifactVersion,
                    currentRevisionId: event.currentRevisionId, errorCode: event.errorCode, repinStatus: event.repinStatus }));
                if (event.state === 'QUEUED' || event.state === 'FAILED') {
                    const { [event.artifactId]: _dropped, ...rest } = drafts;
                    drafts = rest;
                }
                break;
            }
            case 'BLOCKS_APPENDED': {
                const known = session.artifacts.find(artifact => artifact.artifactId === event.artifactId);
                if (known === undefined) { reconcile = true; break; }
                if (!DRAFTING.includes(known.state)) break;
                const held = drafts[event.artifactId];
                if (held !== undefined && event.generation < held.generation) break;
                const base = held === undefined || event.generation > held.generation ? [] : held.blocks;
                if (event.startIndex > base.length) break;
                drafts = { ...drafts, [event.artifactId]: {
                    generation: event.generation, blocks: [...base.slice(0, event.startIndex), ...event.blocks] } };
                arrival = { artifactId: event.artifactId, fromIndex: event.startIndex, token: nextToken() };
                break;
            }
            case 'MEDIA_SLOT_STATE':
                reconcile = true;
                stale.add(event.artifactId);
                break;
            case 'USAGE_UPDATED':
                usage = { reservedCredits: event.reservedCredits, spentCredits: event.spentCredits,
                    balanceRemainingCredits: event.balanceRemainingCredits, deferredUntil: event.deferredUntil };
                break;
            case 'SESSION_STATE':
                if (isNewer(event.rowVersion, session.rowVersion)) {
                    session = { ...session, state: event.state, rowVersion: event.rowVersion, artifactCounts: event.artifactCounts };
                }
                break;
        }
    }
    return { model: { session, drafts, usage, arrival }, lastSeq: seen, reconcile, staleArtifacts: [...stale] };
}

/** Keeps the newer of every artifact summary and the newer session row: a read that raced a command never goes back in time. */
export function mergeSession(held: SessionDetail | null, fresh: SessionDetail): SessionDetail {
    if (held === null) return fresh;
    const artifacts = fresh.artifacts.map(artifact => {
        const current = held.artifacts.find(candidate => candidate.artifactId === artifact.artifactId);
        return current !== undefined && isNewer(current.rowVersion, artifact.rowVersion) ? current : artifact;
    });
    const base = isNewer(held.rowVersion, fresh.rowVersion) ? held : fresh;
    // `notes` change without a version bump (archival does not touch the session): the fresh read is always the newer one.
    return { ...base, notes: fresh.notes, artifacts: [...artifacts].sort((left, right) => left.ordinal - right.ordinal) };
}
