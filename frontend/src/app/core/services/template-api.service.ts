import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Page } from '../models/page.models';
import { CardTemplateDTO, FieldTemplateDTO, CreateFieldTemplateRequest } from '../models/template.models';
import { appConfig } from '../../app.config';

@Injectable({ providedIn: 'root' })
export class TemplateApiService {
    private readonly baseUrl = `${appConfig.coreApiBaseUrl}/templates`;

    constructor(private http: HttpClient) {}

    getTemplates(page: number, limit: number, scope: 'public' | 'mine' | 'all' = 'public'): Observable<Page<CardTemplateDTO>> {
        let params = new HttpParams()
            .set('page', page.toString())
            .set('limit', limit.toString());
        if (scope) {
            params = params.set('scope', scope);
        }
        return this.http.get<Page<CardTemplateDTO>>(this.baseUrl, { params });
    }

    getTemplate(templateId: string): Observable<CardTemplateDTO> {
        return this.http.get<CardTemplateDTO>(`${this.baseUrl}/${templateId}`);
    }

    createTemplate(dto: Partial<CardTemplateDTO>): Observable<CardTemplateDTO> {
        return this.http.post<CardTemplateDTO>(this.baseUrl, dto);
    }

    patchTemplate(templateId: string, dto: Partial<CardTemplateDTO>): Observable<CardTemplateDTO> {
        return this.http.patch<CardTemplateDTO>(`${this.baseUrl}/${templateId}`, dto);
    }

    deleteTemplate(templateId: string): Observable<void> {
        return this.http.delete<void>(`${this.baseUrl}/${templateId}`);
    }

    addField(templateId: string, field: CreateFieldTemplateRequest): Observable<FieldTemplateDTO> {
        return this.http.post<FieldTemplateDTO>(`${this.baseUrl}/${templateId}/fields`, field);
    }

    patchField(templateId: string, fieldId: string, field: Partial<FieldTemplateDTO>): Observable<FieldTemplateDTO> {
        return this.http.patch<FieldTemplateDTO>(`${this.baseUrl}/${templateId}/fields/${fieldId}`, field);
    }

    deleteField(templateId: string, fieldId: string): Observable<void> {
        return this.http.delete<void>(`${this.baseUrl}/${templateId}/fields/${fieldId}`);
    }
}
