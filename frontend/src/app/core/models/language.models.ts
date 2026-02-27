export type DeckLanguageCode =
    | 'ru'
    | 'en'
    | 'jp'
    | 'sp'
    | 'zh'
    | 'hi'
    | 'ar'
    | 'fr'
    | 'bn'
    | 'pt'
    | 'id';

export interface DeckLanguageOption {
    code: DeckLanguageCode;
    labelKey: string;
}

export const DEFAULT_DECK_LANGUAGE: DeckLanguageCode = 'en';

export const DECK_LANGUAGE_OPTIONS: readonly DeckLanguageOption[] = [
    { code: 'en', labelKey: 'language.code.en' },
    { code: 'ru', labelKey: 'language.code.ru' },
    { code: 'jp', labelKey: 'language.code.jp' },
    { code: 'sp', labelKey: 'language.code.sp' },
    { code: 'zh', labelKey: 'language.code.zh' },
    { code: 'hi', labelKey: 'language.code.hi' },
    { code: 'ar', labelKey: 'language.code.ar' },
    { code: 'fr', labelKey: 'language.code.fr' },
    { code: 'bn', labelKey: 'language.code.bn' },
    { code: 'pt', labelKey: 'language.code.pt' },
    { code: 'id', labelKey: 'language.code.id' }
];
