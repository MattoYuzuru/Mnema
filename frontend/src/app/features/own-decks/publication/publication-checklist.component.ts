import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { EMPTY, Subscription, expand } from 'rxjs';

import { MnemaSelectComponent, MnemaSelectOption } from '../../../core/controls/mnema-select.component';
import { ToastService } from '../../../core/notifications/toast.service';
import { TagInputComponent } from '../../../shared/tag-input.component';
import { ItemApiService } from '../../authoring/item-api.service';
import { PublicationApiService } from './publication-api.service';
import { ChecklistDraft, PublicationDraftStore } from './publication-draft.store';
import {
    BlockedMedia,
    CONTENT_LEVELS,
    ChecklistKey,
    ContentLevel,
    PublicationCommand,
    PublicationState,
    Topic,
    newPublicationCommandId,
    publicationFailureOf,
    topicLeaves
} from './publication.models';
import { CHECKLIST_LABEL, CHECKLIST_TEXT, CONTENT_LEVEL_LABEL, PUBLICATION_TEXT, blockedMediaLabel, publicationFailureText } from './publication.text';

type TopicsState =
    | { readonly phase: 'loading' }
    | { readonly phase: 'error' }
    | { readonly phase: 'ready'; readonly topics: readonly Topic[] };

type ItemState = 'done' | 'todo' | 'failed' | 'optional';

interface ChecklistItemView {
    readonly key: ChecklistKey;
    readonly state: ItemState;
}

/** Languages offered for the deck's text; a language the server already stores is added to the list when it is not here. */
const LANGUAGE_CODES = ['ru', 'en', 'es', 'de', 'fr', 'it', 'pt', 'nl', 'sv', 'pl', 'uk', 'cs', 'tr', 'el', 'la', 'he', 'ar', 'hi', 'zh', 'ja', 'ko', 'vi', 'th', 'id'] as const;
const MATERIAL_PAGES = 5;

/** Whether the stored state, as just re-read, satisfies an item that only the stored state can satisfy. */
function stateSatisfies(state: PublicationState, key: ChecklistKey): boolean {
    switch (key) {
        case 'description': return state.checklist.description;
        case 'publicProfile': return state.checklist.publicProfile;
        case 'blockedMedia': return state.checklist.blockedMedia.length === 0;
        default: return false;
    }
}
let nextChecklist = 0;

function languageName(code: string): string {
    let name: string | undefined;
    try {
        name = new Intl.DisplayNames(['ru'], { type: 'language' }).of(code);
    } catch {
        name = undefined;
    }
    const shown = name ?? code;
    return shown.charAt(0).toLocaleUpperCase('ru') + shown.slice(1);
}

/**
 * «Сделать колоду публичной»: an inline panel in the Deck hub. The checklist lists what a public deck needs, each item in
 * words (Готово / Нужно сделать) with the control that fixes it next to it: the description (the deck editor), a topic from
 * the directory (suggestions first), the language (pre-filled by the server's guess), an optional level, up to five tags, the
 * public profile (a link to its consent in the profile) and the stock images that may not be public. The catalog threshold is
 * only information. «Опубликовать и сделать публичной» stays pressable-looking but inert (`aria-disabled`) until every blocking
 * item passes, and says what is missing; the server's 409 marks the items it names.
 */
@Component({
    selector: 'app-publication-checklist',
    imports: [NgTemplateOutlet, RouterLink, MnemaSelectComponent, TagInputComponent],
    templateUrl: './publication-checklist.component.html',
    styleUrl: './publication-checklist.component.css',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class PublicationChecklistComponent {
    readonly deckId = input.required<string>();
    readonly state = input.required<PublicationState>();
    /** The deck became public: the new state. */
    readonly published = output<PublicationState>();
    readonly cancelled = output<void>();
    /** The server said the deck changed or what is missing: the host re-reads the state. */
    readonly refresh = output<void>();
    /** An exact retry was answered with the original acknowledgement: the host re-reads and, if the deck is public, closes the panel. */
    readonly replayed = output<void>();
    /** «Добавить описание»: the host opens the deck editor. */
    readonly editDescription = output<void>();

    protected readonly text = CHECKLIST_TEXT;
    protected readonly uid = `mn-checklist-${nextChecklist++}`;
    protected readonly topicId = signal('');
    protected readonly language = signal('');
    protected readonly level = signal('');
    protected readonly tags = signal<readonly string[]>([]);
    protected readonly topics = signal<TopicsState>({ phase: 'loading' });
    protected readonly submitting = signal(false);
    protected readonly problem = signal<string | null>(null);
    protected readonly serverFailed = signal<readonly ChecklistKey[]>([]);
    protected readonly materialTitles = signal<ReadonlyMap<string, string>>(new Map());
    protected readonly languageSuggested = signal(false);

    protected readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

    protected readonly items = computed<readonly ChecklistItemView[]>(() => {
        const checklist = this.state().checklist;
        const failed = this.serverFailed();
        // The server's verdict wins over the local picture until the item is changed or the re-read state satisfies it.
        const stateOf = (key: ChecklistKey, done: boolean): ItemState => failed.includes(key) ? 'failed' : done ? 'done' : 'todo';
        return [
            { key: 'description', state: stateOf('description', checklist.description) },
            { key: 'topic', state: stateOf('topic', this.topicId() !== '') },
            { key: 'language', state: stateOf('language', this.language() !== '') },
            { key: 'publicProfile', state: stateOf('publicProfile', checklist.publicProfile) },
            { key: 'blockedMedia', state: stateOf('blockedMedia', checklist.blockedMedia.length === 0) }
        ];
    });
    protected readonly missing = computed(() => this.items().filter(item => item.state !== 'done').map(item => item.key));
    protected readonly canSubmit = computed(() => this.missing().length === 0);
    protected readonly missingText = computed(() => CHECKLIST_TEXT.blockedSummary(this.missing().map(key => CHECKLIST_LABEL[key])));
    protected readonly stateOf = (key: ChecklistKey): ItemState => this.items().find(item => item.key === key)!.state;

    protected readonly topicOptions = computed<readonly MnemaSelectOption[]>(() => {
        const topics = this.topics();
        if (topics.phase !== 'ready') return [];
        return topics.topics.flatMap(topic => topic.children.length === 0
            ? [{ value: topic.topicId, label: topic.nameRu }]
            : topic.children.map(child => ({ value: child.topicId, label: child.nameRu, group: topic.nameRu })));
    });
    protected readonly suggestedTopics = computed(() => {
        const topics = this.topics();
        if (topics.phase !== 'ready') return [];
        const leaves = topicLeaves(topics.topics);
        return this.state().suggested.topicIds.flatMap(id => leaves.filter(leaf => leaf.topicId === id));
    });
    protected readonly languageOptions = computed<readonly MnemaSelectOption[]>(() => {
        const codes = new Set<string>(LANGUAGE_CODES);
        const stored = [this.state().metadata.contentLanguage, this.state().suggested.contentLanguage, this.language()];
        for (const code of stored) if (code) codes.add(code);
        return [...codes].map(code => ({ value: code, label: languageName(code) }));
    });
    protected readonly levelOptions: readonly MnemaSelectOption[] = [
        { value: '', label: CHECKLIST_TEXT.levelNone },
        ...CONTENT_LEVELS.map(level => ({ value: level, label: CONTENT_LEVEL_LABEL[level] }))
    ];

    private readonly api = inject(PublicationApiService);
    private readonly itemsApi = inject(ItemApiService);
    private readonly toasts = inject(ToastService);
    private readonly drafts = inject(PublicationDraftStore);
    private readonly destroyRef = inject(DestroyRef);
    private topicsLoad: Subscription | null = null;
    private titlesLoad: Subscription | null = null;
    private titlesFor = '';
    private initialized = false;
    private baseline: ChecklistDraft = { topicId: '', language: '', level: '', tags: [] };
    /** Set once the deck is published: the draft is gone and must not be written back by the last effect run. */
    private finished = false;
    /** The command of an attempt whose outcome is unknown: the same exact body is retried with the same id. */
    private pending: { readonly command: PublicationCommand; readonly rowVersion: string } | null = null;

    constructor() {
        this.loadTopics();
        effect(() => {
            const state = this.state();
            untracked(() => {
                if (!this.initialized) {
                    this.initialized = true;
                    // What the owner already picked in this session wins over the stored state and the server's guess.
                    const draft = this.drafts.get(this.deckId());
                    const guess = state.metadata.contentLanguage === null ? state.suggested.contentLanguage : null;
                    this.baseline = {
                        topicId: state.metadata.topicId ?? '', language: state.metadata.contentLanguage ?? guess ?? '',
                        level: state.metadata.level ?? '', tags: state.metadata.tags
                    };
                    this.topicId.set(draft?.topicId ?? this.baseline.topicId);
                    this.language.set(draft?.language ?? this.baseline.language);
                    this.languageSuggested.set(draft === null && guess !== null);
                    this.level.set(draft?.level ?? this.baseline.level);
                    this.tags.set(draft?.tags ?? this.baseline.tags);
                }
                this.serverFailed.update(failed => failed.filter(key => !stateSatisfies(state, key)));
                this.loadMaterialTitles(state);
            });
        });
        effect(() => {
            const draft = { topicId: this.topicId(), language: this.language(), level: this.level(), tags: this.tags() };
            untracked(() => {
                if (!this.initialized || this.finished) return;
                // Only what differs from the stored state and the server's guess is a draft.
                if (JSON.stringify(draft) === JSON.stringify(this.baseline)) this.drafts.clear(this.deckId());
                else this.drafts.set(this.deckId(), draft);
            });
        });
        // The «Чтобы опубликовать…» alert of a failed attempt is stale once nothing is missing any more.
        effect(() => {
            if (this.canSubmit()) untracked(() => this.problem.set(null));
        });
        afterNextRender(() => this.heading().nativeElement.focus());
        this.destroyRef.onDestroy(() => { this.topicsLoad?.unsubscribe(); this.titlesLoad?.unsubscribe(); });
    }

    protected loadTopics(): void {
        this.topicsLoad?.unsubscribe();
        this.topics.set({ phase: 'loading' });
        this.topicsLoad = this.api.topics().subscribe({
            next: topics => this.topics.set({ phase: 'ready', topics }),
            error: () => this.topics.set({ phase: 'error' })
        });
    }

    protected pickTopic(id: string): void {
        this.topicId.set(id);
        this.serverFailed.update(failed => failed.filter(key => key !== 'topic'));
    }

    protected pickLanguage(code: string): void {
        this.language.set(code);
        this.serverFailed.update(failed => failed.filter(key => key !== 'language'));
    }

    protected blockedTitle(entry: BlockedMedia): string | null {
        return entry.memberKey === null ? null : this.materialTitles().get(entry.memberKey) ?? null;
    }

    protected blockedLabel(entry: BlockedMedia): string {
        return blockedMediaLabel(entry, this.blockedTitle(entry));
    }

    protected blockedLinkLabel(entry: BlockedMedia): string {
        return CHECKLIST_TEXT.openBlocked(entry, this.blockedTitle(entry));
    }

    protected blockedLink(entry: BlockedMedia): readonly string[] {
        return entry.memberKey !== null
            ? ['/decks', this.deckId(), 'materials', entry.memberKey, 'edit']
            : ['/decks', this.deckId(), 'exercises', entry.exerciseId!, 'edit'];
    }

    /** Esc closes the panel only from a control that does not use it: an open select or a text field keeps its own Esc. */
    protected onEscape(event: Event): void {
        const target = event.target;
        if (event.defaultPrevented || this.submitting()) return;
        if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) return;
        this.cancelled.emit();
    }

    protected stateId(key: string): string {
        return `${this.uid}-${key}-state`;
    }

    protected submit(): void {
        if (this.submitting()) return;
        // What is missing is already said by the description of the button (`aria-describedby`); no second message.
        if (!this.canSubmit()) return;
        const state = this.state();
        const command = this.pendingFor(state) ?? this.buildCommand(state);
        this.pending = { command, rowVersion: state.rowVersion };
        this.submitting.set(true);
        this.problem.set(null);
        this.api.save(this.deckId(), state.rowVersion, command).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
            next: result => {
                this.pending = null;
                this.submitting.set(false);
                this.finished = true;
                this.drafts.clear(this.deckId());
                this.toasts.echo(PUBLICATION_TEXT.madePublic);
                if (result.replayed) {
                    // The flag first: the host's re-read may answer at once and must already know the panel is to close.
                    this.replayed.emit();
                    this.refresh.emit();
                } else this.published.emit(result.acknowledgement.publication);
            },
            error: (error: unknown) => this.fail(error)
        });
    }

    private fail(error: unknown): void {
        const failure = publicationFailureOf(error);
        this.submitting.set(false);
        this.problem.set(publicationFailureText(failure));
        // A network error or a server fault leaves the outcome unknown: keep the command for an exact retry. Anything the server
        // answered definitively (a stale version, a missing requirement) is over.
        if (failure !== null && failure.status >= 400 && failure.status < 500) {
            this.pending = null;
            if (failure.status === 409 && failure.code === 'PUBLICATION_REQUIREMENTS') this.serverFailed.set(failure.failed);
            if (failure.status === 412 || failure.status === 409) this.refresh.emit();
        }
    }

    private pendingFor(state: PublicationState): PublicationCommand | null {
        const pending = this.pending;
        if (pending === null || pending.rowVersion !== state.rowVersion) return null;
        const same = JSON.stringify({ ...pending.command, commandId: '' }) === JSON.stringify({ ...this.buildCommand(state), commandId: '' });
        return same ? pending.command : null;
    }

    private buildCommand(state: PublicationState): PublicationCommand {
        return {
            commandId: newPublicationCommandId(),
            visibility: 'PUBLIC',
            metadata: {
                topicId: this.topicId(),
                contentLanguage: this.language(),
                targetLanguage: state.metadata.targetLanguage,
                level: this.level() === '' ? null : this.level() as ContentLevel,
                tags: this.tags()
            },
            requestsEnabled: state.requestsEnabled,
            publish: { expectedHeadRevisionId: state.headRevisionId, releaseNote: null }
        };
    }

    /** Names of the materials that use blocked images: read from the first pages of the material list, never required. */
    private loadMaterialTitles(state: PublicationState): void {
        const wanted = state.checklist.blockedMedia.flatMap(entry => entry.memberKey === null ? [] : [entry.memberKey]);
        const key = wanted.join(',');
        if (key === this.titlesFor) return;
        this.titlesFor = key;
        this.titlesLoad?.unsubscribe();
        if (wanted.length === 0) return;
        let pages = 0;
        const found = new Map<string, string>();
        this.titlesLoad = this.itemsApi.list(this.deckId(), { limit: 100 }).pipe(
            expand(page => {
                page.items.forEach(item => { if (wanted.includes(item.memberKey)) found.set(item.memberKey, item.title); });
                this.materialTitles.set(new Map(found));
                pages += 1;
                return page.nextCursor !== null && pages < MATERIAL_PAGES && found.size < wanted.length
                    ? this.itemsApi.list(this.deckId(), { limit: 100, cursor: page.nextCursor }) : EMPTY;
            })
        ).subscribe({ error: () => undefined });
    }
}
