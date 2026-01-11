export interface ReviewQueueDTO {
    dueCount: number;
    newCount: number;
    totalRemaining?: number;
}

export interface IntervalDTO {
    at: string;
    display: string;
}

export interface ReviewNextCardResponse {
    userCardId: string | null;
    publicCardId: string | null;
    due: boolean;
    effectiveContent: Record<string, string>;
    intervals: Record<string, IntervalDTO> | null;
    queue: ReviewQueueDTO;
}

export interface ReviewSummaryResponse {
    dueCount: number;
    newCount: number;
}

export interface ReviewAnswerRequest {
    rating: 'AGAIN' | 'HARD' | 'GOOD' | 'EASY';
    responseMs: number;
    source: string;
}

export interface ReviewAnswerResponse {
    userCardId: string;
    next: ReviewNextCardResponse;
}

export interface ReviewPreferencesDTO {
    dailyNewLimit: number | null;
    learningHorizonHours: number | null;
}

export interface ReviewDeckAlgorithmResponse {
    algorithmId: string;
    algorithmParams: Record<string, unknown> | null;
    effectiveAlgorithmParams?: Record<string, unknown> | null;
    pendingMigrationCards: number;
    reviewPreferences?: ReviewPreferencesDTO;
}

export interface UpdateAlgorithmRequest {
    algorithmId: string;
    algorithmParams: Record<string, unknown> | null;
    reviewPreferences?: ReviewPreferencesDTO;
}
