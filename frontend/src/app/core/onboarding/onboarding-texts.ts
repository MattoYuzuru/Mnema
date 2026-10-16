/**
 * Product strings of the onboarding surfaces, copied verbatim from the texts the owner approved (design/prototype/onboarding/texts.js,
 * 2026-10-16). Later tasks add their keys here; a string is never written inline in a component. `{name}` marks a placeholder.
 */
export const ONBOARDING_TEXTS = {
    // Tour chrome
    'tour.counter': 'Шаг {n} из {m}',
    'tour.back': 'Назад',
    'tour.next': 'Дальше',
    'tour.finish': 'Завершить знакомство',
    'tour.finish.armed': 'Удерживайте 2\u00a0секунды, чтобы завершить знакомство',
    'tour.finish.toast': 'Знакомство завершено. Пройти его снова можно в профиле.',
    'tour.gate': 'Нажмите «{action}», чтобы продолжить',
    'tour.missing': 'Этого места сейчас нет на экране. Продолжим со следующего шага.',
    'tour.preparing': 'Готовим колоду…',
    'tour.pause.label': 'Знакомство на паузе',
    'tour.pause.resume': 'Продолжить знакомство',
    'tour.slider.prev': 'Предыдущее пояснение',
    'tour.slider.next': 'Следующее пояснение',
    // The mini-slider's group name: in the prototype markup, not in its text list
    'tour.slider.label': 'Пояснения к шагу'
} as const;

export type OnboardingTextKey = keyof typeof ONBOARDING_TEXTS;

/** The text for `key` with its `{placeholders}` filled; an unfilled one stays visible so a missing value is noticed. */
export function onboardingText(key: OnboardingTextKey, params: Readonly<Record<string, string | number>> = {}): string {
    return ONBOARDING_TEXTS[key].replace(/\{(\w+)\}/gu, (_, name: string) => String(params[name] ?? `{${name}}`));
}
