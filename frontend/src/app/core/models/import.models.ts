export type ImportSourceType = 'apkg' | 'csv' | 'tsv' | 'txt';
export type ImportMode = 'create_new' | 'merge_into_existing';
export type ImportJobStatus = 'queued' | 'processing' | 'completed' | 'failed' | 'canceled';
export type ImportJobType = 'import_job' | 'export_job';

export interface ImportFieldInfo {
    name: string;
    fieldType?: string | null;
}

export interface ImportPreviewRequest {
    sourceMediaId: string;
    sourceType: ImportSourceType;
    targetDeckId?: string | null;
    sampleSize?: number | null;
}

export interface ImportPreviewResponse {
    sourceFields: ImportFieldInfo[];
    targetFields: ImportFieldInfo[];
    suggestedMapping: Record<string, string>;
    sample: Array<Record<string, string>>;
}

export interface CreateImportJobRequest {
    sourceMediaId: string;
    sourceType: ImportSourceType;
    sourceName?: string | null;
    sourceSizeBytes?: number | null;
    targetDeckId?: string | null;
    mode?: ImportMode | null;
    deckName?: string | null;
    fieldMapping?: Record<string, string> | null;
}

export interface CreateExportJobRequest {
    userDeckId: string;
    format: ImportSourceType;
}

export interface ImportJobResponse {
    jobId: string;
    jobType: ImportJobType;
    status: ImportJobStatus;
    sourceType: ImportSourceType;
    sourceName?: string | null;
    sourceLocation?: string | null;
    sourceSizeBytes?: number | null;
    sourceMediaId?: string | null;
    targetDeckId?: string | null;
    mode?: ImportMode | null;
    totalItems?: number | null;
    processedItems?: number | null;
    fieldMapping?: Record<string, string> | null;
    deckName?: string | null;
    resultMediaId?: string | null;
    createdAt?: string | null;
    updatedAt?: string | null;
    startedAt?: string | null;
    completedAt?: string | null;
    errorMessage?: string | null;
}

export interface UploadImportSourceResponse {
    mediaId: string;
    fileName: string;
    sizeBytes: number;
    sourceType: ImportSourceType;
}
