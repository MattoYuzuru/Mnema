import { AiRuntimeCapabilities } from '../../core/models/ai.models';

export type AiModelKind = 'text' | 'tts' | 'stt' | 'image' | 'video';

export const AI_DEFAULT_MODEL_OPTION = '__default__';
export const AI_CUSTOM_MODEL_OPTION = '__custom__';

export interface AiModelOption {
    value: string;
    label: string;
    badge?: string;
    note?: string;
}

const CATALOG: Record<string, Partial<Record<AiModelKind, AiModelOption[]>>> = {
    openai: {
        text: [
            { value: 'gpt-5-mini', label: 'GPT-5 mini', badge: 'Best value', note: 'Balanced quality/cost for generated cards and audits.' },
            { value: 'gpt-5-nano', label: 'GPT-5 nano', badge: 'Cheapest', note: 'Fastest low-cost option for simple drafts.' },
            { value: 'gpt-4.1-mini', label: 'GPT-4.1 mini', badge: 'Legacy value', note: 'Still useful, but no longer the main default.' },
            { value: 'gpt-5.1', label: 'GPT-5.1', badge: 'Quality', note: 'Use when accuracy matters more than cost.' }
        ],
        tts: [
            { value: 'gpt-4o-mini-tts', label: 'GPT-4o mini TTS', badge: 'Best value', note: 'Current OpenAI speech default.' },
            { value: 'tts-1', label: 'TTS-1', badge: 'Fast' },
            { value: 'tts-1-hd', label: 'TTS-1 HD', badge: 'Quality' }
        ],
        stt: [
            { value: 'gpt-4o-mini-transcribe', label: 'GPT-4o mini Transcribe', badge: 'Best value' },
            { value: 'gpt-4o-transcribe', label: 'GPT-4o Transcribe', badge: 'Quality' },
            { value: 'whisper-1', label: 'Whisper', badge: 'Legacy' }
        ],
        image: [
            { value: 'gpt-image-1-mini', label: 'GPT Image 1 mini', badge: 'Best value' },
            { value: 'gpt-image-1', label: 'GPT Image 1', badge: 'Quality' },
            { value: 'gpt-image-1.5', label: 'GPT Image 1.5', badge: 'Latest' }
        ],
        video: [
            { value: 'sora-2', label: 'Sora 2', badge: 'Best value' },
            { value: 'sora-2-pro', label: 'Sora 2 Pro', badge: 'Quality' }
        ]
    },
    gemini: {
        text: [
            { value: 'gemini-2.5-flash-lite', label: 'Gemini 2.5 Flash-Lite', badge: 'Cheapest' },
            { value: 'gemini-2.5-flash', label: 'Gemini 2.5 Flash', badge: 'Best value' },
            { value: 'gemini-3-flash', label: 'Gemini 3 Flash', badge: 'Latest value' },
            { value: 'gemini-3-pro', label: 'Gemini 3 Pro', badge: 'Quality' }
        ],
        tts: [
            { value: 'gemini-2.5-flash-preview-tts', label: 'Gemini 2.5 Flash Preview TTS', badge: 'Best value' },
            { value: 'gemini-2.5-pro-preview-tts', label: 'Gemini 2.5 Pro Preview TTS', badge: 'Quality' }
        ],
        stt: [
            { value: 'gemini-2.5-flash-lite', label: 'Gemini 2.5 Flash-Lite', badge: 'Cheapest' },
            { value: 'gemini-2.5-flash', label: 'Gemini 2.5 Flash', badge: 'Best value' }
        ],
        image: [
            { value: 'imagen-4.0-fast-generate-001', label: 'Imagen 4 Fast', badge: 'Cheapest' },
            { value: 'gemini-2.5-flash-image', label: 'Gemini 2.5 Flash Image', badge: 'Best value' },
            { value: 'imagen-4.0-generate-001', label: 'Imagen 4', badge: 'Quality' },
            { value: 'imagen-4.0-ultra-generate-001', label: 'Imagen 4 Ultra', badge: 'Premium' }
        ]
    },
    anthropic: {
        text: [
            { value: 'claude-haiku-4-5', label: 'Claude Haiku 4.5', badge: 'Best value' },
            { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6', badge: 'Quality' },
            { value: 'claude-opus-4-8', label: 'Claude Opus 4.8', badge: 'Premium' }
        ]
    },
    qwen: {
        text: [
            { value: 'qwen-plus-latest', label: 'Qwen Plus latest', badge: 'Best value' },
            { value: 'qwen-turbo-latest', label: 'Qwen Turbo latest', badge: 'Cheapest' },
            { value: 'qwen3-omni-flash', label: 'Qwen3 Omni Flash', badge: 'Multimodal' }
        ],
        tts: [
            { value: 'qwen3-tts-flash', label: 'Qwen3 TTS Flash', badge: 'Best value' }
        ],
        stt: [
            { value: 'qwen3-asr-flash', label: 'Qwen3 ASR Flash', badge: 'Best value' },
            { value: 'qwen3-omni-flash', label: 'Qwen3 Omni Flash', badge: 'Multimodal' }
        ],
        image: [
            { value: 'qwen-image', label: 'Qwen Image', badge: 'Best value' },
            { value: 'qwen-image-plus', label: 'Qwen Image Plus', badge: 'Quality' },
            { value: 'qwen-image-max', label: 'Qwen Image Max', badge: 'Premium' }
        ],
        video: [
            { value: 'wan2.2-t2v-plus', label: 'Wan 2.2 T2V Plus', badge: 'Best value' },
            { value: 'wan2.5-t2v-preview', label: 'Wan 2.5 T2V Preview', badge: 'Preview' }
        ]
    },
    grok: {
        text: [
            { value: 'grok-4-1-fast-non-reasoning', label: 'Grok 4.1 Fast Non-Reasoning', badge: 'Best value' },
            { value: 'grok-4.3', label: 'Grok 4.3', badge: 'Quality' }
        ],
        image: [
            { value: 'grok-imagine-image', label: 'Grok Imagine Image', badge: 'Best value' },
            { value: 'grok-imagine-image-quality', label: 'Grok Imagine Image Quality', badge: 'Quality' },
            { value: 'grok-imagine-image-pro', label: 'Grok Imagine Image Pro', badge: 'Premium' }
        ],
        video: [
            { value: 'grok-imagine-video', label: 'Grok Imagine Video', badge: 'Best value' },
            { value: 'grok-imagine-video-1.5-preview', label: 'Grok Imagine Video 1.5 Preview', badge: 'Preview' }
        ]
    },
    ollama: {
        text: [
            { value: 'qwen3:8b', label: 'Qwen3 8B', badge: 'Local value' },
            { value: 'qwen3:4b', label: 'Qwen3 4B', badge: 'Low memory' }
        ]
    }
};

export function normalizeAiProvider(provider?: string | null): string {
    if (!provider) return '';
    const normalized = provider.trim().toLowerCase();
    if (normalized === 'claude' || normalized.includes('anthropic')) return 'anthropic';
    if (normalized.includes('openai')) return 'openai';
    if (normalized.includes('gemini') || normalized.includes('google')) return 'gemini';
    if (normalized === 'xai' || normalized === 'x.ai') return 'grok';
    if (normalized === 'dashscope' || normalized === 'aliyun' || normalized === 'alibaba') return 'qwen';
    return normalized;
}

export function modelOptions(provider: string,
                             kind: AiModelKind,
                             runtimeCapabilities?: AiRuntimeCapabilities | null): AiModelOption[] {
    const normalized = normalizeAiProvider(provider);
    if (normalized === 'ollama') {
        const runtimeModels = runtimeCapabilities?.ollama?.models || [];
        const runtime = runtimeModels
            .filter(model => Array.isArray(model.capabilities) && model.capabilities.includes(kind))
            .map(model => ({ value: model.name, label: model.name, badge: 'Local' }))
            .filter(model => model.value && model.value.trim().length > 0)
            .sort((a, b) => a.value.localeCompare(b.value, undefined, { sensitivity: 'base' }));
        if (runtime.length > 0) {
            return uniqueOptions(runtime);
        }
    }
    return uniqueOptions(CATALOG[normalized]?.[kind] || []);
}

export function defaultModel(provider: string,
                             kind: AiModelKind,
                             runtimeCapabilities?: AiRuntimeCapabilities | null): string {
    return modelOptions(provider, kind, runtimeCapabilities)[0]?.value || '';
}

export function modelSelectOptions(provider: string,
                                   kind: AiModelKind,
                                   runtimeCapabilities?: AiRuntimeCapabilities | null): AiModelOption[] {
    const recommended = modelOptions(provider, kind, runtimeCapabilities);
    const currentDefault = recommended[0]?.value || '';
    return [
        {
            value: AI_DEFAULT_MODEL_OPTION,
            label: currentDefault ? `Default (${currentDefault})` : 'Default provider model',
            badge: 'Default',
            note: 'Use the server-side default for this provider.'
        },
        ...recommended,
        { value: AI_CUSTOM_MODEL_OPTION, label: 'Custom model', badge: 'Custom', note: 'Type an exact provider model id.' }
    ];
}

export function resolveModelChoice(value: string, options: AiModelOption[]): string {
    const normalized = value.trim();
    if (!normalized) {
        return AI_DEFAULT_MODEL_OPTION;
    }
    return options.some(option => option.value === normalized) ? normalized : AI_CUSTOM_MODEL_OPTION;
}

export function isCustomModelChoice(value: string, options: AiModelOption[]): boolean {
    return resolveModelChoice(value, options) === AI_CUSTOM_MODEL_OPTION;
}

export function modelHelpText(provider: string,
                              kind: AiModelKind,
                              selectedValue: string,
                              runtimeCapabilities?: AiRuntimeCapabilities | null): string {
    const options = modelOptions(provider, kind, runtimeCapabilities);
    const explicit = selectedValue.trim();
    if (!explicit) {
        const fallback = options[0]?.value || 'provider default';
        return `Default: ${fallback}. Leave as Default to use the backend fallback.`;
    }
    const option = options.find(item => item.value === explicit);
    if (option?.note) {
        return option.note;
    }
    return `Custom model: ${explicit}.`;
}

function uniqueOptions(options: AiModelOption[]): AiModelOption[] {
    const seen = new Set<string>();
    const result: AiModelOption[] = [];
    for (const option of options) {
        const value = option.value?.trim();
        if (!value || seen.has(value)) {
            continue;
        }
        seen.add(value);
        result.push({ ...option, value });
    }
    return result;
}
