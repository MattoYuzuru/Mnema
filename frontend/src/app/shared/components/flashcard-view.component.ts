import { Component, ElementRef, HostListener, Input, OnChanges, OnDestroy, Renderer2, SimpleChanges } from '@angular/core';
import { NgFor, NgIf, NgClass } from '@angular/common';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { CardContentValue } from '../../core/models/user-card.models';
import { MediaApiService } from '../../core/services/media-api.service';
import { scopeAnkiCss } from '../utils/anki-css.util';
import { markdownToHtml } from '../utils/markdown.util';

interface RenderedField {
    field: FieldTemplateDTO;
    value: string | null;
    rawValue: CardContentValue;
}

interface AnkiPayload {
    front: string;
    back: string;
    css: string;
    renderedFieldNames: Set<string>;
    mediaIds: Set<string>;
}

@Component({
    selector: 'app-flashcard-view',
    standalone: true,
    imports: [NgFor, NgIf, NgClass],
    template: `
    <div class="flashcard-view">
      <ng-container *ngIf="ankiMode; else fieldView">
        <div class="anki-card card card1" [ngClass]="ankiScopeClass" (click)="handleAnkiClick($event)">
          <div class="anki-html" [innerHTML]="side === 'front' ? ankiFrontHtml : ankiBackHtml"></div>
        </div>
      </ng-container>

      <ng-template #fieldView>
        <div *ngIf="side === 'front'" class="card-side front">
          <div *ngFor="let rf of frontFields" class="field-block">
            <div *ngIf="!hideLabels" class="field-label">{{ rf.field.label }}</div>
            <div class="field-value" [ngClass]="'field-type-' + rf.field.fieldType">
              <ng-container *ngIf="rf.field.fieldType === 'image' && rf.value">
                <img [src]="rf.value" [alt]="rf.field.label" class="field-image" />
              </ng-container>
              <ng-container *ngIf="rf.field.fieldType === 'audio' && rf.value">
                <audio controls [src]="rf.value"></audio>
              </ng-container>
              <ng-container *ngIf="rf.field.fieldType === 'video' && rf.value">
                <video controls [src]="rf.value" class="field-video"></video>
              </ng-container>
              <ng-container *ngIf="rf.field.fieldType !== 'image' && rf.field.fieldType !== 'audio' && rf.field.fieldType !== 'video'">
                <div [innerHTML]="formatValue(rf.value, rf.field.fieldType)"></div>
              </ng-container>
            </div>
          </div>
        </div>

        <div *ngIf="side === 'back'" class="card-side back">
          <div *ngFor="let rf of backFields" class="field-block">
            <div *ngIf="!hideLabels" class="field-label">{{ rf.field.label }}</div>
            <div class="field-value" [ngClass]="'field-type-' + rf.field.fieldType">
              <ng-container *ngIf="rf.field.fieldType === 'image' && rf.value">
                <img [src]="rf.value" [alt]="rf.field.label" class="field-image" />
              </ng-container>
              <ng-container *ngIf="rf.field.fieldType === 'audio' && rf.value">
                <audio controls [src]="rf.value"></audio>
              </ng-container>
              <ng-container *ngIf="rf.field.fieldType === 'video' && rf.value">
                <video controls [src]="rf.value" class="field-video"></video>
              </ng-container>
              <ng-container *ngIf="rf.field.fieldType !== 'image' && rf.field.fieldType !== 'audio' && rf.field.fieldType !== 'video'">
                <div [innerHTML]="formatValue(rf.value, rf.field.fieldType)"></div>
              </ng-container>
            </div>
          </div>
        </div>
      </ng-template>
    </div>
  `,
    styles: [
        `
      :host {
        display: block;
        width: 100%;
      }

      .flashcard-view {
        width: 100%;
      }

      .anki-card {
        width: 100%;
      }

      .anki-html {
        width: 100%;
      }

      .card-side {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .field-block {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xs);
      }

      .field-label {
        font-size: 0.85rem;
        font-weight: 600;
        color: var(--color-text-secondary);
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }

      .field-value {
        font-size: 1rem;
        color: var(--color-text-primary);
        line-height: 1.5;
      }

      .field-type-rich_text .field-value {
        white-space: pre-wrap;
      }

      .field-image {
        max-width: 100%;
        height: auto;
        border-radius: var(--border-radius-md);
      }

      .field-video {
        max-width: 100%;
        height: auto;
        border-radius: var(--border-radius-md);
      }

      audio {
        width: 100%;
      }

      .anki-card img,
      .anki-card video {
        max-width: 100%;
        height: auto;
      }

      .anki-card audio {
        width: 100%;
      }
    `
    ]
})
export class FlashcardViewComponent implements OnChanges, OnDestroy {
    private static nextAnkiScopeId = 0;

    @Input() template!: CardTemplateDTO;
    @Input() content: Record<string, unknown> = {};
    @Input() side: 'front' | 'back' = 'front';
    @Input() hideLabels = false;
    @Input() autoPlayAudioSequence = false;
    @Input() autoPlaySequenceToken: string | null = null;

    frontFields: RenderedField[] = [];
    backFields: RenderedField[] = [];
    ankiMode = false;
    ankiFrontHtml = '';
    ankiBackHtml = '';
    ankiCss = '';
    readonly ankiScopeClass = `mn-anki-scope-${++FlashcardViewComponent.nextAnkiScopeId}`;
    private resolvedUrls: Record<string, string> = {};
    private ankiStyleElement: HTMLStyleElement | null = null;
    private autoPlayDelayHandle: ReturnType<typeof setTimeout> | null = null;
    private autoPlayRunId = 0;
    private lastAutoPlayToken: string | null = null;

    constructor(
        private mediaApi: MediaApiService,
        private host: ElementRef<HTMLElement>,
        private renderer: Renderer2
    ) {}

    ngOnChanges(changes: SimpleChanges): void {
        if (changes['template'] || changes['content'] || changes['side'] || changes['autoPlayAudioSequence'] || changes['autoPlaySequenceToken']) {
            void this.refreshView();
        }
    }

    ngOnDestroy(): void {
        this.cancelAutoPlaySequence();
        this.removeAnkiStyle();
    }

    @HostListener('keydown', ['$event'])
    onMediaKeyDown(event: KeyboardEvent): void {
        if (this.isSpaceKey(event) && this.eventTargetsMedia(event)) {
            event.preventDefault();
        }
    }

    private async refreshView(): Promise<void> {
        if (this.isAnkiTemplate()) {
            await this.buildAnkiView();
            this.scheduleAutoPlayIfNeeded();
            return;
        }
        this.ankiMode = false;
        this.removeAnkiStyle();
        await this.buildFields();
        this.scheduleAutoPlayIfNeeded();
    }

    private async buildFields(): Promise<void> {
        if (!this.template || !this.template.fields) {
            this.frontFields = [];
            this.backFields = [];
            return;
        }

        const mediaIdsToResolve: string[] = [];
        const allContentValues = Object.values(this.content);

        for (const value of allContentValues) {
            if (value && typeof value === 'object' && 'mediaId' in value && !('url' in value && value.url)) {
                const mediaId = (value as any).mediaId;
                if (mediaId && !mediaIdsToResolve.includes(mediaId)) {
                    mediaIdsToResolve.push(mediaId);
                }
            }
        }

        if (mediaIdsToResolve.length > 0) {
            try {
                const resolved = await this.mediaApi.resolve(mediaIdsToResolve).toPromise();
                this.resolvedUrls = this.mediaApi.toUrlMap(resolved || []);
            } catch (err) {
                console.error('Failed to resolve media URLs:', err);
            }
        }

        const fieldsMap = new Map<string, FieldTemplateDTO>();
        this.template.fields.forEach(f => fieldsMap.set(f.name, f));

        this.frontFields = (this.template.layout?.front || [])
            .map(name => fieldsMap.get(name))
            .filter((f): f is FieldTemplateDTO => f !== undefined)
            .sort((a, b) => a.orderIndex - b.orderIndex)
            .map(field => {
                const rawValue = this.content[field.name] as CardContentValue;
                return {
                    field,
                    value: this.extractStringValue(rawValue),
                    rawValue
                };
            });

            this.backFields = (this.template.layout?.back || [])
            .map(name => fieldsMap.get(name))
            .filter((f): f is FieldTemplateDTO => f !== undefined)
            .sort((a, b) => a.orderIndex - b.orderIndex)
            .map(field => {
                const rawValue = this.content[field.name] as CardContentValue;
                return {
                    field,
                    value: this.extractStringValue(rawValue),
                    rawValue
                };
            });
    }

    private isAnkiTemplate(): boolean {
        const anki = this.getAnkiPayload();
        return !!anki && (!!anki.front || !!anki.back || !!anki.css);
    }

    private getAnkiPayload(): AnkiPayload | null {
        const raw = (this.content as any)?._anki;
        if (raw && typeof raw === 'object') {
            const stored = {
                front: typeof raw.front === 'string' ? raw.front : '',
                back: typeof raw.back === 'string' ? raw.back : '',
                css: typeof raw.css === 'string' ? raw.css : '',
                renderedFieldNames: this.collectStoredAnkiRenderedFieldNames(),
                mediaIds: new Set<string>()
            };
            stored.mediaIds = new Set(this.collectAnkiMediaIds(stored));
            return stored;
        }
        return this.buildTemplateAnkiPayload();
    }

    private async buildAnkiView(): Promise<void> {
        const anki = this.getAnkiPayload();
        if (!anki) {
            this.ankiMode = false;
            this.ankiFrontHtml = '';
            this.ankiBackHtml = '';
            this.ankiCss = '';
            this.removeAnkiStyle();
            return;
        }
        this.ankiMode = true;
        const mediaIds = this.uniqueStrings([
            ...this.collectAnkiMediaIds(anki),
            ...this.collectContentMediaIds()
        ]);
        if (mediaIds.length > 0) {
            try {
                const resolved = await this.mediaApi.resolve(mediaIds).toPromise();
                this.resolvedUrls = this.mediaApi.toUrlMap(resolved || []);
            } catch (err) {
                console.error('Failed to resolve Anki media URLs:', err);
            }
        }
        this.ankiFrontHtml = this.replaceAnkiMediaTokens(anki.front) + this.renderAnkiSupplementalFields('front', anki);
        this.ankiBackHtml = this.replaceAnkiMediaTokens(anki.back) + this.renderAnkiSupplementalFields('back', anki);
        this.ankiCss = this.replaceAnkiMediaTokens(anki.css);
        this.updateAnkiStyle();
        this.frontFields = [];
        this.backFields = [];
    }

    private collectAnkiMediaIds(anki: AnkiPayload): string[] {
        const ids = new Set<string>();
        const pattern = /mnema-media:\/\/([0-9a-fA-F-]{36})/g;
        const sources = [anki.front, anki.back, anki.css];
        for (const source of sources) {
            if (!source) continue;
            let match: RegExpExecArray | null;
            while ((match = pattern.exec(source)) !== null) {
                ids.add(match[1]);
            }
        }
        return Array.from(ids);
    }

    private collectContentMediaIds(): string[] {
        const ids = new Set<string>();
        for (const value of Object.values(this.content || {})) {
            if (value && typeof value === 'object' && 'mediaId' in value) {
                const mediaId = (value as any).mediaId;
                if (typeof mediaId === 'string' && mediaId.trim()) {
                    ids.add(mediaId.trim());
                }
            }
        }
        return Array.from(ids);
    }

    private replaceAnkiMediaTokens(value: string): string {
        if (!value) {
            return '';
        }
        return value.replace(/mnema-media:\/\/[0-9a-fA-F-]{36}/g, (match: string) => {
            const id = match.substring('mnema-media://'.length);
            return this.resolvedUrls[id] || match;
        });
    }

    private buildTemplateAnkiPayload(): AnkiPayload | null {
        const layoutAnki = this.template?.layout?.anki;
        if (this.template?.layout?.renderMode !== 'anki' || !layoutAnki) {
            return null;
        }
        const renderedFieldNames = this.collectTemplateFieldNames();
        const front = this.renderAnkiTemplate(layoutAnki.frontTemplate || '', '');
        const back = this.renderAnkiTemplate(layoutAnki.backTemplate || '', front);
        const payload = {
            front,
            back,
            css: layoutAnki.css || '',
            renderedFieldNames,
            mediaIds: new Set<string>()
        };
        payload.mediaIds = new Set(this.collectAnkiMediaIds(payload));
        return payload;
    }

    private renderAnkiTemplate(template: string, frontHtml: string): string {
        if (!template) {
            return '';
        }
        return template.replace(/\{\{([^}]+)}}/g, (_match: string, token: string) => this.renderAnkiToken(token, frontHtml));
    }

    private renderAnkiToken(token: string, frontHtml: string): string {
        const cleaned = this.cleanAnkiToken(token);
        if (!cleaned) {
            return '';
        }
        if (cleaned.toLowerCase() === 'frontside') {
            return frontHtml || '';
        }
        const field = this.findField(cleaned);
        if (!field) {
            return '';
        }
        return this.renderAnkiFieldValue(field);
    }

    private cleanAnkiToken(token: string): string {
        let cleaned = (token || '').trim();
        while (cleaned.startsWith('#') || cleaned.startsWith('^') || cleaned.startsWith('/')) {
            cleaned = cleaned.substring(1).trim();
        }
        const colon = cleaned.lastIndexOf(':');
        if (colon >= 0 && colon < cleaned.length - 1) {
            cleaned = cleaned.substring(colon + 1).trim();
        }
        return cleaned;
    }

    private collectTemplateFieldNames(): Set<string> {
        const names = new Set<string>();
        const layoutAnki = this.template?.layout?.anki;
        for (const source of [layoutAnki?.frontTemplate, layoutAnki?.backTemplate]) {
            if (!source) continue;
            const matcher = /\{\{([^}]+)}}/g;
            let match: RegExpExecArray | null;
            while ((match = matcher.exec(source)) !== null) {
                const name = this.cleanAnkiToken(match[1]);
                if (name && name.toLowerCase() !== 'frontside') {
                    names.add(this.normalizeFieldName(name));
                }
            }
        }
        return names;
    }

    private collectNonMediaFieldNames(): Set<string> {
        const names = new Set<string>();
        for (const field of this.template?.fields || []) {
            if (!this.isMediaField(field)) {
                names.add(this.normalizeFieldName(field.name));
            }
        }
        return names;
    }

    private collectStoredAnkiRenderedFieldNames(): Set<string> {
        const names = this.collectTemplateFieldNames();
        if (names.size === 0) {
            return this.collectNonMediaFieldNames();
        }

        for (const field of this.template?.fields || []) {
            if (this.isMediaField(field)) {
                names.delete(this.normalizeFieldName(field.name));
            }
        }
        return names;
    }

    private isMediaField(field: FieldTemplateDTO): boolean {
        return ['image', 'audio', 'video'].includes(field.fieldType);
    }

    private renderAnkiSupplementalFields(side: 'front' | 'back', anki: AnkiPayload): string {
        const fields = this.fieldsForSide(side)
            .filter(field => !anki.renderedFieldNames.has(this.normalizeFieldName(field.name)))
            .filter(field => this.hasRenderableValue(field))
            .filter(field => !this.isMediaAlreadyRendered(field, anki));
        if (fields.length === 0) {
            return '';
        }
        const rendered = fields
            .map(field => {
                const value = this.renderAnkiFieldValue(field);
                if (!value) {
                    return '';
                }
                return `<div class="mn-anki-extra-field mn-anki-extra-field-${field.fieldType}">${value}</div>`;
            })
            .filter(Boolean)
            .join('');
        return rendered ? `<div class="mn-anki-extra-fields">${rendered}</div>` : '';
    }

    private fieldsForSide(side: 'front' | 'back'): FieldTemplateDTO[] {
        const fields = [...(this.template?.fields || [])].sort((a, b) => a.orderIndex - b.orderIndex);
        const fieldsByName = new Map<string, FieldTemplateDTO>();
        fields.forEach(field => fieldsByName.set(field.name, field));
        const layoutNames = side === 'front' ? this.template?.layout?.front || [] : this.template?.layout?.back || [];
        const used = new Set<string>();
        const ordered: FieldTemplateDTO[] = [];
        for (const name of layoutNames) {
            const field = fieldsByName.get(name);
            if (field) {
                ordered.push(field);
                used.add(field.name);
            }
        }
        for (const field of fields) {
            if (!used.has(field.name) && field.isOnFront === (side === 'front')) {
                ordered.push(field);
            }
        }
        return ordered;
    }

    private findField(name: string): FieldTemplateDTO | null {
        const normalized = this.normalizeFieldName(name);
        return (this.template?.fields || []).find(field => this.normalizeFieldName(field.name) === normalized) || null;
    }

    private hasRenderableValue(field: FieldTemplateDTO): boolean {
        const raw = this.content[field.name] as CardContentValue;
        if (raw === null || raw === undefined) {
            return false;
        }
        if (typeof raw === 'string') {
            return raw.trim().length > 0;
        }
        return !!raw.mediaId || !!raw.url;
    }

    private isMediaAlreadyRendered(field: FieldTemplateDTO, anki: AnkiPayload): boolean {
        const raw = this.content[field.name] as CardContentValue;
        return !!raw && typeof raw === 'object' && !!raw.mediaId && anki.mediaIds.has(raw.mediaId);
    }

    private renderAnkiFieldValue(field: FieldTemplateDTO): string {
        const raw = this.content[field.name] as CardContentValue;
        const value = this.extractStringValue(raw);
        if (!value) {
            return '';
        }
        switch (field.fieldType) {
            case 'image':
                return `<img src="${this.escapeHtmlAttribute(value)}" alt="${this.escapeHtmlAttribute(field.label || field.name)}">`;
            case 'audio':
                return `<audio controls src="${this.escapeHtmlAttribute(value)}"></audio>`;
            case 'video':
                return `<video controls src="${this.escapeHtmlAttribute(value)}"></video>`;
            case 'markdown':
                return markdownToHtml(value);
            default:
                return this.escapeHtml(value).replace(/\n/g, '<br>');
        }
    }

    private updateAnkiStyle(): void {
        if (!this.ankiMode || !this.ankiCss) {
            this.removeAnkiStyle();
            return;
        }
        if (!this.ankiStyleElement) {
            this.ankiStyleElement = this.renderer.createElement('style');
            this.renderer.appendChild(this.host.nativeElement, this.ankiStyleElement);
        }
        if (this.ankiStyleElement) {
            this.ankiStyleElement.textContent = this.buildAnkiCss(this.ankiCss);
        }
    }

    private removeAnkiStyle(): void {
        if (!this.ankiStyleElement) {
            return;
        }
        this.renderer.removeChild(this.host.nativeElement, this.ankiStyleElement);
        this.ankiStyleElement = null;
    }

    private scheduleAutoPlayIfNeeded(): void {
        this.cancelAutoPlaySequence();
        if (!this.autoPlayAudioSequence || this.side !== 'front') {
            return;
        }

        const token = this.autoPlaySequenceToken?.trim() || null;
        if (!token || token === this.lastAutoPlayToken) {
            return;
        }

        this.lastAutoPlayToken = token;
        const runId = ++this.autoPlayRunId;
        this.autoPlayDelayHandle = setTimeout(() => {
            this.autoPlayDelayHandle = null;
            void this.playAudioSequence(runId);
        }, 500);
    }

    private cancelAutoPlaySequence(): void {
        if (this.autoPlayDelayHandle) {
            clearTimeout(this.autoPlayDelayHandle);
            this.autoPlayDelayHandle = null;
        }
        this.autoPlayRunId++;
    }

    private async playAudioSequence(runId: number): Promise<void> {
        const audioElements = Array.from(this.host.nativeElement.querySelectorAll('audio'));
        if (audioElements.length === 0) {
            return;
        }

        for (const audio of audioElements) {
            if (runId !== this.autoPlayRunId) {
                return;
            }

            audio.currentTime = 0;
            try {
                await audio.play();
            } catch {
                continue;
            }

            await this.waitForAudioToFinish(audio, runId);
            if (runId !== this.autoPlayRunId) {
                return;
            }
            await this.delayWithCancel(500, runId);
        }
    }

    private waitForAudioToFinish(audio: HTMLAudioElement, runId: number): Promise<void> {
        return new Promise(resolve => {
            const cleanup = (): void => {
                audio.removeEventListener('ended', onEnded);
                audio.removeEventListener('error', onEndLike);
                audio.removeEventListener('abort', onEndLike);
                audio.removeEventListener('pause', onPause);
            };

            const complete = (): void => {
                cleanup();
                resolve();
            };

            const onEnded = (): void => complete();
            const onEndLike = (): void => complete();
            const onPause = (): void => {
                if (!audio.ended) {
                    complete();
                }
            };

            if (runId !== this.autoPlayRunId || audio.ended) {
                complete();
                return;
            }

            audio.addEventListener('ended', onEnded, { once: true });
            audio.addEventListener('error', onEndLike, { once: true });
            audio.addEventListener('abort', onEndLike, { once: true });
            audio.addEventListener('pause', onPause, { once: true });
        });
    }

    private delayWithCancel(delayMs: number, runId: number): Promise<void> {
        return new Promise(resolve => {
            if (runId !== this.autoPlayRunId) {
                resolve();
                return;
            }
            setTimeout(resolve, delayMs);
        });
    }

    private buildAnkiCss(css: string): string {
        if (!css) {
            return '';
        }
        const scopeSelector = `.anki-card.${this.ankiScopeClass}`;
        const fixes: string[] = [
            `${scopeSelector} { width: 100% !important; max-width: 100% !important; overflow-x: clip; }`,
            `${scopeSelector} .anki-html { width: 100% !important; max-width: 100% !important; overflow-wrap: anywhere; word-break: break-word; }`,
            `${scopeSelector} img, ${scopeSelector} video, ${scopeSelector} iframe, ${scopeSelector} object, ${scopeSelector} embed { max-width: 100% !important; height: auto !important; }`,
            `${scopeSelector} audio { max-width: 100% !important; }`,
            `${scopeSelector} table { display: block; width: 100% !important; max-width: 100% !important; overflow-x: auto; }`,
            `${scopeSelector} .mn-anki-extra-fields { margin-top: 1rem; display: grid; gap: 0.75rem; }`,
            `${scopeSelector} .mn-anki-extra-field audio, ${scopeSelector} .mn-anki-extra-field video { width: 100% !important; }`
        ];
        if (css.includes('wrapped-japanese')) {
            fixes.push(`${scopeSelector} .wrapped-japanese { visibility: visible !important; }`);
        }
        if (css.includes('migaku-card')) {
            fixes.push(`${scopeSelector} .migaku-card { width: 100% !important; max-width: 100% !important; margin-left: auto !important; margin-right: auto !important; }`);
            fixes.push(`${scopeSelector} .migaku-card-image img { width: 100% !important; max-width: 100% !important; }`);
            fixes.push(`${scopeSelector} .migaku-card-content { width: 100% !important; max-width: 100% !important; overflow-wrap: anywhere; }`);
        }
        return `${scopeAnkiCss(css, scopeSelector)}\n\n/* Mnema compatibility fixes */\n${fixes.join('\n')}\n`;
    }

    handleAnkiClick(event: Event): void {
        const target = event.target as HTMLElement | null;
        if (!target) {
            return;
        }
        const el = target.closest('[data-anki-audio]') as HTMLElement | null;
        if (!el) {
            return;
        }
        const audioSrc = el.getAttribute('data-anki-audio');
        if (!audioSrc) {
            return;
        }
        const audio = new Audio(audioSrc);
        audio.play().catch(() => {});
    }

    private extractStringValue(value: CardContentValue): string | null {
        if (value === null || value === undefined) {
            return null;
        }
        if (typeof value === 'string') {
            return value;
        }
        if (value.url) {
            return value.url;
        }
        if (value.mediaId && this.resolvedUrls[value.mediaId]) {
            return this.resolvedUrls[value.mediaId];
        }
        return null;
    }

    formatValue(value: string | null, fieldType: string = 'text'): string {
        if (!value) {
            return '<span style="color: var(--color-text-tertiary); font-style: italic;">No content</span>';
        }
        if (fieldType === 'markdown') {
            return markdownToHtml(value);
        }
        return value.replace(/\n/g, '<br>');
    }

    private normalizeFieldName(value: string): string {
        return (value || '').trim().toLowerCase();
    }

    private uniqueStrings(values: string[]): string[] {
        return Array.from(new Set(values.filter(value => !!value && value.trim().length > 0)));
    }

    private escapeHtml(value: string): string {
        return value
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    private escapeHtmlAttribute(value: string): string {
        return this.escapeHtml(value);
    }

    private isSpaceKey(event: KeyboardEvent): boolean {
        return event.key === ' ' || event.key === 'Space' || event.key === 'Spacebar' || event.code === 'Space';
    }

    private eventTargetsMedia(event: KeyboardEvent): boolean {
        return event.composedPath().some(node => node instanceof HTMLAudioElement || node instanceof HTMLVideoElement);
    }
}
