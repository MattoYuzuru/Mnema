import { Component, ElementRef, HostListener, Input, OnChanges, OnDestroy, Renderer2, SimpleChanges } from '@angular/core';
import { NgFor, NgIf, NgClass } from '@angular/common';
import { CardTemplateDTO, FieldTemplateDTO } from '../../core/models/template.models';
import { CardContentValue } from '../../core/models/user-card.models';
import { MediaApiService } from '../../core/services/media-api.service';
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
}

@Component({
    selector: 'app-flashcard-view',
    standalone: true,
    imports: [NgFor, NgIf, NgClass],
    template: `
    <div class="flashcard-view">
      <ng-container *ngIf="ankiMode; else fieldView">
        <div class="anki-card card1" (click)="handleAnkiClick($event)">
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
        return !!anki && (!!anki.front || !!anki.back);
    }

    private getAnkiPayload(): AnkiPayload | null {
        const raw = (this.content as any)?._anki;
        if (!raw || typeof raw !== 'object') {
            return null;
        }
        return {
            front: typeof raw.front === 'string' ? raw.front : '',
            back: typeof raw.back === 'string' ? raw.back : '',
            css: typeof raw.css === 'string' ? raw.css : ''
        };
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
        const mediaIds = this.collectAnkiMediaIds(anki);
        if (mediaIds.length > 0) {
            try {
                const resolved = await this.mediaApi.resolve(mediaIds).toPromise();
                this.resolvedUrls = this.mediaApi.toUrlMap(resolved || []);
            } catch (err) {
                console.error('Failed to resolve Anki media URLs:', err);
            }
        }
        this.ankiFrontHtml = this.replaceAnkiMediaTokens(anki.front);
        this.ankiBackHtml = this.replaceAnkiMediaTokens(anki.back);
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

    private replaceAnkiMediaTokens(value: string): string {
        if (!value) {
            return '';
        }
        return value.replace(/mnema-media:\/\/[0-9a-fA-F-]{36}/g, (match: string) => {
            const id = match.substring('mnema-media://'.length);
            return this.resolvedUrls[id] || match;
        });
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
        const fixes: string[] = [
            '.anki-card { width: 100% !important; max-width: 100% !important; overflow-x: clip; }',
            '.anki-card .anki-html { width: 100% !important; max-width: 100% !important; overflow-wrap: anywhere; word-break: break-word; }',
            '.anki-card img, .anki-card video, .anki-card iframe, .anki-card object, .anki-card embed { max-width: 100% !important; height: auto !important; }',
            '.anki-card audio { max-width: 100% !important; }',
            '.anki-card table { display: block; width: 100% !important; max-width: 100% !important; overflow-x: auto; }'
        ];
        if (css.includes('wrapped-japanese')) {
            fixes.push('.anki-card .wrapped-japanese { visibility: visible !important; }');
        }
        if (css.includes('migaku-card')) {
            fixes.push('.anki-card .migaku-card { width: 100% !important; max-width: 100% !important; margin-left: auto !important; margin-right: auto !important; }');
            fixes.push('.anki-card .migaku-card-image img { width: 100% !important; max-width: 100% !important; }');
            fixes.push('.anki-card .migaku-card-content { width: 100% !important; max-width: 100% !important; overflow-wrap: anywhere; }');
        }
        return `${css}\n\n/* Mnema compatibility fixes */\n${fixes.join('\n')}\n`;
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

    private isSpaceKey(event: KeyboardEvent): boolean {
        return event.key === ' ' || event.key === 'Space' || event.key === 'Spacebar' || event.code === 'Space';
    }

    private eventTargetsMedia(event: KeyboardEvent): boolean {
        return event.composedPath().some(node => node instanceof HTMLAudioElement || node instanceof HTMLVideoElement);
    }
}
