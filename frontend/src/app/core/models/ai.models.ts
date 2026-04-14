export type AiProviderStatus = 'active' | 'inactive';

export interface AiProviderCredential {
    id: string;
    provider: string;
    alias?: string | null;
    status: AiProviderStatus;
    createdAt: string;
    lastUsedAt?: string | null;
    updatedAt?: string | null;
}

export interface AiRuntimeCapabilities {
    mode: 'system_managed' | 'user_keys' | string;
    systemProvider?: string | null;
    ollama?: {
        enabled: boolean;
        available: boolean;
        baseUrl: string;
        models: Array<{
            name: string;
            sizeBytes?: number | null;
            modifiedAt?: string | null;
            capabilities: string[];
        }>;
        voices?: string[];
    } | null;
    providers?: Array<{
        key: string;
        displayName: string;
        requiresCredential: boolean;
        text: boolean;
        stt: boolean;
        tts: boolean;
        image: boolean;
        video: boolean;
        gif: boolean;
    }>;
}

export interface CreateAiProviderRequest {
    provider: string;
    alias?: string | null;
    secret: string;
}

export type AiJobType = 'generic' | 'enrich' | 'tts';
export type AiJobStatus = 'queued' | 'processing' | 'completed' | 'partial_success' | 'failed' | 'canceled';
export type AiJobStepStatus = 'queued' | 'processing' | 'completed' | 'failed';

export interface CreateAiJobRequest {
    requestId: string;
    deckId?: string | null;
    type: AiJobType;
    params?: Record<string, unknown> | null;
    inputHash?: string | null;
}

export interface AiJobResponse {
    jobId: string;
    requestId: string;
    deckId?: string | null;
    type: AiJobType;
    status: AiJobStatus;
    progress: number;
    createdAt: string;
    updatedAt?: string | null;
    startedAt?: string | null;
    completedAt?: string | null;
    errorMessage?: string | null;
    providerCredentialId?: string | null;
    provider?: string | null;
    providerAlias?: string | null;
    model?: string | null;
    currentStep?: string | null;
    completedSteps?: number | null;
    totalSteps?: number | null;
    cost?: {
        estimatedInputTokens?: number | null;
        estimatedOutputTokens?: number | null;
        estimatedCost?: number | null;
        estimatedCostCurrency?: string | null;
        actualInputTokens?: number | null;
        actualOutputTokens?: number | null;
        actualCost?: number | null;
        actualCostCurrency?: string | null;
    } | null;
    estimatedSecondsRemaining?: number | null;
    estimatedCompletionAt?: string | null;
    queueAhead?: number | null;
}

export interface AiJobPreflightItem {
    itemType?: string | null;
    cardId?: string | null;
    preview?: string | null;
    fields?: string[] | null;
    plannedStages?: string[] | null;
}

export interface AiJobPreflightResponse {
    deckId?: string | null;
    type: AiJobType;
    providerCredentialId?: string | null;
    provider?: string | null;
    providerAlias?: string | null;
    model?: string | null;
    mode?: string | null;
    normalizedParams?: Record<string, unknown> | null;
    summary?: string | null;
    targetCount?: number | null;
    fields?: string[] | null;
    plannedStages?: string[] | null;
    warnings?: string[] | null;
    items?: AiJobPreflightItem[] | null;
    cost?: AiJobResponse['cost'];
    estimatedSecondsRemaining?: number | null;
    estimatedCompletionAt?: string | null;
    queueAhead?: number | null;
}

export interface AiJobStepResponse {
    stepName: string;
    status: AiJobStepStatus;
    startedAt?: string | null;
    endedAt?: string | null;
    errorSummary?: string | null;
}

export interface AiJobResultResponse {
    jobId: string;
    status: AiJobStatus;
    resultSummary: unknown;
    steps: AiJobStepResponse[];
}

export interface AiResultItemSummary {
    cardId?: string;
    preview?: string;
    status?: string;
    completedStages?: string[];
    errors?: string[];
}

export interface AiSourceCoverageSummary {
    sourceItemsTotal?: number;
    sourceItemsUsed?: number;
    alteredSourceItems?: number;
    missingSourceIndexes?: number[];
    missingNumberedItems?: number[];
}

export interface AiSourceNormalizationSummary {
    extraction?: string;
    reviewedItems?: number;
    normalizedItems?: number;
    model?: string;
    warning?: string;
}

export interface AiQualityGateItemSummary {
    draftIndex?: number;
    decision?: string;
    summary?: string;
    issues?: string[];
    focusFields?: string[];
}

export interface AiQualityGateSummary {
    auditedDrafts?: number;
    flaggedDrafts?: number;
    repairRequested?: number;
    repairedDrafts?: number;
    finalFlaggedDrafts?: number;
    qualityScore?: number;
    model?: string;
    warning?: string;
    items?: AiQualityGateItemSummary[];
    finalItems?: AiQualityGateItemSummary[];
}

export interface AiUsageCallSummary {
    stage?: string;
    attempt?: number;
    requestedCount?: number;
    candidateCount?: number;
    model?: string;
    inputTokens?: number;
    outputTokens?: number;
    cachedInputTokens?: number;
    reasoningOutputTokens?: number;
    durationMs?: number;
}

export interface AiUsageStageSummary {
    inputTokens?: number;
    outputTokens?: number;
    requests?: number;
    model?: string;
    charsGenerated?: number;
    imagesGenerated?: number;
    videosGenerated?: number;
    calls?: AiUsageCallSummary[];
}

export interface AiGenerationUsageSummary {
    textGeneration?: AiUsageStageSummary;
    sourceNormalization?: AiUsageStageSummary;
    draftAudit?: AiUsageStageSummary;
    draftRepair?: AiUsageStageSummary;
    draftFinalAudit?: AiUsageStageSummary;
    tts?: AiUsageStageSummary;
    media?: AiUsageStageSummary;
}

export interface AiStructuredJobResultSummary {
    mode?: string;
    createdCards?: number;
    updatedCards?: number;
    updated?: number;
    candidates?: number;
    requestedCards?: number;
    ttsGenerated?: number;
    ttsCharsGenerated?: number;
    imagesGenerated?: number;
    videosGenerated?: number;
    sourceCoverage?: AiSourceCoverageSummary;
    sourceNormalization?: AiSourceNormalizationSummary;
    qualityGate?: AiQualityGateSummary;
    usage?: AiGenerationUsageSummary;
    fields?: string[];
    items?: AiResultItemSummary[];
}

export interface AiImportPreviewRequest {
    requestId: string;
    deckId: string;
    sourceMediaId: string;
    providerCredentialId?: string | null;
    provider?: string | null;
    model?: string | null;
    sourceType?: string | null;
    encoding?: string | null;
    language?: string | null;
    instructions?: string | null;
    stt?: Record<string, unknown> | null;
}

export interface AiImportGenerateRequest {
    requestId: string;
    deckId: string;
    sourceMediaId: string;
    fields: string[];
    count: number;
    providerCredentialId?: string | null;
    provider?: string | null;
    model?: string | null;
    sourceType?: string | null;
    encoding?: string | null;
    language?: string | null;
    instructions?: string | null;
    stt?: Record<string, unknown> | null;
    tts?: Record<string, unknown> | null;
    image?: Record<string, unknown> | null;
    video?: Record<string, unknown> | null;
}

export interface AiImportPreviewSummary {
    mode?: string;
    summary?: string;
    estimatedCount?: number;
    truncated?: boolean;
    sourceBytes?: number;
    sourceChars?: number;
    detectedCharset?: string;
    sourceType?: string;
    extraction?: string;
    sourcePages?: number;
    ocrPages?: number;
    audioDurationSeconds?: number;
    audioChunks?: number;
}
