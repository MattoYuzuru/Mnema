import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { appConfig } from '../../app.config';
import {
    ImportPreviewRequest,
    ImportPreviewResponse,
    CreateImportJobRequest,
    CreateExportJobRequest,
    ImportJobResponse,
    UploadImportSourceResponse,
    ImportSourceType
} from '../models/import.models';

@Injectable({ providedIn: 'root' })
export class ImportApiService {
    private readonly baseUrl = appConfig.importApiBaseUrl;

    constructor(private http: HttpClient) {}

    uploadSource(file: File, sourceType?: ImportSourceType): Observable<UploadImportSourceResponse> {
        const formData = new FormData();
        formData.append('file', file, file.name);

        let params = new HttpParams();
        if (sourceType) {
            params = params.set('sourceType', sourceType);
        }

        return this.http.post<UploadImportSourceResponse>(`${this.baseUrl}/uploads`, formData, { params });
    }

    preview(request: ImportPreviewRequest): Observable<ImportPreviewResponse> {
        return this.http.post<ImportPreviewResponse>(`${this.baseUrl}/previews`, request);
    }

    createImportJob(request: CreateImportJobRequest): Observable<ImportJobResponse> {
        return this.http.post<ImportJobResponse>(`${this.baseUrl}/jobs/import`, request);
    }

    createExportJob(request: CreateExportJobRequest): Observable<ImportJobResponse> {
        return this.http.post<ImportJobResponse>(`${this.baseUrl}/jobs/export`, request);
    }

    getJob(jobId: string): Observable<ImportJobResponse> {
        return this.http.get<ImportJobResponse>(`${this.baseUrl}/jobs/${jobId}`);
    }
}
