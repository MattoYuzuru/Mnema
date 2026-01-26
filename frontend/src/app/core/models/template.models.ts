export interface CardTemplateDTO {
    templateId: string;
    version?: number | null;
    latestVersion?: number | null;
    ownerId: string;
    name: string;
    description: string;
    isPublic: boolean;
    createdAt: string;
    updatedAt: string;
    layout: {
        front: string[];
        back: string[];
        renderMode?: string;
    };
    aiProfile?: {
        prompt: string;
        fieldsMapping: Record<string, string>;
    } | string | null;
    iconUrl?: string | null;
    fields?: FieldTemplateDTO[];
}

export interface FieldTemplateDTO {
    fieldId: string;
    templateId: string;
    name: string;
    label: string;
    fieldType: string;
    isRequired: boolean;
    isOnFront: boolean;
    orderIndex: number;
    defaultValue?: string | null;
    helpText?: string | null;
}

export interface CreateFieldTemplateRequest {
    name: string;
    label: string;
    fieldType: string;
    isRequired: boolean;
    isOnFront: boolean;
    orderIndex: number;
    defaultValue?: string | null;
    helpText?: string | null;
}

export interface CreateTemplateRequest {
    name: string;
    description?: string;
    isPublic: boolean;
    layout: {
        front: string[];
        back: string[];
        renderMode?: string;
    };
    aiProfile?: {
        prompt: string;
        fieldsMapping: Record<string, string>;
    } | string | null;
    iconUrl?: string | null;
    fields?: CreateFieldTemplateRequest[];
}
