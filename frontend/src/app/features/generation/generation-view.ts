import { SegmentedOption } from '../../shared/segmented-choice.component';
import { Capability } from '../authoring/capabilities-api.service';
import { GenerationProblem } from './generation-problem';
import {
    ArtifactErrorCode, ArtifactSummary, ArtifactTurn, BlockingBucket, EditAction, EditPreset, Effort, GenerationEstimate, NoteArchiveResult, NoteSkipReason,
    NotesMode, SessionKind, SessionSummary, SlotKind, SlotState, SpeechVoice
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

/** «3 октября, 11:53»: when a Workshop was started. Russian whatever the app locale (Angular's `date` pipe has only `en-US` here). */
export function formatWorkshopStart(instant: string): string {
    const time = Date.parse(instant);
    return Number.isFinite(time)
        ? new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' }).format(new Date(time)) : '';
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

/** Why one exercise failed, in words: the same codes as {@link failureReason}, said about an exercise and its material. */
export function exerciseFailureReason(code: ArtifactErrorCode | null): string {
    switch (code) {
        case 'INVALID_OUTPUT': return 'Мнема не смогла собрать корректное упражнение: даже после повторной попытки ответ не прошёл проверку.';
        case 'REFUSAL': return 'Мнема отказалась писать упражнения по этому материалу.';
        case 'SOURCE_UNAVAILABLE': return 'Материал, по которому писалось упражнение, изменился или удалён.';
        case 'ESTIMATE_EXCEEDED': return 'Упражнение вышло больше, чем рассчитывалось.';
        default: return failureReason(code);
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
export function problemMessage(problem: GenerationProblem, kind: SessionKind = 'MATERIALS'): string {
    if (problem.uncertain) {
        return 'Не удалось подтвердить действие: связь прервалась или сервер не ответил. Повторите — будет отправлена та же команда.';
    }
    switch (problem.status) {
        case 400: return 'Запрос не принят. Проверьте текст и настройки.';
        case 404: return 'Мастерская или материал больше недоступны.';
        case 412: return 'Материал или колода изменились. Мы обновили данные: проверьте и повторите.';
        case 428: return 'Не удалось подтвердить версию. Обновите страницу.';
        case 409: return conflictMessage(problem, kind);
        case 422: return limitMessage(problem);
        default: return 'Не удалось выполнить действие. Попробуйте ещё раз.';
    }
}

function conflictMessage(problem: GenerationProblem, kind: SessionKind): string {
    switch (problem.code) {
        case 'GENERATION_STATE_CONFLICT':
            switch (problem.reason) {
                case 'MEDIA_NOT_READY': return 'Медиа ещё не готовы. Подождите или правьте материал сами.';
                case 'SOURCE_STALE': return kind === 'EXERCISES'
                    ? 'Материал изменился, пока писалось упражнение. Мы обновили список: пересоздайте такие упражнения или отклоните их.'
                    : kind === 'REVISE_ITEM' ? 'Материал изменился с тех пор, как Мнема начала его править. В колоде остался прежний текст: начните правку заново.'
                    : kind === 'REVISE_EXERCISE' ? 'Упражнение изменилось с тех пор, как Мнема начала его править. В колоде осталась прежняя версия: начните правку заново.'
                    : 'Заметка изменилась, пока писался материал. Попробуйте снова или правьте сам материал.';
                case 'NOT_RETRYABLE': return kind === 'REVISE_ITEM' || kind === 'REVISE_EXERCISE'
                    ? 'Эту правку нельзя повторить: начните новую.' : 'Мнема отказалась писать этот материал: повтор не поможет.';
                default: return 'Это действие уже недоступно: состояние изменилось. Мы обновили данные.';
            }
        case 'USAGE_LIMIT_REACHED': return 'Не хватает лимита ИИ. Подробности — в профиле, в блоке «ИИ-бюджет».';
        case 'CAPABILITY_UNAVAILABLE': return 'ИИ сейчас недоступен. Попробуйте позже или напишите материал сами.';
        case 'SOURCE_UNAVAILABLE': return kind === 'EXERCISES'
            ? 'Материал, по которому писались упражнения, изменился или удалён. Выберите материалы заново.'
            : kind === 'REVISE_ITEM' ? 'Материал уже изменился. Обновите страницу и попросите Мнему ещё раз.'
            : kind === 'REVISE_EXERCISE' ? 'Упражнение или его материал уже изменились. Обновите страницу и попросите Мнему ещё раз.'
            : 'Заметка, из которой писался материал, изменилась или удалена.';
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

// --- Selection edits (AI-11, #293) ---

/** The four quick requests of «Попросить Мнему…», in the order they are offered. */
export const EDIT_PRESET_OPTIONS: readonly { readonly value: EditPreset; readonly label: string }[] = [
    { value: 'SIMPLER', label: 'Проще' }, { value: 'SHORTER', label: 'Короче' },
    { value: 'EXAMPLE', label: 'Пример' }, { value: 'LONGER', label: 'Подробнее' }
];

const PRESET_LABELS: Readonly<Record<EditPreset, string>> = { SIMPLER: 'Проще', SHORTER: 'Короче', EXAMPLE: 'Пример', LONGER: 'Подробнее' };

/** What a turn asked for, in a few words: «Проще», «Проще: сделай ближе к разговорной речи», the instruction itself or «Убрано медиа». */
export function describeTurnAsk(turn: Pick<ArtifactTurn, 'action' | 'preset' | 'instruction'> & { readonly voice?: SpeechVoice | null }): string {
    switch (turn.action) {
        case 'REMOVE_MEDIA': return 'Убрано медиа';
        case 'IMAGE_SEARCH': return 'Поиск похожего изображения';
        case 'IMAGE_GENERATE': return 'Создание изображения';
        case 'AUDIO_REGENERATE': return turn.voice === undefined || turn.voice === null ? 'Озвучка заново' : `Озвучка заново: ${voiceName(turn.voice)} голос`;
        default: {
            const preset = turn.preset === null ? null : PRESET_LABELS[turn.preset];
            const instruction = turn.instruction;
            if (preset !== null && instruction !== null) return `${preset}: ${instruction}`;
            return preset ?? instruction ?? 'Переписано заново';
        }
    }
}

/** How a turn stands, for the history list. */
export function describeTurnStatus(turn: Pick<ArtifactTurn, 'status' | 'action'>): string {
    switch (turn.status) {
        case 'QUEUED': return 'ждёт очереди';
        case 'RUNNING': return 'пишется';
        case 'APPLIED': return turn.action === 'REMOVE_MEDIA' ? 'сделано' : 'применено';
        case 'FAILED': return 'не удалось';
        case 'CANCELLED': return 'остановлено';
    }
}

/** Why a rewrite failed, in words; the text on the screen did not change and the limit was not charged. */
export function turnFailureReason(code: ArtifactErrorCode | null): string {
    switch (code) {
        case 'INVALID_OUTPUT': return 'Мнема не смогла собрать корректный текст.';
        case 'REFUSAL': return 'Мнема отказалась переписывать этот фрагмент.';
        case 'SOURCE_UNAVAILABLE': return 'Заметка, из которой писался материал, изменилась или удалена.';
        case 'PROVIDER_UNAVAILABLE': return 'Сервис ИИ временно недоступен.';
        case 'ESTIMATE_EXCEEDED': return 'Результат вышел больше, чем рассчитывалось.';
        case 'DEADLINE_EXCEEDED': return 'Мнема не успела вовремя.';
        default: return 'Что-то пошло не так.';
    }
}

/** «≈ 0,3 % лимита»: the cost of one edit against the whole allowance (a finer figure than the integer percent of the estimate). */
export function describeEditCost(estimate: GenerationEstimate, allowance: number | null): string {
    if (allowance !== null && allowance > 0) {
        const percent = estimate.credits.p95 / allowance * 100;
        if (percent < 0.1) return `менее 0,1${NBSP}% лимита`;
        const text = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: percent < 10 ? 1 : 0 }).format(percent);
        return `≈${NBSP}${text}${NBSP}% лимита`;
    }
    return describeEstimate(estimate);
}

/** Why an edit does not fit the budget, in words: the same facts as the composer's explanation, for one small edit. */
export function describeEditLimit(bucket: BlockingBucket | undefined): string {
    if (bucket === undefined) return 'Не хватит лимита ИИ на эту правку. Подробности — в профиле, в блоке «ИИ-бюджет».';
    if (!bucket.offered) return 'Правки Мнемы недоступны на вашем тарифе. Подробности — в профиле, в блоке «ИИ-бюджет».';
    const date = formatDay(bucket.renewsAt);
    const first = bucket.window === 'DAY' ? 'На сегодня лимит ИИ исчерпан.' : 'Не хватит лимита ИИ на эту правку.';
    return date !== null && bucket.fitsAfterRenewal ? `${first} Лимит обновится ${date}: тогда правки снова будут доступны.`
        : `${first} Подробности — в профиле, в блоке «ИИ-бюджет».`;
}

const EDIT_REFUSALS: Readonly<Record<string, string>> = {
    TARGET_NOT_CONTIGUOUS: 'Выделение изменилось: выделите нужные абзацы заново.',
    TARGET_UNSUPPORTED_BLOCK: 'В выделении есть блок, который Мнема пока не умеет переписывать (например, видео, формула или заголовок глубокого уровня). Выделите только текст вокруг него.',
    TARGET_PERSONAL_DATA: 'В выделении есть e-mail или телефон — Мнема не переписывает такие фрагменты.',
    TARGET_MEDIA_ONLY: 'В выделении только медиа. Для изображения или аудио используйте действия под ним.',
    TARGET_NO_AUDIO: 'В этом упражнении нет аудио, голос менять не у чего.'
};

const CAPABILITY_WORDS: Readonly<Record<string, string>> = {
    imageSearch: 'Поиск изображений пока недоступен.', imageGeneration: 'Создание изображений пока недоступно.',
    textToSpeech: 'Озвучивание пока недоступно.', aiGeneration: 'ИИ сейчас недоступен. Попробуйте позже или правьте текст сами.'
};

/**
 * What the window of «Попросить Мнему…» says about a refused edit. The explanation of `EDIT_IN_PROGRESS` is the one the issue asks
 * for; the 400 `reason` names why a fragment cannot be rewritten; every other refusal falls back to the common words.
 */
export function editProblemMessage(problem: GenerationProblem): string {
    if (!problem.uncertain) {
        if (problem.status === 400) {
            return (problem.reason !== null ? EDIT_REFUSALS[problem.reason] : undefined) ?? 'Этот фрагмент нельзя переписать с помощью Мнемы.';
        }
        if (problem.status === 412) return 'Материал обновился, пока вы выбирали фрагмент. Выделите его заново.';
        if (problem.status === 409) {
            switch (problem.code) {
                case 'EDIT_IN_PROGRESS': return 'Мнема ещё переписывает предыдущий фрагмент — дождитесь окончания.';
                case 'USAGE_LIMIT_REACHED': return 'Не хватает лимита ИИ на эту правку. Подробности — в профиле, в блоке «ИИ-бюджет».';
                case 'CAPABILITY_UNAVAILABLE':
                    return CAPABILITY_WORDS[problem.capability ?? 'aiGeneration'] ?? CAPABILITY_WORDS['aiGeneration']!;
                case 'GENERATION_STATE_CONFLICT':
                    return problem.reason === 'ILLEGAL_STATE'
                        ? 'Сейчас этот материал нельзя править: его состояние изменилось или правки для него ещё не поддерживаются.'
                        : problemMessage(problem);
                default: break;
            }
        }
        if (problem.status === 422) {
            switch (problem.limit) {
                case 'EDIT_TARGET_SIZE': return 'Фрагмент слишком большой для одной правки. Выделите меньше — несколько абзацев.';
                case 'TURNS_PER_ARTIFACT': return 'Для этого материала исчерпан предел правок (50). Одобрите его или правьте сами.';
                case 'REVISIONS_PER_ARTIFACT': return 'Для этого материала исчерпан предел версий. Одобрите его или правьте сами.';
                default: break;
            }
        }
    }
    return problemMessage(problem);
}

/** «Мнема переписала фрагмент.» and the like: the sentence the summary live region adds when an edit ends. */
export function editOutcomeNote(status: ArtifactTurn['status'], action: EditAction, exercise = false): string {
    switch (status) {
        case 'APPLIED':
            if (action === 'REMOVE_MEDIA') return 'Медиа убрано.';
            if (action === 'AUDIO_REGENERATE') return 'Голос записан.';
            return exercise ? 'Мнема переписала упражнение.' : 'Мнема переписала фрагмент.';
        case 'FAILED': return action === 'AUDIO_REGENERATE' ? 'Не удалось сменить голос.'
            : exercise ? 'Не удалось переписать упражнение: оно не изменилось.' : 'Не удалось переписать фрагмент: текст не изменился.';
        case 'CANCELLED': return exercise ? 'Правка остановлена: упражнение не изменилось.' : 'Правка остановлена: текст не изменился.';
        default: return '';
    }
}

/** «Вернули прежнюю версию.» for the live region after a revert. */
export const REVERTED_NOTE = 'Вернули выбранную версию.';

const MEDIA_ACTION_WHAT: Readonly<Record<'search' | 'generate' | 'speech', string>> = {
    search: 'Подбор изображений', generate: 'Создание изображений', speech: 'Озвучивание'
};

/** Why a media action is not offered yet, for the toggletip next to its disabled button. The capability is the server's word. */
export function mediaActionReason(action: 'search' | 'generate' | 'speech', capability: Capability): string {
    const what = MEDIA_ACTION_WHAT[action];
    if (!capability.available) {
        return capability.reason === 'TEMPORARILY_UNAVAILABLE'
            ? `${what} сейчас временно недоступно. Попробуйте позже.`
            : `${what} появится позже: сервис ещё не подключён. Пока можно убрать медиа или заменить его в редакторе («Править самому»).`;
    }
    return `${what} пока недоступно. Можно убрать медиа или заменить его в редакторе («Править самому»).`;
}


// --- «Попросить Мнему…» and the revision of what exists (AI-16, #294) ---

/** «мужской» / «женский»: the voice in the genitive-free form the chips use («Голос: мужской»). */
export function voiceName(voice: SpeechVoice): string {
    return voice === 'male' ? 'мужской' : 'женский';
}

/** The chip of a voice change, in the intent and in the result of a revision. */
export function voiceChipText(voice: SpeechVoice): string {
    return `Голос: ${voiceName(voice)}`;
}

/** What the result of a voice change says when the audio is the one the exercise had: only the Stub speech executor runs until real synthesis (AI-09). */
export const STUB_VOICE_NOTE = 'Озвучка обновится, когда подключим синтез речи.';

/** The wait of a `429` in words: «5 секунд», «2 минуты». */
export function waitText(seconds: number | null): string {
    if (seconds === null || seconds <= 0) return 'немного';
    if (seconds < 60) return `${seconds}${NBSP}${plural(seconds, 'секунду', 'секунды', 'секунд')}`;
    const minutes = Math.ceil(seconds / 60);
    return `${minutes}${NBSP}${plural(minutes, 'минуту', 'минуты', 'минут')}`;
}

/** What the composer of «Попросить Мнему…» says when the free intent call fails. Nothing was reserved or debited by it. */
export function intentProblemMessage(problem: GenerationProblem): string {
    // An answer that was received but does not read (or is not about this material or exercise) is not a network failure.
    if (problem.status === -1) return 'Мнема ответила так, что мы не смогли это разобрать. Попробуйте ещё раз или перефразируйте запрос: этот шаг бесплатный.';
    if (problem.uncertain) return 'Не удалось связаться с Мнемой. Попробуйте ещё раз: этот шаг бесплатный и ничего не списал.';
    switch (problem.status) {
        case 429: return `Вы часто просите Мнему. Подождите ${waitText(problem.retryAfter)} и попробуйте снова: этот шаг бесплатный, но у него есть почасовой предел.`;
        case 409: return problem.code === 'CAPABILITY_UNAVAILABLE'
            ? 'Мнема сейчас недоступна. Попробуйте позже или сделайте это сами.' : 'Сейчас это невозможно. Обновите страницу.';
        case 404: return 'Материал или упражнение больше недоступны. Обновите страницу.';
        case 400: return 'Не удалось разобрать запрос. Перефразируйте его короче.';
        default: return 'Не удалось разобрать запрос. Попробуйте ещё раз.';
    }
}

const REVISE_REFUSALS: Readonly<Record<string, string>> = {
    TARGET_UNSUPPORTED_BLOCK: 'В этом тексте есть блок, который Мнема пока не умеет переписывать (например, изображение, видео или формула). Поправьте его сами.',
    TARGET_PERSONAL_DATA: 'В тексте есть e-mail, телефон или номер карты — Мнема не переписывает такое. Поправьте его сами.',
    TARGET_MEDIA_ONLY: 'В материале нет текста, который можно переписать: только медиа.',
    TARGET_NO_AUDIO: 'В этом упражнении нет аудио, голос менять не у чего.'
};

/** Why the session of a revision could not start, in words. Nothing was reserved: a refusal comes before the usage. */
export function reviseStartMessage(problem: GenerationProblem, kind: 'REVISE_ITEM' | 'REVISE_EXERCISE'): string {
    if (!problem.uncertain) {
        if (problem.status === 400 && problem.reason !== null && REVISE_REFUSALS[problem.reason] !== undefined) return REVISE_REFUSALS[problem.reason]!;
        if (problem.status === 422 && problem.limit === 'EDIT_TARGET_SIZE') {
            return 'Материал слишком длинный, чтобы Мнема переписала его целиком за один раз. Поправьте его сами.';
        }
        if (problem.status === 409 && problem.code === 'CAPABILITY_UNAVAILABLE' && problem.capability === 'textToSpeech') {
            return 'Озвучивание пока недоступно. Остальное можно попросить отдельно.';
        }
    }
    return problemMessage(problem, kind);
}

/** The Workshop heading of a session of this kind. */
export function workshopHeading(kind: SessionKind): string {
    switch (kind) {
        case 'EXERCISES': return 'Мастерская упражнений';
        case 'REVISE_ITEM': return 'Правка материала';
        case 'REVISE_EXERCISE': return 'Правка упражнения';
        default: return 'Мастерская';
    }
}

/** The one sentence the live region of a revision announces: what Мнема is doing, or what the owner can do now. */
export function reviseSummary(artifact: ArtifactSummary | null, kind: SessionKind): string {
    if (artifact === null) return '';
    const what = kind === 'REVISE_EXERCISE' ? 'упражнение' : 'материал';
    switch (artifact.state) {
        case 'QUEUED':
        case 'GENERATING':
        case 'REVISING': return `Мнема правит ${what}…`;
        case 'PROPOSED': return `Правка готова: оставьте её, верните прежний ${kind === 'REVISE_EXERCISE' ? 'вид' : 'текст'} или попросите ещё раз`;
        case 'PUBLISHED': return `Новая версия сохранена`;
        case 'REJECTED': return 'Правка отклонена';
        case 'STALE': return `${kind === 'REVISE_EXERCISE' ? 'Упражнение' : 'Материал'} изменился: правку нельзя сохранить`;
        case 'FAILED': return 'Правка не удалась';
        case 'HANDED_OFF': return 'Правка передана в редактор';
    }
}
