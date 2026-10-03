import { SegmentedOption } from '../../shared/segmented-choice.component';
import { GenerationProblem } from './generation-problem';
import {
    ArtifactErrorCode, ArtifactSummary, BlockingBucket, Effort, GenerationEstimate, NoteArchiveResult, NoteSkipReason, NotesMode, SessionSummary, SlotKind, SlotState
} from './generation.models';

/** Texts and small pure helpers of the composer and the Workshop. Voice: calm and bookish, «Мнема» in dialogue, «ИИ» in labels. */

export const NBSP = '\u00a0';

/** «Подробность»: the live explanation sits under the group and changes with the choice. */
export const EFFORT_OPTIONS: readonly SegmentedOption<Effort>[] = [
    { value: 'AUTO', label: 'Авто', hint: 'Авто: Мнема выберет объём по запросу — обычно 2–4 абзаца.' },
    { value: 'SHORT', label: 'Кратко', hint: 'Кратко: определение и один пример.' },
    { value: 'MEDIUM', label: 'Средне', hint: 'Средне: объяснение и пара примеров.' },
    { value: 'DETAILED', label: 'Подробно', hint: 'Подробно: объяснение, 3–5 примеров, исключения.' }
];

/** «Как оформить заметки»: one material per note (the default) or all notes in one. */
export const NOTES_MODE_OPTIONS: readonly SegmentedOption<NotesMode>[] = [
    { value: 'ONE_PER_NOTE', label: 'Материал на заметку', hint: 'Из каждой заметки получится отдельный материал.' },
    { value: 'MERGE_INTO_ONE', label: 'Объединить в один', hint: 'Из всех заметок получится один материал.' }
];

/** Languages offered for the audio of a material (BCP 47). */
export const AUDIO_LANGUAGES: readonly { readonly value: string; readonly label: string }[] = [
    { value: 'ru', label: 'Русский' }, { value: 'en', label: 'Английский' }, { value: 'ja', label: 'Японский' },
    { value: 'ko', label: 'Корейский' }, { value: 'zh', label: 'Китайский' }, { value: 'es', label: 'Испанский' },
    { value: 'fr', label: 'Французский' }, { value: 'de', label: 'Немецкий' }, { value: 'it', label: 'Итальянский' }
];

/** «4 октября» in the reader's time zone; `null` for an absent or malformed instant. */
export function formatDay(instant: string | null): string | null {
    if (instant === null) return null;
    const time = Date.parse(instant);
    return Number.isFinite(time) ? new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long' }).format(new Date(time)) : null;
}

/** The preflight sentence next to the button: «≈ 6 % лимита». The estimate is a p95 figure: what a reservation holds. */
export function describeEstimate(estimate: GenerationEstimate): string {
    const percent = estimate.percentOfPeriodAllowance.p95;
    return percent < 1 ? `менее 1${NBSP}% лимита` : `≈${NBSP}${percent}${NBSP}% лимита`;
}

export interface UsageExplanation {
    readonly headline: string;
    /** What the user can do about it, each a full sentence. */
    readonly options: readonly string[];
    /** Link to the «ИИ-бюджет» block of the profile (the plans page does not exist yet). */
    readonly plansLink: boolean;
}

/**
 * Pressing «Создать» without enough budget never disables the button: it explains the options (a shorter effort, waiting
 * for the renewal, the plans). The same explanation is built from the estimate (`blockingBuckets`) and from a
 * `USAGE_LIMIT_REACHED` problem, which carry the same members.
 */
export function describeUsageLimit(limit: BlockingBucket, effort: Effort): UsageExplanation {
    if (!limit.offered) {
        return { headline: 'На вашем тарифе это недоступно.',
            options: ['Уберите то, что требует отдельного лимита, или посмотрите тарифы.'], plansLink: true };
    }
    const date = formatDay(limit.renewsAt);
    const options: string[] = [];
    if (effort !== 'SHORT') options.push('Выберите «Кратко»: запрос обойдётся дешевле.');
    if (date !== null && limit.fitsAfterRenewal) {
        options.push(limit.window === 'DAY'
            ? `Подождите до ${date}: дневной лимит обновится, и запроса хватит.`
            : `Подождите до ${date}: лимит обновится, и запроса хватит.`);
    } else if (date !== null) {
        options.push(`После ${date} лимит обновится, но этому запросу всё равно не хватит: сократите запрос.`);
    } else {
        options.push('Сократите запрос или уберите медиа.');
    }
    return { headline: limit.window === 'DAY' ? 'На сегодня лимит ИИ исчерпан.' : 'Не хватит лимита ИИ на этот запрос.',
        options, plansLink: true };
}

/** Why an artifact failed, in words. Whether the limit was charged is stated by {@link failureNote}. */
export function failureReason(code: ArtifactErrorCode | null): string {
    switch (code) {
        case 'INVALID_OUTPUT': return 'Мнема не смогла собрать корректный материал.';
        case 'REFUSAL': return 'Мнема отказалась писать на эту тему. Измените запрос или напишите материал сами.';
        case 'SOURCE_UNAVAILABLE': return 'Заметка, из которой писался материал, изменилась или удалена.';
        case 'PROVIDER_UNAVAILABLE': return 'Сервис ИИ временно недоступен.';
        case 'USAGE_LIMIT': return 'Не хватило лимита ИИ.';
        case 'ESTIMATE_EXCEEDED': return 'Материал вышел больше, чем рассчитывалось.';
        case 'DEADLINE_EXCEEDED': return 'Мнема не успела вовремя.';
        case 'CANCELLED': return 'Остановлено вами.';
        case 'PLAN_FAILED': return 'Не удалось составить план.';
        case null: return 'Что-то пошло не так.';
    }
}

/** A failed artifact is not charged (no provider call, or a provider-side failure); stopping by hand is the user's own. */
export function failureNote(code: ArtifactErrorCode | null): string | null {
    return code === 'CANCELLED' ? null : 'За этот материал лимит не списан.';
}

export type StatusShape = 'ready' | 'writing' | 'queued' | 'failed' | 'done' | 'rejected' | 'stale';

export interface ArtifactStatus { readonly shape: StatusShape; readonly word: string; }

/** The shape (●◐○✕✓…) says the status without colour; the word is what a screen reader hears. */
export function artifactStatus(artifact: ArtifactSummary): ArtifactStatus {
    switch (artifact.state) {
        case 'QUEUED': return { shape: 'queued', word: 'ждёт очереди' };
        case 'GENERATING': return { shape: 'writing', word: 'пишется' };
        case 'REVISING': return { shape: 'writing', word: 'правится' };
        case 'PROPOSED': {
            const { total, ready, failed } = artifact.mediaSlotCounts;
            if (ready === total) return { shape: 'ready', word: 'готов' };
            return failed > 0 ? { shape: 'failed', word: 'медиа не удалось' } : { shape: 'writing', word: 'готовятся медиа' };
        }
        case 'FAILED': return { shape: 'failed', word: 'не удался' };
        case 'REJECTED': return { shape: 'rejected', word: 'отклонён' };
        case 'STALE': return { shape: 'stale', word: 'нужно решение' };
        case 'PUBLISHED': return { shape: 'done', word: 'в колоде' };
        case 'HANDED_OFF': return { shape: 'done', word: 'передан в редактор' };
    }
}

function plural(count: number, one: string, few: string, many: string): string {
    const lastTwo = count % 100;
    const last = count % 10;
    if (lastTwo >= 11 && lastTwo <= 14) return many;
    if (last === 1) return one;
    return last >= 2 && last <= 4 ? few : many;
}

export interface SummaryCounts {
    readonly ready: number;
    readonly writing: number;
    readonly failed: number;
    readonly stale: number;
    readonly done: number;
    readonly rejected: number;
}

/** «7 готово · 2 пишутся · 1 не удался»: only what is non-zero, ready first. */
export function summarize(artifacts: readonly ArtifactSummary[]): string {
    const count = (shape: StatusShape): number => artifacts.filter(artifact => artifactStatus(artifact).shape === shape).length;
    return summarizeCounts({ ready: count('ready'), writing: count('writing') + count('queued'), failed: count('failed'),
        stale: count('stale'), done: count('done'), rejected: count('rejected') });
}

/** The same sentence for a session summary of the deck list: what the server counted. */
export function describeSessionProgress(session: SessionSummary): string {
    const counts = session.artifactCounts;
    return summarizeCounts({ ready: session.approvableCount, writing: counts.QUEUED + counts.GENERATING + counts.REVISING,
        failed: counts.FAILED, stale: counts.STALE, done: 0, rejected: 0 });
}

function summarizeCounts({ ready, writing, failed, stale, done, rejected }: SummaryCounts): string {
    const parts: string[] = [];
    if (ready > 0) parts.push(`${ready}${NBSP}готово`);
    if (writing > 0) parts.push(`${writing}${NBSP}${plural(writing, 'пишется', 'пишутся', 'пишутся')}`);
    if (failed > 0) parts.push(`${failed}${NBSP}${plural(failed, 'не удался', 'не удались', 'не удались')}`);
    if (stale > 0) parts.push(`${stale}${NBSP}${plural(stale, 'нужно решение', 'нужно решение', 'нужно решение')}`);
    if (done > 0) parts.push(`${done}${NBSP}в колоде`);
    if (rejected > 0) parts.push(`${rejected}${NBSP}отклонено`);
    return parts.length > 0 ? parts.join(' · ') : 'Пока ничего';
}

/** «Материал 3 из 10». */
export function positionLabel(index: number, total: number): string {
    return `Материал ${index + 1} из ${total}`;
}

/** The first line of the prompt, for the page subtitle; never the whole text. */
export function promptExcerpt(prompt: string | null, limit = 120): string | null {
    const trimmed = prompt?.trim() ?? '';
    if (trimmed.length === 0) return null;
    const line = trimmed.split(/\r?\n/u)[0]!;
    return line.length > limit ? `${line.slice(0, limit - 1).trimEnd()}…` : line;
}

/** A sentence for the placeholder frame of one media slot: what is happening, in words. */
export function slotCaption(kind: SlotKind, state: SlotState): string {
    const what = kind === 'AUDIO' ? 'аудио' : kind === 'IMAGE' ? 'изображение' : 'видео';
    switch (state) {
        case 'PENDING': return `Ждём очереди: ${what}`;
        case 'GENERATING': return kind === 'IMAGE' ? 'Подбираем изображение…' : kind === 'AUDIO' ? 'Озвучиваем…' : 'Готовим видео…';
        case 'VERIFYING': return `Проверяем: ${what}`;
        case 'READY': return `Готово: ${what}`;
        case 'FAILED': return `Не удалось подготовить: ${what}`;
        case 'REMOVED': return `Убрано: ${what}`;
    }
}

/** What the page says about a failed command. `problem.uncertain` means the same command is sent again on retry. */
export function problemMessage(problem: GenerationProblem): string {
    if (problem.uncertain) {
        return 'Не удалось подтвердить действие: связь прервалась или сервер не ответил. Повторите — будет отправлена та же команда.';
    }
    switch (problem.status) {
        case 400: return 'Запрос не принят. Проверьте текст и настройки.';
        case 404: return 'Мастерская или материал больше недоступны.';
        case 412: return 'Материал или колода изменились. Мы обновили данные: проверьте и повторите.';
        case 428: return 'Не удалось подтвердить версию. Обновите страницу.';
        case 409: return conflictMessage(problem);
        case 422: return limitMessage(problem);
        default: return 'Не удалось выполнить действие. Попробуйте ещё раз.';
    }
}

function conflictMessage(problem: GenerationProblem): string {
    switch (problem.code) {
        case 'GENERATION_STATE_CONFLICT':
            switch (problem.reason) {
                case 'MEDIA_NOT_READY': return 'Медиа ещё не готовы. Подождите или правьте материал сами.';
                case 'SOURCE_STALE': return 'Заметка изменилась, пока писался материал. Попробуйте снова или правьте сам материал.';
                case 'NOT_RETRYABLE': return 'Мнема отказалась писать этот материал: повтор не поможет.';
                default: return 'Это действие уже недоступно: состояние изменилось. Мы обновили данные.';
            }
        case 'USAGE_LIMIT_REACHED': return 'Не хватает лимита ИИ. Подробности — в профиле, в блоке «ИИ-бюджет».';
        case 'CAPABILITY_UNAVAILABLE': return 'ИИ сейчас недоступен. Попробуйте позже или напишите материал сами.';
        case 'SOURCE_UNAVAILABLE': return 'Заметка, из которой писался материал, изменилась или удалена.';
        case 'IDEMPOTENCY_CONFLICT': return 'Эта команда уже использована с другими данными. Повторите действие.';
        default: return 'Действие сейчас невозможно. Мы обновили данные.';
    }
}

function limitMessage(problem: GenerationProblem): string {
    if (problem.code === 'SPEC_NOT_SUPPORTED') return 'Такой запрос пока не поддерживается.';
    switch (problem.limit) {
        case 'ACTIVE_SESSIONS': return 'Уже идут три мастерские. Завершите одну из них, чтобы начать новую.';
        case 'EDITING_DRAFTS': return 'Накопилось слишком много черновиков. Удалите ненужные и повторите.';
        case 'ARTIFACTS_PER_SESSION':
        case 'SOURCES': return 'Слишком много материалов или заметок для одной мастерской. Разделите запрос.';
        default: return 'Достигнут предел. Сократите запрос и повторите.';
    }
}

const SKIP_REASONS: Readonly<Record<NoteSkipReason, string>> = {
    CHANGED: 'заметка изменилась', ALREADY_ARCHIVED: 'уже в архиве', DELETED: 'заметка удалена'
};

/**
 * What «Архивировать использованные заметки» did: «Архивировано: 3, пропущено: 1 — заметка изменилась». A note that changed after
 * the pin is skipped, never archived silently; with several reasons each is counted.
 */
export function describeNoteArchive(result: Pick<NoteArchiveResult, 'archived' | 'skipped'>): string {
    const archived = result.archived.length;
    const skipped = result.skipped.length;
    if (archived === 0 && skipped === 0) return 'Архивировать нечего.';
    if (skipped === 0) return `Архивировано: ${archived}`;
    const counts = new Map<NoteSkipReason, number>();
    for (const entry of result.skipped) counts.set(entry.reason, (counts.get(entry.reason) ?? 0) + 1);
    const reasons = counts.size === 1 ? SKIP_REASONS[[...counts.keys()][0]!]
        : [...counts].map(([reason, count]) => `${SKIP_REASONS[reason]}: ${count}`).join(', ');
    return `Архивировано: ${archived}, пропущено: ${skipped} — ${reasons}`;
}
