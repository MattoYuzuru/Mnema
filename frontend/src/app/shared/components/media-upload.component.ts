import { Component, Input, Output, EventEmitter, HostListener } from '@angular/core';
import { NgIf, NgClass } from '@angular/common';
import { MediaApiService, MediaKind } from '../../core/services/media-api.service';
import { CardContentValue } from '../../core/models/user-card.models';
import { ButtonComponent } from './button.component';

@Component({
    selector: 'app-media-upload',
    standalone: true,
    imports: [NgIf, NgClass, ButtonComponent],
    template: `
    <div class="media-upload">
      <label *ngIf="label" class="field-label">{{ label }}</label>

      <div
        class="upload-area"
        [class.dragging]="isDragging"
        [class.has-media]="hasMedia"
        (click)="openFilePicker()"
        (dragover)="onDragOver($event)"
        (dragleave)="onDragLeave($event)"
        (drop)="onDrop($event)"
      >
        <div *ngIf="!hasMedia && !uploading" class="upload-prompt">
          <span class="upload-icon">{{ getIcon() }}</span>
          <p class="upload-text">Drag & drop or click to select {{ fieldType }}</p>
          <p class="upload-hint">{{ getAcceptedFormats() }}</p>
        </div>

        <div *ngIf="uploading" class="upload-progress">
          <div class="progress-bar">
            <div class="progress-fill" [style.width.%]="uploadProgress"></div>
          </div>
          <p>Uploading... {{ uploadProgress }}%</p>
        </div>

        <div *ngIf="hasMedia && !uploading" class="media-preview">
          <img *ngIf="fieldType === 'image' && mediaUrl" [src]="mediaUrl" alt="Preview" class="preview-image" />
          <audio *ngIf="fieldType === 'audio' && mediaUrl" [src]="mediaUrl" controls class="preview-audio"></audio>
          <video *ngIf="fieldType === 'video' && mediaUrl" [src]="mediaUrl" controls class="preview-video"></video>
          <p *ngIf="!mediaUrl" class="media-id-label">Media ID: {{ getMediaId() }}</p>
        </div>

        <input
          #fileInput
          type="file"
          [accept]="getAcceptString()"
          (change)="onFileSelected($event)"
          style="display: none;"
        />
      </div>

      <div *ngIf="hasMedia" class="media-actions">
        <app-button variant="ghost" size="sm" (click)="exportMedia(); $event.stopPropagation()">Export</app-button>
        <app-button variant="ghost" size="sm" (click)="clearMedia(); $event.stopPropagation()">Remove</app-button>
      </div>

      <p *ngIf="error" class="error-message">{{ error }}</p>
    </div>
  `,
    styles: [`
      .media-upload { display: flex; flex-direction: column; gap: var(--spacing-xs); }
      .field-label { font-size: 0.875rem; font-weight: 500; color: var(--color-text-primary); }
      .upload-area {
        min-height: 120px;
        border: 2px dashed var(--border-color);
        border-radius: var(--border-radius-md);
        background: var(--color-background);
        cursor: pointer;
        transition: all 0.2s;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: var(--spacing-md);
      }
      .upload-area:hover { border-color: var(--color-primary); background: var(--color-card-background); }
      .upload-area.dragging { border-color: var(--color-primary); background: var(--color-primary-light); }
      .upload-area.has-media { border-style: solid; }
      .upload-prompt { text-align: center; }
      .upload-icon { font-size: 2rem; display: block; margin-bottom: var(--spacing-sm); }
      .upload-text { margin: 0; font-size: 0.9rem; color: var(--color-text-primary); }
      .upload-hint { margin: var(--spacing-xs) 0 0; font-size: 0.8rem; color: var(--color-text-secondary); }
      .upload-progress { text-align: center; width: 100%; }
      .progress-bar { height: 8px; background: var(--color-background); border-radius: 4px; overflow: hidden; margin-bottom: var(--spacing-sm); }
      .progress-fill { height: 100%; background: var(--color-primary); transition: width 0.3s; }
      .media-preview { text-align: center; max-width: 100%; }
      .preview-image { max-width: 100%; max-height: 200px; border-radius: var(--border-radius-md); }
      .preview-audio { width: 100%; }
      .preview-video { max-width: 100%; max-height: 200px; border-radius: var(--border-radius-md); }
      .media-id-label { font-size: 0.8rem; color: var(--color-text-secondary); margin: 0; }
      .media-actions { display: flex; gap: var(--spacing-sm); justify-content: center; }
      .error-message { margin: 0; font-size: 0.85rem; color: #ef4444; }
    `]
})
export class MediaUploadComponent {
    @Input() value: CardContentValue | null = null;
    @Input() fieldType: 'image' | 'audio' | 'video' = 'image';
    @Input() label: string = '';
    @Output() valueChange = new EventEmitter<CardContentValue | null>();

    isDragging = false;
    uploading = false;
    uploadProgress = 0;
    error: string | null = null;

    constructor(private mediaApi: MediaApiService) {}

    get hasMedia(): boolean {
        if (!this.value) return false;
        if (typeof this.value === 'string') return this.value.length > 0;
        return !!this.value.mediaId;
    }

    get mediaUrl(): string | null {
        if (!this.value) return null;
        if (typeof this.value === 'string') return this.value;
        return this.value.url || null;
    }

    getMediaId(): string | null {
        if (!this.value || typeof this.value === 'string') return null;
        return this.value.mediaId;
    }

    getIcon(): string {
        switch (this.fieldType) {
            case 'image': return '🖼️';
            case 'audio': return '🎵';
            case 'video': return '🎬';
        }
    }

    getAcceptedFormats(): string {
        switch (this.fieldType) {
            case 'image': return 'JPG, PNG, GIF, WebP';
            case 'audio': return 'MP3, WAV, OGG, M4A';
            case 'video': return 'MP4, WebM, OGG';
        }
    }

    getAcceptString(): string {
        switch (this.fieldType) {
            case 'image': return 'image/*';
            case 'audio': return 'audio/*';
            case 'video': return 'video/*';
        }
    }

    getMediaKind(): MediaKind {
        switch (this.fieldType) {
            case 'image': return 'card_image';
            case 'audio': return 'card_audio';
            case 'video': return 'card_video';
        }
    }

    openFilePicker(): void {
        if (this.uploading) return;
        const input = document.querySelector('input[type="file"]') as HTMLInputElement;
        input?.click();
    }

    onDragOver(event: DragEvent): void {
        event.preventDefault();
        event.stopPropagation();
        this.isDragging = true;
    }

    onDragLeave(event: DragEvent): void {
        event.preventDefault();
        event.stopPropagation();
        this.isDragging = false;
    }

    onDrop(event: DragEvent): void {
        event.preventDefault();
        event.stopPropagation();
        this.isDragging = false;

        const files = event.dataTransfer?.files;
        if (files && files.length > 0) {
            void this.uploadFile(files[0]);
        }
    }

    onFileSelected(event: Event): void {
        const input = event.target as HTMLInputElement;
        if (input.files && input.files.length > 0) {
            void this.uploadFile(input.files[0]);
        }
    }

    async uploadFile(file: File): Promise<void> {
        this.error = null;
        this.uploading = true;
        this.uploadProgress = 0;

        try {
            const mediaId = await this.mediaApi.uploadFile(
                file,
                this.getMediaKind(),
                (percent) => {
                    this.uploadProgress = percent;
                }
            );

            const newValue: CardContentValue = {
                mediaId,
                kind: this.fieldType
            };

            this.value = newValue;
            this.uploading = false;
            this.valueChange.emit(newValue);
        } catch (err) {
            this.error = 'Upload failed. Please try again.';
            this.uploading = false;
            console.error('Upload error:', err);
        }
    }

    async exportMedia(): Promise<void> {
        let url = this.mediaUrl;

        if (!url && this.getMediaId()) {
            try {
                const resolved = await this.mediaApi.resolve([this.getMediaId()!]).toPromise();
                const urlMap = this.mediaApi.toUrlMap(resolved || []);
                url = urlMap[this.getMediaId()!] || null;
            } catch (err) {
                console.error('Failed to resolve media:', err);
                this.error = 'Failed to load media URL';
                return;
            }
        }

        if (url) {
            window.open(url, '_blank');
        }
    }

    clearMedia(): void {
        this.value = null;
        this.valueChange.emit(null);
    }
}
