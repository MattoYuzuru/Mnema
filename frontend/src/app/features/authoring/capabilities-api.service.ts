import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { AuthoringProtocolError, requireObject } from './authoring.models';

/**
 * Why a capability is unavailable: switched off, no adapter configured, or temporarily down (an open circuit breaker or the
 * global daily budget of the server; the user's own quota is a different thing, see `GET /api/usage`).
 */
export type CapabilityReason = 'DISABLED' | 'PROVIDER_NOT_CONFIGURED' | 'TEMPORARILY_UNAVAILABLE';

const REASONS: readonly CapabilityReason[] = ['DISABLED', 'PROVIDER_NOT_CONFIGURED', 'TEMPORARILY_UNAVAILABLE'];

/** Server-owned availability of one optional capability; `reason` is present exactly when it is unavailable. */
export type Capability =
    | { readonly available: true; readonly reason: null }
    | { readonly available: false; readonly reason: CapabilityReason };

/** The exact key set of `getCapabilities` in `contracts/generation/http.json`. */
export interface LearningCapabilities {
    readonly aiAssessment: Capability;
    readonly speechToText: Capability;
    readonly aiGeneration: Capability;
    readonly textToSpeech: Capability;
    readonly imageSearch: Capability;
    readonly imageGeneration: Capability;
    readonly videoGeneration: Capability;
    readonly webSearch: Capability;
}

const CAPABILITY_KEYS = ['aiAssessment', 'speechToText', 'aiGeneration', 'textToSpeech', 'imageSearch', 'imageGeneration',
    'videoGeneration', 'webSearch'] as const;

/** Capabilities used until the server answers, and whenever it cannot be reached: fail closed. */
export const CAPABILITIES_UNAVAILABLE: LearningCapabilities = {
    aiAssessment: { available: false, reason: 'DISABLED' },
    speechToText: { available: false, reason: 'DISABLED' },
    aiGeneration: { available: false, reason: 'DISABLED' },
    textToSpeech: { available: false, reason: 'DISABLED' },
    imageSearch: { available: false, reason: 'DISABLED' },
    imageGeneration: { available: false, reason: 'DISABLED' },
    videoGeneration: { available: false, reason: 'DISABLED' },
    webSearch: { available: false, reason: 'DISABLED' }
};

@Injectable({ providedIn: 'root' })
export class CapabilitiesApiService {
    private readonly http = inject(HttpClient);
    private readonly baseUrl = appConfig.learningApiBaseUrl.replace(/\/$/, '');

    read(): Observable<LearningCapabilities> {
        return defer(() => this.http.get<unknown>(`${this.baseUrl}/capabilities`, { observe: 'response' }))
            .pipe(map(response => parse(response)));
    }
}

function parse(response: HttpResponse<unknown>): LearningCapabilities {
    const cache = (response.headers.get('Cache-Control') ?? '').toLowerCase().split(',').map(value => value.trim());
    if (!cache.includes('private') || !cache.includes('no-store')) {
        throw new AuthoringProtocolError('Capabilities response can be cached.');
    }
    // Strict on purpose: a missing, renamed or extra key means the client and the server disagree, and the caller then
    // falls back to CAPABILITIES_UNAVAILABLE (fail closed) instead of guessing.
    const object = requireObject(response.body, [...CAPABILITY_KEYS]);
    return {
        aiAssessment: capability(object['aiAssessment']),
        speechToText: capability(object['speechToText']),
        aiGeneration: capability(object['aiGeneration']),
        textToSpeech: capability(object['textToSpeech']),
        imageSearch: capability(object['imageSearch']),
        imageGeneration: capability(object['imageGeneration']),
        videoGeneration: capability(object['videoGeneration']),
        webSearch: capability(object['webSearch'])
    };
}

function capability(value: unknown): Capability {
    const object = requireObject(value, ['available', 'reason']);
    if (object['available'] === true && object['reason'] === null) return { available: true, reason: null };
    const reason = REASONS.find(known => known === object['reason']);
    if (object['available'] === false && reason !== undefined) return { available: false, reason };
    throw new AuthoringProtocolError('Invalid capability.');
}
