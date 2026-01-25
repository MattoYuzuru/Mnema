import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { appConfig } from '../../app.config';
import {
    AiProviderCredential,
    CreateAiProviderRequest,
    CreateAiJobRequest,
    AiJobResponse,
    AiJobResultResponse
} from '../models/ai.models';

@Injectable({ providedIn: 'root' })
export class AiApiService {
    private readonly baseUrl = appConfig.aiApiBaseUrl;

    constructor(private http: HttpClient) {}

    listProviders(): Observable<AiProviderCredential[]> {
        return this.http.get<AiProviderCredential[]>(`${this.baseUrl}/providers`);
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

    cancelJob(jobId: string): Observable<AiJobResponse> {
        return this.http.post<AiJobResponse>(`${this.baseUrl}/jobs/${jobId}/cancel`, {});
    }
}
