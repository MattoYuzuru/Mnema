import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, defer, map, of, throwError } from 'rxjs';

import { appConfig } from '../../app.config';
import {
    BRANCHES,
    BRANCH_ACCEPTED,
    Branch,
    EXPERIENCES,
    Experience,
    JOURNEY_ACTIONS,
    JOURNEY_STATES,
    Journey,
    JourneyAction,
    JourneyCommand,
    OnboardingProtocolError,
    OnboardingRead,
    OnboardingReadResult,
    OnboardingView,
    PreferencesCommand,
    SAMPLE_FREE_AI_CHECKS,
    SampleDeck,
    SampleDeckCommand,
    STEP_BRANCH,
    STEP_KEYS,
    STEP_KEY_RULE,
    StepKey,
    WEEKLY_TARGET,
    isCommandId
} from './onboarding.models';
import {
    MILESTONE_KEYS,
    MILESTONE_STATES,
    Milestone,
    MilestoneKey,
    PathSummary,
    isMilestoneKey,
    milestoneDefinition
} from './path.models';

const ROW_VERSION = /^(?:0|[1-9][0-9]{0,18})$/u;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/u;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const VIEW_KEYS = ['enabled', 'journey', 'milestones', 'path', 'rhythm', 'sampleDeck'];
const JOURNEY_KEYS = ['state', 'eligible', 'autoOffer', 'tourVersion', 'branch', 'stepKey', 'experience', 'weeklyTarget', 'offeredAt', 'finishedAt',
    'replayCount', 'rowVersion'];

/**
 * HTTP boundary of the onboarding journey. Envelopes are parsed strictly; the `ETag` is an opaque validator echoed as
 * `If-None-Match`, so an unchanged view costs a `304` without a body. A command is validated against the contract's transition
 * rules before it is sent. Failures are the raw `HttpErrorResponse`; an answer outside the contract is an `OnboardingProtocolError`.
 */
@Injectable({ providedIn: 'root' })
export class OnboardingApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/u, '');

    /** `GET /api/onboarding`. A `304` means the held view is current. */
    read(etag: string | null = null): Observable<OnboardingReadResult> {
        return defer(() => {
            const headers = etag === null ? undefined : new HttpHeaders({ 'If-None-Match': etag });
            return this.http.get<unknown>(`${this.baseUrl}/onboarding`, { observe: 'response', headers });
        }).pipe(
            map((response): OnboardingReadResult => {
                if (response.status === 304) return { kind: 'not-modified' };
                const read = parseRead(response, true);
                return { kind: 'view', ...read };
            }),
            // Angular surfaces every non-2xx status, 304 included, as an error.
            catchError((error: unknown) => error instanceof HttpErrorResponse && error.status === 304
                ? of<OnboardingReadResult>({ kind: 'not-modified' }) : throwError(() => error))
        );
    }

    /** `PUT /api/onboarding/journey`: one tour command. Idempotent by `commandId`; a retry after an unknown outcome sends the same one. */
    command(body: JourneyCommand): Observable<OnboardingRead> {
        return defer(() => this.http.put<unknown>(`${this.baseUrl}/onboarding/journey`, validateJourneyCommand(body), { observe: 'response' }))
            .pipe(map(response => parseRead(response, false)));
    }

    /**
     * `POST /api/onboarding/sample-deck`: makes the account's sample copy, or answers the live one (201 / 200); either way the answer is
     * the whole view with the copy's `deckId`. Idempotent by `commandId`, so a retry after an unknown outcome sends the same one.
     */
    createSampleDeck(body: SampleDeckCommand): Observable<OnboardingRead> {
        return defer(() => this.http.post<unknown>(`${this.baseUrl}/onboarding/sample-deck`, validateSampleDeckCommand(body), { observe: 'response' }))
            .pipe(map(response => {
                const read = parseRead(response, false, [200, 201]);
                // An answer that names no copy is not an answer to this command.
                if (read.view.sampleDeck.deckId === null) throw protocol('The sample deck is missing from the answer.');
                return read;
            }));
    }

    /** `PUT /api/onboarding/preferences`: the survey answers that belong to onboarding. */
    preferences(body: PreferencesCommand): Observable<OnboardingRead> {
        return defer(() => this.http.put<unknown>(`${this.baseUrl}/onboarding/preferences`, validatePreferencesCommand(body), { observe: 'response' }))
            .pipe(map(response => parseRead(response, false)));
    }
}

function protocol(message: string): OnboardingProtocolError { return new OnboardingProtocolError(message); }

function record(value: unknown): Record<string, unknown> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw protocol('Expected object.');
    return value as Record<string, unknown>;
}

function exact(value: unknown, keys: readonly string[]): Record<string, unknown> {
    const object = record(value);
    const actual = Object.keys(object);
    if (actual.length !== keys.length || actual.some(key => !keys.includes(key))) throw protocol('Unexpected shape.');
    return object;
}

function within(value: unknown, required: readonly string[], optional: readonly string[]): Record<string, unknown> {
    const object = record(value);
    if (Object.keys(object).some(key => !required.includes(key) && !optional.includes(key))) throw protocol('Unexpected field.');
    if (required.some(key => !(key in object))) throw protocol('Missing field.');
    return object;
}

function bool(value: unknown): boolean {
    if (typeof value !== 'boolean') throw protocol('Invalid flag.');
    return value;
}

function count(value: unknown, minimum = 0): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum) throw protocol('Invalid number.');
    return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T {
    if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw protocol('Invalid enum value.');
    return value as T;
}

function nullable<T>(value: unknown, parse: (value: unknown) => T): T | null { return value === null ? null : parse(value); }

function instant(value: unknown): string {
    if (typeof value !== 'string' || !INSTANT.test(value) || Number.isNaN(Date.parse(value))) throw protocol('Invalid timestamp.');
    return value;
}

function rowVersion(value: unknown): string {
    if (typeof value !== 'string' || !ROW_VERSION.test(value)) throw protocol('Invalid row version.');
    return value;
}

function weeklyTarget(value: unknown): number {
    const target = count(value, WEEKLY_TARGET.min);
    if (target > WEEKLY_TARGET.max) throw protocol('Weekly target out of range.');
    return target;
}

function parseJourney(value: unknown): Journey {
    const body = exact(value, JOURNEY_KEYS);
    const branch = nullable(body['branch'], item => oneOf<Branch>(item, BRANCHES));
    const stepKey = nullable(body['stepKey'], item => oneOf<StepKey>(item, STEP_KEYS));
    const state = oneOf(body['state'], JOURNEY_STATES);
    // A branch step belongs to its own branch only; a shared step never fixes a branch.
    if (stepKey !== null && branch !== null && STEP_BRANCH[stepKey] !== null && STEP_BRANCH[stepKey] !== branch) throw protocol('Step of another branch.');
    return {
        state, eligible: bool(body['eligible']), autoOffer: bool(body['autoOffer']), tourVersion: count(body['tourVersion'], 1),
        branch, stepKey, experience: nullable(body['experience'], item => oneOf<Experience>(item, EXPERIENCES)),
        weeklyTarget: nullable(body['weeklyTarget'], weeklyTarget), offeredAt: nullable(body['offeredAt'], instant),
        finishedAt: nullable(body['finishedAt'], instant), replayCount: count(body['replayCount']), rowVersion: rowVersion(body['rowVersion'])
    };
}

function parseSampleDeck(value: unknown): SampleDeck {
    const body = exact(value, ['available', 'deckId', 'updateAvailable', 'freeChecksLeft']);
    const deckId = nullable(body['deckId'], item => {
        if (typeof item !== 'string' || !UUID.test(item)) throw protocol('Invalid deck id.');
        return item;
    });
    const freeChecksLeft = count(body['freeChecksLeft']);
    if (freeChecksLeft > SAMPLE_FREE_AI_CHECKS) throw protocol('Free checks out of range.');
    return { available: bool(body['available']), deckId, updateAvailable: bool(body['updateAvailable']), freeChecksLeft };
}

function parseMilestone(value: unknown): Milestone {
    const body = exact(value, ['key', 'state', 'progress', 'target', 'achievedAt']);
    const key = body['key'];
    if (!isMilestoneKey(key)) throw protocol('Unknown milestone.');
    const state = oneOf(body['state'], MILESTONE_STATES);
    const target = count(body['target'], 1);
    const progress = count(body['progress']);
    const achievedAt = nullable(body['achievedAt'], instant);
    if (target !== milestoneDefinition(key).target || progress > target) throw protocol('Milestone target out of the registry.');
    // DONE: achieved, at the target. PARTIAL: started and not achieved. TODO: nothing yet.
    const consistent = state === 'DONE' ? achievedAt !== null && progress === target
        : state === 'PARTIAL' ? achievedAt === null && progress > 0 && progress < target : achievedAt === null && progress === 0;
    if (!consistent) throw protocol('Milestone state does not match its progress.');
    return { key, state, progress, target, achievedAt };
}

/**
 * `milestones` and `path` of the view. Off (`enabled=false`): `[]` and `null`. On: the visible milestones in registry order without a
 * repeat, and `path` agreeing with them: `done` the ones achieved, `total` how many are visible, `hidden` the rest of the registry in
 * registry order. A milestone that is always visible is never hidden.
 */
function parsePath(milestones: unknown, path: unknown, enabled: boolean): { readonly milestones: readonly Milestone[]; readonly path: PathSummary | null } {
    if (!enabled) {
        if (!Array.isArray(milestones) || milestones.length > 0 || path !== null) throw protocol('A disabled view carries no path.');
        return { milestones: [], path: null };
    }
    if (!Array.isArray(milestones) || milestones.length > MILESTONE_KEYS.length) throw protocol('Invalid milestones.');
    const parsed = milestones.map(parseMilestone);
    const order = parsed.map(milestone => MILESTONE_KEYS.indexOf(milestone.key));
    if (order.some((position, index) => index > 0 && position <= order[index - 1])) throw protocol('Milestones are not in registry order.');
    const summary = exact(path, ['done', 'total', 'hidden']);
    const hidden = summary['hidden'];
    if (!Array.isArray(hidden) || !hidden.every((key): key is MilestoneKey => isMilestoneKey(key))) throw protocol('Invalid hidden milestones.');
    const visible = new Set<string>(parsed.map(milestone => milestone.key));
    const rest = MILESTONE_KEYS.filter(key => !visible.has(key));
    if (hidden.length !== rest.length || hidden.some((key, index) => key !== rest[index])) throw protocol('Hidden milestones do not complete the registry.');
    if (hidden.some(key => milestoneDefinition(key).visibility === 'ALWAYS')) throw protocol('A milestone that is always visible is hidden.');
    const done = parsed.filter(milestone => milestone.state === 'DONE').length;
    if (summary['done'] !== done || summary['total'] !== parsed.length) throw protocol('The path counts do not match the milestones.');
    return { milestones: parsed, path: { done, total: parsed.length, hidden } };
}

/** Parses the strict envelope of the view. Exported for the contract spec. */
export function parseOnboardingView(value: unknown): OnboardingView {
    const body = exact(value, VIEW_KEYS);
    const enabled = bool(body['enabled']);
    const journey = nullable(body['journey'], parseJourney);
    // Disabled means no tour state at all; enabled always has a journey (`NOT_OFFERED` when there is no row).
    if (enabled !== (journey !== null)) throw protocol('The journey must be present exactly when onboarding is enabled.');
    const rhythm = body['rhythm'];
    if (rhythm !== null && (typeof rhythm !== 'object' || Array.isArray(rhythm))) throw protocol('Invalid rhythm.');
    const { milestones, path } = parsePath(body['milestones'], body['path'], enabled);
    return { enabled, journey, milestones, path, rhythm, sampleDeck: parseSampleDeck(body['sampleDeck']) };
}

function parseRead(response: HttpResponse<unknown>, validatorRequired: boolean, statuses: readonly number[] = [200]): OnboardingRead {
    if (!statuses.includes(response.status)) throw protocol('Unexpected onboarding status.');
    const directives = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!directives.includes('private') || !directives.includes('no-store')) throw protocol('Onboarding response can be cached.');
    const validator = response.headers.get('ETag');
    if (validator === null ? validatorRequired : (validator.length < 1 || validator.length > 256 || validator.startsWith('W/'))) throw protocol('Missing strong ETag.');
    return { view: parseOnboardingView(response.body), etag: validator };
}

/** Refuses what the server would refuse (contract `transitions` and `invalid`), so a defect never costs a round trip. */
export function validateJourneyCommand(body: JourneyCommand): JourneyCommand {
    const command = within(body, ['commandId', 'rowVersion', 'action'], ['branch', 'stepKey']);
    if (!isCommandId(command['commandId'])) throw protocol('Invalid command id.');
    rowVersion(command['rowVersion']);
    const action = oneOf<JourneyAction>(command['action'], JOURNEY_ACTIONS);
    const stepKey = 'stepKey' in command ? oneOf<StepKey>(command['stepKey'], STEP_KEYS) : null;
    const branch = 'branch' in command ? oneOf<Branch>(command['branch'], BRANCHES) : null;
    const rule = STEP_KEY_RULE[action];
    if (rule === 'required' && stepKey === null) throw protocol('The action needs a step.');
    if (rule === 'forbidden' && stepKey !== null) throw protocol('The action takes no step.');
    if (branch !== null && !BRANCH_ACCEPTED[action]) throw protocol('The action takes no branch.');
    if (branch !== null && stepKey !== null && STEP_BRANCH[stepKey] !== null && STEP_BRANCH[stepKey] !== branch) throw protocol('The step belongs to another branch.');
    return body;
}

export function validateSampleDeckCommand(body: SampleDeckCommand): SampleDeckCommand {
    const command = within(body, ['commandId'], []);
    if (!isCommandId(command['commandId'])) throw protocol('Invalid command id.');
    return body;
}

export function validatePreferencesCommand(body: PreferencesCommand): PreferencesCommand {
    const command = within(body, ['commandId', 'rowVersion'], ['experience', 'weeklyTarget']);
    if (!isCommandId(command['commandId'])) throw protocol('Invalid command id.');
    rowVersion(command['rowVersion']);
    if (!('experience' in command) && !('weeklyTarget' in command)) throw protocol('A preference is required.');
    if ('experience' in command) nullable(command['experience'], item => oneOf<Experience>(item, EXPERIENCES));
    if ('weeklyTarget' in command) nullable(command['weeklyTarget'], weeklyTarget);
    return body;
}
