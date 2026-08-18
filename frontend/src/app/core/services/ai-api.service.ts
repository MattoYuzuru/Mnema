import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, map, of, throwError } from 'rxjs';
import { appConfig } from '../../app.config';
import {
    AiProviderCredential,
    AiRuntimeCapabilities,
    CreateAiProviderRequest,
    CreateAiJobRequest,
    AiJobPreflightResponse,
    AiJobResponse,
    AiJobResultResponse,
    AiImportPreviewRequest,
    AiImportGenerateRequest
} from '../models/ai.models';

@Injectable({ providedIn: 'root' })
export class AiApiService {
    private readonly baseUrl = appConfig.aiApiBaseUrl;
    private static readonly SYSTEM_PROVIDER_ID = '00000000-0000-0000-0000-000000000001';

    constructor(private http: HttpClient) {}

    listProviders(): Observable<AiProviderCredential[]> {
        if (!appConfig.features.aiEnabled) {
            return of([]);
        }
        return this.http.get<AiProviderCredential[]>(`${this.baseUrl}/providers`).pipe(
            map(list => {
                if (!appConfig.features.aiSystemProviderEnabled) {
                    return list;
                }
                const hasSystem = list.some(item =>
                    item.provider?.toLowerCase() === appConfig.features.aiSystemProviderName.toLowerCase()
                );
                if (hasSystem) {
                    return list;
                }
                const systemProvider: AiProviderCredential = {
                    id: AiApiService.SYSTEM_PROVIDER_ID,
                    provider: appConfig.features.aiSystemProviderName,
                    alias: 'local',
                    status: 'active',
                    createdAt: new Date(0).toISOString(),
                    updatedAt: new Date().toISOString(),
                    lastUsedAt: null
                };
                return [systemProvider, ...list];
            })
            ,
            catchError(() => {
                if (!appConfig.features.aiSystemProviderEnabled) {
                    return of([]);
                }
                return of([{
                    id: AiApiService.SYSTEM_PROVIDER_ID,
                    provider: appConfig.features.aiSystemProviderName,
                    alias: 'local',
                    status: 'active',
                    createdAt: new Date(0).toISOString(),
                    updatedAt: new Date().toISOString(),
                    lastUsedAt: null
                } as AiProviderCredential]);
            })
        );
    }

    getRuntimeCapabilities(): Observable<AiRuntimeCapabilities> {
        return this.whenEnabled(() => this.http.get<AiRuntimeCapabilities>(`${this.baseUrl}/runtime/capabilities`));
    }

    createProvider(request: CreateAiProviderRequest): Observable<AiProviderCredential> {
        return this.whenEnabled(() => this.http.post<AiProviderCredential>(`${this.baseUrl}/providers`, request));
    }

    deleteProvider(id: string): Observable<void> {
        return this.whenEnabled(() => this.http.delete<void>(`${this.baseUrl}/providers/${id}`));
    }

    createJob(request: CreateAiJobRequest): Observable<AiJobResponse> {
        return this.whenEnabled(() =>
            this.http.post<AiJobResponse>(`${this.baseUrl}/jobs`, this.normalizeSystemProviderJobRequest(request))
        );
    }

    preflightJob(request: CreateAiJobRequest): Observable<AiJobPreflightResponse> {
        return this.whenEnabled(() =>
            this.http.post<AiJobPreflightResponse>(`${this.baseUrl}/jobs/preflight`, this.normalizeSystemProviderJobRequest(request))
        );
    }

    getJob(jobId: string): Observable<AiJobResponse> {
        return this.whenEnabled(() => this.http.get<AiJobResponse>(`${this.baseUrl}/jobs/${jobId}`));
    }

    getJobResult(jobId: string): Observable<AiJobResultResponse> {
        return this.whenEnabled(() => this.http.get<AiJobResultResponse>(`${this.baseUrl}/jobs/${jobId}/results`));
    }

    listJobs(deckId: string, limit: number = 20): Observable<AiJobResponse[]> {
        return this.whenEnabled(() =>
            this.http.get<AiJobResponse[]>(`${this.baseUrl}/jobs`, {
                params: {
                    deckId,
                    limit
                }
            })
        );
    }

    cancelJob(jobId: string): Observable<AiJobResponse> {
        return this.whenEnabled(() => this.http.post<AiJobResponse>(`${this.baseUrl}/jobs/${jobId}/cancel`, {}));
    }

    retryFailedJob(jobId: string): Observable<AiJobResponse> {
        return this.whenEnabled(() => this.http.post<AiJobResponse>(`${this.baseUrl}/jobs/${jobId}/retry-failed`, {}));
    }

    createImportPreview(request: AiImportPreviewRequest): Observable<AiJobResponse> {
        return this.whenEnabled(() =>
            this.http.post<AiJobResponse>(`${this.baseUrl}/imports/preview`, this.normalizeSystemProviderImportRequest(request))
        );
    }

    createImportGenerate(request: AiImportGenerateRequest): Observable<AiJobResponse> {
        return this.whenEnabled(() =>
            this.http.post<AiJobResponse>(`${this.baseUrl}/imports/generate`, this.normalizeSystemProviderImportRequest(request))
        );
    }

    private whenEnabled<T>(request: () => Observable<T>): Observable<T> {
        if (!appConfig.features.aiEnabled) {
            return throwError(() => new AiTemporarilyUnavailableError());
        }
        return request();
    }

    private normalizeSystemProviderImportRequest<T extends { providerCredentialId?: string | null }>(request: T): T {
        if (request.providerCredentialId !== AiApiService.SYSTEM_PROVIDER_ID) {
            return request;
        }
        const normalized = { ...request };
        delete normalized.providerCredentialId;
        return normalized;
    }

    private normalizeSystemProviderJobRequest(request: CreateAiJobRequest): CreateAiJobRequest {
        const providerCredentialId = request.params?.['providerCredentialId'];
        if (providerCredentialId !== AiApiService.SYSTEM_PROVIDER_ID) {
            return request;
        }
        const params = { ...(request.params ?? {}) };
        delete params['providerCredentialId'];
        return {
            ...request,
            params
        };
    }
}

export class AiTemporarilyUnavailableError extends Error {
    readonly code = 'AI_TEMPORARILY_UNAVAILABLE';

    constructor() {
        super('AI is temporarily unavailable');
        this.name = 'AiTemporarilyUnavailableError';
    }
}
