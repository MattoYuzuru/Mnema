import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, defer, map } from 'rxjs';

import { appConfig } from '../../app.config';
import { AuthoringProtocolError, requireObject } from './authoring.models';

export type CapabilityReason = 'DISABLED' | 'PROVIDER_NOT_CONFIGURED';

/** Server-owned availability of one optional capability; `reason` is present exactly when it is unavailable. */
export type Capability =
    | { readonly available: true; readonly reason: null }
    | { readonly available: false; readonly reason: CapabilityReason };

export interface LearningCapabilities {
    readonly aiAssessment: Capability;
    readonly speechToText: Capability;
}

/** Capabilities used until the server answers, and whenever it cannot be reached: fail closed. */
export const CAPABILITIES_UNAVAILABLE: LearningCapabilities = {
    aiAssessment: { available: false, reason: 'DISABLED' },
    speechToText: { available: false, reason: 'DISABLED' }
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
    const object = requireObject(response.body, ['aiAssessment', 'speechToText']);
    return { aiAssessment: capability(object['aiAssessment']), speechToText: capability(object['speechToText']) };
}

function capability(value: unknown): Capability {
    const object = requireObject(value, ['available', 'reason']);
    if (object['available'] === true && object['reason'] === null) return { available: true, reason: null };
    if (object['available'] === false && (object['reason'] === 'DISABLED' || object['reason'] === 'PROVIDER_NOT_CONFIGURED')) {
        return { available: false, reason: object['reason'] };
    }
    throw new AuthoringProtocolError('Invalid capability.');
}
