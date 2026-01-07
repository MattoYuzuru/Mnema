import { Component, OnInit, ViewChild, ElementRef } from '@angular/core';
import { NgIf } from '@angular/common';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from './auth.service';
import { UserApiService, UserProfile } from './user-api.service';
import { MediaApiService } from './core/services/media-api.service';
import { ButtonComponent } from './shared/components/button.component';
import { InputComponent } from './shared/components/input.component';
import { TextareaComponent } from './shared/components/textarea.component';

@Component({
    standalone: true,
    selector: 'app-profile-page',
    imports: [NgIf, ReactiveFormsModule, RouterLink, ButtonComponent, InputComponent, TextareaComponent],
    template: `
    <section *ngIf="auth.status() === 'authenticated'; else notAuth" class="profile-page">
      <div class="profile-container">
        <h1>Profile</h1>

        <div *ngIf="loading" class="loading">Loading profile...</div>

        <div *ngIf="!loading && profile" class="profile-content">
          <div class="profile-header">
            <div class="avatar-section">
              <div class="avatar-container" (click)="triggerAvatarUpload()">
                <img *ngIf="profile.avatarUrl" [src]="profile.avatarUrl" [alt]="profile.username" class="avatar" />
                <div *ngIf="!profile.avatarUrl" class="avatar-placeholder">
                  {{ profile.username.charAt(0).toUpperCase() }}
                </div>
                <div class="avatar-overlay">
                  <span class="edit-icon">✎</span>
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
              <p class="member-since">Member since {{ formatDate(profile.createdAt) }}</p>
              <span *ngIf="profile.admin" class="admin-badge">Admin</span>
            </div>
          </div>

          <form [formGroup]="form" (ngSubmit)="save()" class="edit-form">
            <h3>Edit Profile</h3>

            <app-input
              label="Username"
              type="text"
              formControlName="username"
              placeholder="Enter username"
              [hasError]="form.get('username')?.invalid && form.get('username')?.touched || false"
              errorMessage="Username must be at least 3 characters"
            ></app-input>

            <app-textarea
              label="Bio"
              formControlName="bio"
              placeholder="Tell us about yourself"
              [rows]="4"
            ></app-textarea>

            <app-input
              label="Avatar URL"
              type="url"
              formControlName="avatarUrl"
              placeholder="https://example.com/avatar.jpg"
            ></app-input>

            <div class="form-actions">
              <app-button
                type="submit"
                variant="primary"
                [disabled]="form.invalid || saving"
              >
                {{ saving ? 'Saving...' : 'Save Changes' }}
              </app-button>
            </div>
          </form>
        </div>
      </div>
    </section>

    <ng-template #notAuth>
      <section class="profile-page">
        <div class="profile-container">
          <h1>Profile</h1>
          <p>Please log in to view your profile.</p>
          <a routerLink="/login">
            <app-button variant="primary">Log In</app-button>
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
      }
    `
    ]
})
export class ProfilePageComponent implements OnInit {
    @ViewChild('avatarInput') avatarInput!: ElementRef<HTMLInputElement>;

    profile: UserProfile | null = null;
    loading = false;
    saving = false;
    uploading = false;
    form: FormGroup;

    constructor(
        public auth: AuthService,
        private api: UserApiService,
        private mediaApi: MediaApiService,
        private fb: FormBuilder
    ) {
        this.form = this.fb.group({
            username: ['', [Validators.required, Validators.minLength(3)]],
            bio: [''],
            avatarUrl: ['']
        });
    }

    ngOnInit(): void {
        if (this.auth.status() !== 'authenticated') return;

        this.loading = true;
        this.api.getMe().subscribe({
            next: profile => {
                this.profile = profile;
                this.form.patchValue({
                    username: profile.username,
                    bio: profile.bio ?? '',
                    avatarUrl: profile.avatarUrl ?? ''
                });
            },
            error: err => {
                console.error('Failed to load profile', err);
            },
            complete: () => {
                this.loading = false;
            }
        });
    }

    save(): void {
        if (!this.profile || this.form.invalid) return;

        this.saving = true;
        const values = this.form.value;
        this.api
            .updateMe({
                username: values.username,
                bio: values.bio || null,
                avatarUrl: values.avatarUrl || null
            })
            .subscribe({
                next: profile => {
                    this.profile = profile;
                },
                error: err => {
                    console.error('Failed to save profile', err);
                },
                complete: () => {
                    this.saving = false;
                }
            });
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
        try {
            const mediaId = await this.mediaApi.uploadFile(file, 'avatar', () => {});
            this.api.updateMe({ avatarMediaId: mediaId }).subscribe({
                next: profile => {
                    this.profile = profile;
                    this.uploading = false;
                },
                error: err => {
                    console.error('Failed to update avatar', err);
                    this.uploading = false;
                }
            });
        } catch (err) {
            console.error('Failed to upload avatar', err);
            this.uploading = false;
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
        let normalized = isoString.replace(/\.(\d{3})\d*/, '.$1');
        if (/[+-]\d{2}$/.test(normalized)) {
            normalized = normalized.replace(/([+-]\d{2})$/, '$1:00');
        }
        return normalized;
    }
}
