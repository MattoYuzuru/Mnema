export interface ReviewQueueDTO {
    dueCount: number;
    newCount: number;
    totalRemaining?: number;
    dueTodayCount?: number;
    newTotalCount?: number;
    learningAheadCount?: number;
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
    features?: ReviewClientFeatures | null;
}

export interface ReviewClientFeatures {
    x?: number[];
    meta?: {
        uiMode?: 'binary' | 'quad';
    };
}

export interface ReviewAnswerResponse {
    userCardId?: string;
    answeredCardId?: string;
    next: ReviewNextCardResponse;
    completion?: ReviewCompletionDTO | null;
}

export interface ReviewCompletionDTO {
    firstCompletionToday: boolean;
    completionIndexToday: number;
    reviewDay: string;
    streak: ReviewCompletionStreakDTO;
    session?: ReviewCompletionSessionDTO | null;
}

export interface ReviewCompletionStreakDTO {
    previousStreakDays: number;
    currentStreakDays: number;
    longestStreakDays: number;
}

export interface ReviewCompletionSessionDTO {
    startedAt: string;
    endedAt: string;
    durationMinutes: number;
    reviewCount: number;
    totalResponseMs: number;
}

export interface ReviewPreferencesDTO {
    dailyNewLimit?: number | null;
    learningHorizonHours?: number | null;
    maxReviewPerDay?: number | null;
    dayCutoffHour?: number | null;
    timeZone?: string | null;
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

export type ReviewStatsScope = 'ACCOUNT' | 'DECK';

export interface ReviewStatsFilter {
    fromDate: string;
    toDate: string;
    timeZone: string;
    dayCutoffMinutes: number;
    sessionGapMinutes: number;
    forecastDays: number;
}

export interface ReviewStatsStreak {
    currentStreakDays: number;
    longestStreakDays: number;
    todayStreakDays: number;
    activeToday: boolean;
    currentStreakStartDate: string | null;
    currentStreakEndDate: string | null;
    lastActiveDate: string | null;
}

export interface ReviewStatsOverview {
    reviewCount: number;
    uniqueCardCount: number;
    againCount: number;
    againRatePercent: number;
    successRatePercent: number;
    totalResponseMs: number;
    avgResponseMs: number;
    medianResponseMs: number;
    reviewsPerDay: number;
}

export interface ReviewStatsQueueSnapshot {
    activeCards: number;
    trackedCards: number;
    newCards: number;
    suspendedCards: number;
    dueNow: number;
    dueToday: number;
    dueIn24h: number;
    dueIn7d: number;
    overdue: number;
}

export interface ReviewStatsDailyPoint {
    date: string;
    reviewCount: number;
    uniqueCardCount: number;
    againCount: number;
    hardCount: number;
    goodCount: number;
    easyCount: number;
    totalResponseMs: number;
}

export interface ReviewStatsHourlyPoint {
    hourOfDay: number;
    reviewCount: number;
    againRatePercent: number;
    avgResponseMs: number;
}

export interface ReviewStatsRatingPoint {
    ratingCode: number;
    rating: string;
    reviewCount: number;
    ratioPercent: number;
}

export interface ReviewStatsSourcePoint {
    source: string;
    reviewCount: number;
    ratioPercent: number;
}

export interface ReviewStatsForecastPoint {
    date: string;
    dueCount: number;
}

export interface ReviewStatsSessionDayPoint {
    date: string;
    sessionCount: number;
    firstSessionStartAt: string | null;
    lastSessionEndAt: string | null;
    studiedMinutes: number;
    reviewCount: number;
    totalResponseMs: number;
}

export interface ReviewStatsSessionWindowPoint {
    startedAt: string;
    endedAt: string;
    durationMinutes: number;
    reviewCount: number;
    totalResponseMs: number;
}

export interface ReviewStatsResponse {
    scope: ReviewStatsScope;
    userDeckId?: string | null;
    filter: ReviewStatsFilter;
    streak: ReviewStatsStreak;
    overview: ReviewStatsOverview;
    queue: ReviewStatsQueueSnapshot;
    daily: ReviewStatsDailyPoint[];
    sessionDays: ReviewStatsSessionDayPoint[];
    todaySessions: ReviewStatsSessionWindowPoint[];
    hourly: ReviewStatsHourlyPoint[];
    ratings: ReviewStatsRatingPoint[];
    sources: ReviewStatsSourcePoint[];
    forecast: ReviewStatsForecastPoint[];
}

export interface ReviewStatsRequest {
    userDeckId?: string | null;
    from?: string | null;
    to?: string | null;
    timeZone?: string | null;
    dayCutoffMinutes?: number | null;
    sessionGapMinutes?: number | null;
    forecastDays?: number | null;
}
