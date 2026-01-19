import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { NgIf } from '@angular/common';
import { AbstractControl, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService, PasswordStatus } from './auth.service';
import { UserApiService, UserProfile } from './user-api.service';
import { MediaApiService } from './core/services/media-api.service';
import { ButtonComponent } from './shared/components/button.component';
import { InputComponent } from './shared/components/input.component';
import { TextareaComponent } from './shared/components/textarea.component';

import { TranslatePipe } from './shared/pipes/translate.pipe';

@Component({
    standalone: true,
    selector: 'app-profile-page',
    imports: [NgIf, ReactiveFormsModule, RouterLink, ButtonComponent, InputComponent, TextareaComponent, TranslatePipe],
    template: `
    <section *ngIf="auth.status() === 'authenticated'; else notAuth" class="profile-page">
      <div class="profile-container">
        <h1>{{ 'profile.title' | translate }}</h1>

        <div *ngIf="loading" class="loading">{{ 'profile.loadingProfile' | translate }}</div>

        <div *ngIf="!loading && profile" class="profile-content">
          <div *ngIf="auth.user()?.emailVerified === false" class="verification-warning">
            <h3>{{ 'profile.unverifiedTitle' | translate }}</h3>
            <p>{{ 'profile.unverifiedText' | translate }}</p>
          </div>
          <div class="profile-header">
            <div class="avatar-section">
              <div class="avatar-container" (click)="triggerAvatarUpload()">
                <img *ngIf="avatarDisplayUrl" [src]="avatarDisplayUrl" [alt]="profile.username" class="avatar" />
                <div *ngIf="!avatarDisplayUrl" class="avatar-placeholder">
                  {{ profile.username.charAt(0).toUpperCase() }}
                </div>
                <div class="avatar-overlay" [class.uploading]="uploading">
                  <span *ngIf="!uploading" class="edit-icon">✎</span>
                  <span *ngIf="uploading" class="uploading-text">Uploading... {{ uploadProgress }}%</span>
                </div>
              </div>
              <input
                #avatarInput
                type="file"
                accept="image/*"
                (change)="onAvatarSelected($event)"
                style="display: none;"
              />
            </div>
            <div class="profile-info">
              <h2>{{ profile.username }}</h2>
              <p class="email">{{ profile.email }}</p>
              <p class="member-since">{{ 'profile.memberSince' | translate }} {{ formatDate(profile.createdAt) }}</p>
              <span *ngIf="profile.admin" class="admin-badge">{{ 'profile.admin' | translate }}</span>
            </div>
          </div>

          <form [formGroup]="form" (ngSubmit)="save()" class="edit-form">
            <h3>{{ 'profile.editProfile' | translate }}</h3>

            <app-input
              [label]="'profile.username' | translate"
              type="text"
              formControlName="username"
              [placeholder]="'profile.enterUsername' | translate"
              [hasError]="form.get('username')?.invalid && form.get('username')?.touched || false"
              [errorMessage]="usernameErrorMessage() | translate"
              [maxLength]="maxUsernameLength"
            ></app-input>

            <app-textarea
              [label]="'profile.bio' | translate"
              formControlName="bio"
              [placeholder]="'profile.enterBio' | translate"
              [rows]="4"
              [hasError]="form.get('bio')?.invalid && form.get('bio')?.touched || false"
              [errorMessage]="bioErrorMessage() | translate"
              [maxLength]="maxBioLength"
            ></app-textarea>

            <div class="form-actions">
              <app-button
                type="submit"
                variant="primary"
                [disabled]="form.invalid || saving"
              >
                {{ (saving ? 'profile.saving' : 'profile.saveChanges') | translate }}
              </app-button>
            </div>
          </form>

          <div *ngIf="passwordStatus" class="password-panel">
            <h3>{{ 'profile.passwordTitle' | translate }}</h3>
            <p class="password-description">
              {{ (passwordStatus.hasPassword ? 'profile.passwordChangeHint' : 'profile.passwordSetHint') | translate }}
            </p>
            <form [formGroup]="passwordForm" (ngSubmit)="savePassword()" class="password-form">
              <app-input
                *ngIf="passwordStatus.hasPassword"
                [label]="'profile.passwordCurrent' | translate"
                type="password"
                formControlName="currentPassword"
                [placeholder]="'profile.passwordCurrentPlaceholder' | translate"
                [hasError]="passwordForm.get('currentPassword')?.invalid && passwordForm.get('currentPassword')?.touched || false"
                [errorMessage]="'profile.passwordRequired' | translate"
              ></app-input>

              <app-input
                [label]="'profile.passwordNew' | translate"
                type="password"
                formControlName="newPassword"
                [placeholder]="'profile.passwordNewPlaceholder' | translate"
                [hasError]="passwordForm.get('newPassword')?.invalid && passwordForm.get('newPassword')?.touched || false"
                [errorMessage]="passwordErrorMessage('newPassword') | translate"
              ></app-input>

              <app-input
                [label]="'profile.passwordConfirm' | translate"
                type="password"
                formControlName="confirmPassword"
                [placeholder]="'profile.passwordConfirmPlaceholder' | translate"
                [hasError]="passwordForm.hasError('passwordMismatch') && passwordForm.get('confirmPassword')?.touched || false"
                [errorMessage]="'profile.passwordMismatch' | translate"
              ></app-input>

              <div class="form-actions">
                <app-button
                  type="submit"
                  variant="primary"
                  [disabled]="passwordForm.invalid || passwordSaving"
                >
                  {{ (passwordSaving ? 'profile.passwordSaving' : 'profile.passwordSave') | translate }}
                </app-button>
              </div>
              <div *ngIf="passwordMessageKey" class="password-success">{{ passwordMessageKey | translate }}</div>
              <div *ngIf="passwordErrorKey" class="password-error">{{ passwordErrorKey | translate }}</div>
            </form>
          </div>
        </div>
      </div>
    </section>

    <ng-template #notAuth>
      <section class="profile-page">
        <div class="profile-container">
          <h1>{{ 'profile.title' | translate }}</h1>
          <p>{{ 'profile.pleaseLogIn' | translate }}</p>
          <a routerLink="/login">
            <app-button variant="primary">{{ 'profile.logIn' | translate }}</app-button>
          </a>
        </div>
      </section>
    </ng-template>
  `,
    styles: [
        `
      .profile-page {
        padding: var(--spacing-xl) var(--spacing-md);
      }

      .profile-container {
        max-width: 800px;
        margin: 0 auto;
      }

      h1 {
        font-size: 2rem;
        font-weight: 700;
        margin-bottom: var(--spacing-xl);
        color: var(--color-text-primary);
      }

      .loading {
        color: var(--color-text-secondary);
      }

      .profile-content {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-xl);
      }

      .verification-warning {
        position: relative;
        padding: var(--spacing-lg) var(--spacing-lg) var(--spacing-lg) calc(var(--spacing-lg) + 8px);
        border-radius: var(--border-radius-lg);
        background: var(--color-card-background);
        border: 1px solid rgba(14, 165, 233, 0.35);
        box-shadow: var(--shadow-md);
        color: var(--color-text-primary);
        overflow: hidden;
      }

      .verification-warning::before {
        content: '';
        position: absolute;
        top: 10px;
        bottom: 10px;
        left: 10px;
        width: 4px;
        border-radius: 999px;
        background: linear-gradient(180deg, var(--color-primary-accent), var(--color-secondary-accent));
      }

      .verification-warning h3 {
        margin: 0 0 var(--spacing-xs) 0;
        font-size: 1.1rem;
        color: var(--color-text-primary);
      }

      .verification-warning p {
        margin: 0;
        font-size: 0.95rem;
        line-height: 1.5;
        color: var(--color-text-secondary);
      }

      .password-panel {
        padding: var(--spacing-xl);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
      }

      .password-panel h3 {
        margin: 0 0 var(--spacing-sm) 0;
      }

      .password-description {
        margin: 0 0 var(--spacing-lg) 0;
        color: var(--color-text-secondary);
      }

      .password-form {
        display: flex;
        flex-direction: column;
        gap: var(--spacing-md);
      }

      .password-success {
        color: #166534;
        font-size: 0.9rem;
      }

      .password-error {
        color: #b91c1c;
        font-size: 0.9rem;
      }

      .profile-header {
        display: flex;
        gap: var(--spacing-lg);
        padding: var(--spacing-xl);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
      }

      .avatar-section {
        flex-shrink: 0;
      }

      .avatar-container {
        position: relative;
        cursor: pointer;
        width: 96px;
        height: 96px;
      }

      .avatar,
      .avatar-placeholder {
        width: 96px;
        height: 96px;
        border-radius: 50%;
      }

      .avatar {
        object-fit: cover;
        border: 2px solid var(--border-color);
      }

      .avatar-placeholder {
        display: flex;
        align-items: center;
        justify-content: center;
        background: #111827;
        color: #fff;
        font-size: 2.5rem;
        font-weight: 600;
      }

      .avatar-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        border-radius: 50%;
        background: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        opacity: 0;
        transition: opacity 0.2s;
      }

      .avatar-container:hover .avatar-overlay {
        opacity: 1;
      }

      .avatar-overlay.uploading {
        opacity: 1;
        flex-direction: column;
        gap: var(--spacing-xs);
        text-align: center;
      }

      .uploading-text {
        color: #fff;
        font-size: 0.85rem;
        font-weight: 600;
      }

      .edit-icon {
        color: white;
        font-size: 1.5rem;
      }

      .profile-info {
        flex: 1;
      }

      .profile-info h2 {
        font-size: 1.5rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-xs) 0;
        color: var(--color-text-primary);
      }

      .email {
        color: var(--color-text-secondary);
        margin: 0 0 var(--spacing-xs) 0;
      }

      .member-since {
        color: var(--color-text-tertiary);
        font-size: 0.9rem;
        margin: 0 0 var(--spacing-sm) 0;
      }

      .admin-badge {
        display: inline-block;
        padding: var(--spacing-xs) var(--spacing-sm);
        background: #111827;
        color: #fff;
        border-radius: var(--border-radius-full);
        font-size: 0.75rem;
        font-weight: 600;
        text-transform: uppercase;
      }

      .edit-form {
        padding: var(--spacing-xl);
        background: var(--color-card-background);
        border: 1px solid var(--border-color);
        border-radius: var(--border-radius-lg);
        display: flex;
        flex-direction: column;
        gap: var(--spacing-lg);
      }

      .edit-form h3 {
        font-size: 1.25rem;
        font-weight: 600;
        margin: 0 0 var(--spacing-md) 0;
        color: var(--color-text-primary);
      }

      .form-actions {
        display: flex;
        gap: var(--spacing-md);
        padding-top: var(--spacing-md);
      }

      @media (max-width: 768px) {
        .profile-header {
          flex-direction: column;
          align-items: center;
          text-align: center;
        }

        .profile-page {
          padding: var(--spacing-lg) var(--spacing-md);
        }

        .form-actions {
          flex-direction: column;
          align-items: stretch;
        }
      }

      @media (max-width: 480px) {
        .profile-page {
          padding: var(--spacing-md) var(--spacing-sm);
        }
      }
    `
    ]
})
export class ProfilePageComponent implements OnInit {
    private static readonly MAX_USERNAME_LENGTH = 50;
    private static readonly MAX_BIO_LENGTH = 200;
    readonly maxUsernameLength = ProfilePageComponent.MAX_USERNAME_LENGTH;
    readonly maxBioLength = ProfilePageComponent.MAX_BIO_LENGTH;
    @ViewChild('avatarInput') avatarInput!: ElementRef<HTMLInputElement>;

    profile: UserProfile | null = null;
    avatarDisplayUrl: string | null = null;
    loading = false;
    saving = false;
    uploading = false;
    uploadProgress = 0;
    form: FormGroup;
    passwordForm: FormGroup;
    passwordStatus: PasswordStatus | null = null;
    passwordSaving = false;
    passwordMessageKey: string | null = null;
    passwordErrorKey: string | null = null;

    constructor(
        public auth: AuthService,
        private api: UserApiService,
        private mediaApi: MediaApiService,
        private fb: FormBuilder
    ) {
        this.form = this.fb.group({
            username: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(ProfilePageComponent.MAX_USERNAME_LENGTH)]],
            bio: ['', [Validators.maxLength(ProfilePageComponent.MAX_BIO_LENGTH)]]
        });
        this.passwordForm = this.fb.group(
            {
                currentPassword: [''],
                newPassword: ['', [Validators.required, Validators.minLength(8)]],
                confirmPassword: ['', [Validators.required]]
            },
            { validators: this.passwordMatchValidator }
        );
    }

    ngOnInit(): void {
        if (this.auth.status() !== 'authenticated') return;

        this.loading = true;
        this.api.getMe().subscribe({
            next: async profile => {
                this.profile = profile;
                await this.resolveAvatarUrl(profile);
                this.form.patchValue({
                    username: profile.username,
                    bio: profile.bio ?? ''
                });
                await this.loadPasswordStatus();
            },
            error: err => {
                console.error('Failed to load profile', err);
            },
            complete: () => {
                this.loading = false;
            }
        });
    }

    private async resolveAvatarUrl(profile: UserProfile): Promise<void> {
        if (profile.avatarMediaId) {
            try {
                const resolved = await firstValueFrom(this.mediaApi.resolve([profile.avatarMediaId]));
                this.avatarDisplayUrl = resolved[0]?.url || null;
            } catch (err) {
                console.error('Failed to resolve avatar media', err);
                this.avatarDisplayUrl = null;
            }
            return;
        }
        if (profile.avatarUrl) {
            this.avatarDisplayUrl = profile.avatarUrl;
        } else {
            this.avatarDisplayUrl = null;
        }
    }

    save(): void {
        if (!this.profile || this.form.invalid) return;

        this.saving = true;
        const values = this.form.value;
        this.api
            .updateMe({
                username: values.username,
                bio: values.bio || null
            })
            .subscribe({
                next: async profile => {
                    this.profile = profile;
                    await this.resolveAvatarUrl(profile);
                },
                error: err => {
                    console.error('Failed to save profile', err);
                },
                complete: () => {
                    this.saving = false;
                }
            });
    }

    async loadPasswordStatus(): Promise<void> {
        try {
            this.passwordStatus = await this.auth.getPasswordStatus();
            this.applyPasswordValidators();
        } catch (err) {
            console.error('Failed to load password status', err);
        }
    }

    savePassword(): void {
        if (!this.passwordStatus || this.passwordForm.invalid) return;
        this.passwordSaving = true;
        this.passwordMessageKey = null;
        this.passwordErrorKey = null;

        const currentPassword = this.passwordStatus.hasPassword
            ? (this.passwordForm.get('currentPassword')?.value as string | null)
            : null;
        const newPassword = this.passwordForm.get('newPassword')?.value as string;

        this.auth
            .setPassword(currentPassword, newPassword)
            .then(status => {
                this.passwordStatus = status;
                this.passwordMessageKey = 'profile.passwordSuccess';
                this.passwordForm.reset();
                this.applyPasswordValidators();
            })
            .catch(err => {
                console.error('Failed to update password', err);
                this.passwordErrorKey = 'profile.passwordError';
            })
            .finally(() => {
                this.passwordSaving = false;
            });
    }

    private applyPasswordValidators(): void {
        const current = this.passwordForm.get('currentPassword');
        if (!current) return;
        if (this.passwordStatus?.hasPassword) {
            current.setValidators([Validators.required]);
        } else {
            current.clearValidators();
        }
        current.updateValueAndValidity();
    }

    usernameErrorMessage(): string {
        const control = this.form.get('username');
        if (control?.hasError('required') || control?.hasError('minlength')) {
            return 'profile.usernameMinError';
        }
        if (control?.hasError('maxlength')) {
            return 'validation.maxLength50';
        }
        return '';
    }

    bioErrorMessage(): string {
        const control = this.form.get('bio');
        if (control?.hasError('maxlength')) {
            return 'validation.maxLength200';
        }
        return '';
    }

    passwordErrorMessage(controlName: string): string {
        const control = this.passwordForm.get(controlName);
        if (control?.hasError('required')) {
            return 'profile.passwordRequired';
        }
        if (control?.hasError('minlength')) {
            return 'profile.passwordMinError';
        }
        return '';
    }

    triggerAvatarUpload(): void {
        if (this.uploading) return;
        this.avatarInput.nativeElement.click();
    }

    onAvatarSelected(event: Event): void {
        const input = event.target as HTMLInputElement;
        if (input.files && input.files.length > 0) {
            void this.uploadAvatar(input.files[0]);
            input.value = '';
        }
    }

    async uploadAvatar(file: File): Promise<void> {
        this.uploading = true;
        this.uploadProgress = 0;
        try {
            const mediaId = await this.mediaApi.uploadFile(file, 'avatar', progress => {
                this.uploadProgress = progress;
            });
            this.api.updateMe({ avatarMediaId: mediaId }).subscribe({
                next: async profile => {
                    this.profile = profile;
                    await this.resolveAvatarUrl(profile);
                    this.uploading = false;
                    this.uploadProgress = 0;
                },
                error: err => {
                    console.error('Failed to update avatar', err);
                    this.uploading = false;
                    this.uploadProgress = 0;
                }
            });
        } catch (err) {
            console.error('Failed to upload avatar', err);
            this.uploading = false;
            this.uploadProgress = 0;
        }
    }

    formatDate(dateString: string | null | undefined): string {
        if (!dateString) return 'Unknown';
        const normalizedDate = this.normalizeISODate(dateString);
        if (!normalizedDate) return 'Unknown';
        const date = new Date(normalizedDate);
        if (isNaN(date.getTime())) return 'Unknown';
        return date.toLocaleDateString('en-US', {
            year: 'numeric',
            month: 'long',
            day: 'numeric'
        });
    }

    private normalizeISODate(isoString?: string | null): string | null {
        if (!isoString) return null;

        let normalized = isoString.trim();

        normalized = normalized.replace(/(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})/, '$1T$2');

        normalized = normalized.replace(/\s+([+-]\d{2}:\d{2})$/, '$1');
        normalized = normalized.replace(/\s+([+-]\d{2})$/, '$1');

        normalized = normalized.replace(/\.(\d{3})\d*/, '.$1');

        if (/[+-]\d{2}$/.test(normalized)) {
            normalized = normalized.replace(/([+-]\d{2})$/, '$1:00');
        }

        try {
            const test = new Date(normalized);
            if (isNaN(test.getTime())) return null;
        } catch {
            return null;
        }

        return normalized;
    }

    private passwordMatchValidator(control: AbstractControl): { [key: string]: boolean } | null {
        const newPassword = control.get('newPassword')?.value;
        const confirmPassword = control.get('confirmPassword')?.value;
        if (!newPassword || !confirmPassword) {
            return null;
        }
        return newPassword === confirmPassword ? null : { passwordMismatch: true };
    }
}
