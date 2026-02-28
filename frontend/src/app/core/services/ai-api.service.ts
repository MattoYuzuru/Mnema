import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, map, of } from 'rxjs';
import { appConfig } from '../../app.config';
import {
    AiProviderCredential,
    AiRuntimeCapabilities,
    CreateAiProviderRequest,
    CreateAiJobRequest,
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
        return this.http.get<AiRuntimeCapabilities>(`${this.baseUrl}/runtime/capabilities`);
    }

    createProvider(request: CreateAiProviderRequest): Observable<AiProviderCredential> {
        return this.http.post<AiProviderCredential>(`${this.baseUrl}/providers`, request);
    }

    deleteProvider(id: string): Observable<void> {
        return this.http.delete<void>(`${this.baseUrl}/providers/${id}`);
    }

    createJob(request: CreateAiJobRequest): Observable<AiJobResponse> {
        return this.http.post<AiJobResponse>(`${this.baseUrl}/jobs`, request);
    }

    getJob(jobId: string): Observable<AiJobResponse> {
        return this.http.get<AiJobResponse>(`${this.baseUrl}/jobs/${jobId}`);
    }

    getJobResult(jobId: string): Observable<AiJobResultResponse> {
        return this.http.get<AiJobResultResponse>(`${this.baseUrl}/jobs/${jobId}/results`);
    }

    listJobs(deckId: string, limit: number = 20): Observable<AiJobResponse[]> {
        return this.http.get<AiJobResponse[]>(`${this.baseUrl}/jobs`, {
            params: {
                deckId,
                limit
            }
        });
    }

    cancelJob(jobId: string): Observable<AiJobResponse> {
        return this.http.post<AiJobResponse>(`${this.baseUrl}/jobs/${jobId}/cancel`, {});
    }

    createImportPreview(request: AiImportPreviewRequest): Observable<AiJobResponse> {
        return this.http.post<AiJobResponse>(`${this.baseUrl}/imports/preview`, request);
    }

    createImportGenerate(request: AiImportGenerateRequest): Observable<AiJobResponse> {
        return this.http.post<AiJobResponse>(`${this.baseUrl}/imports/generate`, request);
    }
}
