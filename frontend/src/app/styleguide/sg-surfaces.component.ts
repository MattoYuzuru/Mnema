import { ChangeDetectionStrategy, Component, ViewEncapsulation, inject, signal } from '@angular/core';

import { NativeDocument } from '../content/native-document';
import { documentOf, nativeNode } from '../content/rendering/native-renderer.fixtures';
import { NativeDocumentRendererComponent } from '../content/rendering/native-document-renderer.component';
import { DEMO_ASSETS } from '../content/exercise/demo/demo-media';
import { ToastService } from '../core/notifications/toast.service';
import { AiPromptWindowComponent } from '../features/generation/ai-prompt-window.component';
import { BatchPagerComponent } from '../features/generation/batch-pager.component';
import { ArtifactSummary } from '../features/generation/generation.models';
import { PromoPopupComponent } from '../features/promo/promo-popup.component';
import { PromoCampaign } from '../features/promo/promo.models';
import { NewBadgeComponent } from '../shared/new-badge.component';
import { PublicFooterComponent } from '../shared/public-footer.component';
import { MailContactComponent } from '../shared/mail-contact.component';
import { SupportContactComponent } from '../shared/support-contact.component';
import { ToggletipComponent } from '../shared/toggletip.component';
import { UsageMeterComponent } from '../shared/usage-meter.component';
import { SgLegalComponent } from './sg-legal.component';
import { SgSpecimenComponent } from './sg-specimen.component';

const ARTIFACT_STATES: readonly ArtifactSummary['state'][] = ['PUBLISHED', 'PROPOSED', 'GENERATING', 'FAILED', 'REJECTED', 'STALE'];

function artifact(index: number): ArtifactSummary {
    return {
        artifactId: `d5000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}`, ordinal: index + 1, targetKind: 'ITEM',
        state: ARTIFACT_STATES[index], rowVersion: '1', title: `Материал ${index + 1}`, currentRevisionId: null,
        mediaSlotCounts: { total: 0, ready: 0, failed: 0 }, errorCode: null, repinStatus: null, publishedRef: null
    };
}

const PLACEHOLDER_ASSET = 'd5000000-0000-4000-8000-0000000000aa';

/** «Меню и окна», «Вкладки и пейджер», «Статусы и ход», «Обратная связь», «Карточки и области». */
@Component({
    selector: 'app-sg-surfaces',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, ToggletipComponent, AiPromptWindowComponent, BatchPagerComponent, NewBadgeComponent, UsageMeterComponent,
        NativeDocumentRendererComponent, PromoPopupComponent, PublicFooterComponent, SupportContactComponent, MailContactComponent, SgLegalComponent],
    templateUrl: './sg-surfaces.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgSurfacesComponent {
    private readonly toasts = inject(ToastService);
    private nextToast = 0;

    protected readonly popupOpen = signal(false);
    protected readonly campaign: PromoCampaign = {
        id: 'sg-demo', title: 'Plus дешевле до конца октября', cta: 'Посмотреть тарифы', code: 'AUTUMN-26',
        body: 'Скидка на первый платный месяц. Ничего не включается само: тариф и промокод вы выбираете сами.'
    };
    protected readonly windowOpen = signal(false);
    protected readonly instruction = signal('');
    protected readonly artifacts: readonly ArtifactSummary[] = ARTIFACT_STATES.map((_, index) => artifact(index));
    protected readonly selectedArtifact = signal<string | null>(this.artifacts[0].artifactId);
    protected readonly assetSources = {
        [DEMO_ASSETS.waveDense]: '/assets/demo/wave-dense.svg',
        [DEMO_ASSETS.toneLow]: { url: '/assets/demo/tone-low.mp3', mimeType: 'audio/mpeg' }
    };
    protected readonly mediaDocument: NativeDocument = documentOf([
        nativeNode('paragraph', {}, [nativeNode('text', { text: 'Картинка и звук из материала; пока медиа не готово, на его месте стоит плейсхолдер.' })]),
        nativeNode('image', { assetId: DEMO_ASSETS.waveDense, alt: 'Схема звуковой волны: колебания частые' }),
        nativeNode('audio', { assetId: DEMO_ASSETS.toneLow, title: 'Низкий тон' }),
        nativeNode('image', { assetId: PLACEHOLDER_ASSET, alt: 'Иллюстрация к материалу' })
    ]);
    protected readonly assetStatuses = { [PLACEHOLDER_ASSET]: 'Изображение готовится' };

    protected openPopup(): void { this.popupOpen.set(true); }
    protected closePopup(): void { this.popupOpen.set(false); }

    protected openWindow(): void { this.windowOpen.set(true); }
    protected closeWindow(): void { this.windowOpen.set(false); }

    protected echo(): void { this.toasts.echo('Материал одобрен.'); }

    protected notify(severity: 'INFO' | 'WARNING' | 'ERROR'): void {
        const text = {
            INFO: 'Мнема закончила материал «Падежи».',
            WARNING: 'Лимит ИИ почти исчерпан: осталось около 8 %.',
            ERROR: 'Не удалось собрать упражнения. Ничего не списано.'
        }[severity];
        this.toasts.notify(`sg-${this.nextToast++}`, text, severity, null);
    }
}
