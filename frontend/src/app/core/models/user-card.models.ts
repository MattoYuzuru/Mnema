export type CardContentValue = string | { mediaId: string; url?: string | null; kind?: 'image' | 'audio' | 'video' };

export interface UserCardDTO {
    userCardId: string;
    publicCardId: string;
    isCustom: boolean;
    isDeleted: boolean;
    personalNote?: string | null;
    tags?: string[] | null;
    effectiveContent: Record<string, CardContentValue>;
}

export interface CreateCardRequest {
    content: Record<string, CardContentValue>;
    orderIndex?: number;
    tags?: string[];
}

export interface MissingFieldStat {
    field: string;
    missingCount: number;
    sampleCards: UserCardDTO[];
}

export interface MissingFieldSummary {
    fields: MissingFieldStat[];
    sampleLimit: number;
}
