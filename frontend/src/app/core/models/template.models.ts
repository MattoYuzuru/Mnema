export interface CardTemplateDTO {
    templateId: string;
    ownerId: string;
    name: string;
    description: string;
    isPublic: boolean;
    createdAt: string;
    updatedAt: string;
    layout: {
        front: string[];
        back: string[];
    };
    aiProfile?: {
        prompt: string;
        fieldsMapping: Record<string, string>;
    } | null;
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
