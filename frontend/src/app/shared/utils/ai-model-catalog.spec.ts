import {
    AI_CUSTOM_MODEL_OPTION,
    AI_DEFAULT_MODEL_OPTION,
    defaultModel,
    isCustomModelChoice,
    modelOptions,
    modelSelectOptions,
    resolveModelChoice
} from './ai-model-catalog';

describe('ai-model-catalog', () => {
    it('uses current OpenAI value defaults for each implemented media tier', () => {
        expect(defaultModel('openai', 'text')).toBe('gpt-5-mini');
        expect(defaultModel('openai', 'tts')).toBe('gpt-4o-mini-tts');
        expect(defaultModel('openai', 'stt')).toBe('gpt-4o-mini-transcribe');
        expect(defaultModel('openai', 'image')).toBe('gpt-image-1-mini');
        expect(defaultModel('openai', 'video')).toBe('sora-2');
    });

    it('keeps custom and backend-default choices explicit in selectors', () => {
        const options = modelSelectOptions('qwen', 'text');

        expect(options[0].value).toBe(AI_DEFAULT_MODEL_OPTION);
        expect(options.some(option => option.value === 'qwen-plus-latest')).toBeTrue();
        expect(options.at(-1)?.value).toBe(AI_CUSTOM_MODEL_OPTION);
        expect(resolveModelChoice('', options)).toBe(AI_DEFAULT_MODEL_OPTION);
        expect(isCustomModelChoice('my-private-model', options)).toBeTrue();
    });

    it('does not expose provider capabilities that Mnema backend cannot execute yet', () => {
        expect(modelOptions('gemini', 'image').length).toBeGreaterThan(0);
        expect(modelOptions('gemini', 'video')).toEqual([]);
    });
});
