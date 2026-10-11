import { ChangeDetectionStrategy, Component, DestroyRef, ViewEncapsulation, computed, inject, signal } from '@angular/core';

import { NativeDocument } from '../content/native-document';
import { documentOf, nativeNode } from '../content/rendering/native-renderer.fixtures';
import { NativeDocumentRendererComponent } from '../content/rendering/native-document-renderer.component';
import { DEMO_ASSETS } from '../content/exercise/demo/demo-media';
import { ToastService } from '../core/notifications/toast.service';
import { AiPromptWindowComponent } from '../features/generation/ai-prompt-window.component';
import { BatchPagerComponent } from '../features/generation/batch-pager.component';
import { ArtifactSummary } from '../features/generation/generation.models';
import { PublicationApiService } from '../features/own-decks/publication/publication-api.service';
import { PublicationBlockComponent } from '../features/own-decks/publication/publication-block.component';
import { ItemApiService } from '../features/authoring/item-api.service';
import { PromoPopupComponent } from '../features/promo/promo-popup.component';
import { PromoCampaign } from '../features/promo/promo.models';
import { AccessLevelComponent } from '../shared/access-level.component';
import { ActionMenuComponent, ActionMenuItem } from '../shared/action-menu.component';
import { PublicDeckCardComponent } from '../shared/public-deck-card.component';
import { PublicDeckCard } from '../shared/public-deck-card';
import { ShareButtonComponent } from '../shared/share-button.component';
import { ShareLinkFieldComponent } from '../shared/share-link-field.component';
import { SHARE_MENU_ITEM, ShareLinkService } from '../shared/share-link.service';
import { AuthorChipComponent } from '../shared/author-chip.component';
import { NewBadgeComponent } from '../shared/new-badge.component';
import { PublicFooterComponent } from '../shared/public-footer.component';
import { MailContactComponent } from '../shared/mail-contact.component';
import { SupportContactComponent } from '../shared/support-contact.component';
import { ToggletipComponent } from '../shared/toggletip.component';
import { UsageMeterComponent } from '../shared/usage-meter.component';
import { AutoLoadComponent } from '../shared/auto-load.component';
import { SgLegalComponent } from './sg-legal.component';
import { DemoItemApi, DemoPublicationApi, SG_PRIVATE_DECK, SG_SHARED_DECK } from './sg-publication-demo';
import { SgSpecimenComponent } from './sg-specimen.component';

const ARTIFACT_STATES: readonly ArtifactSummary['state'][] = ['PUBLISHED', 'PROPOSED', 'GENERATING', 'FAILED', 'REJECTED', 'STALE'];

function artifact(index: number): ArtifactSummary {
    return {
        artifactId: `d5000000-0000-4000-8000-${(index + 1).toString().padStart(12, '0')}`, ordinal: index + 1, targetKind: 'ITEM',
        state: ARTIFACT_STATES[index], rowVersion: '1', title: `Материал ${index + 1}`, currentRevisionId: null,
        mediaSlotCounts: { total: 0, ready: 0, failed: 0 }, errorCode: null, repinStatus: null, publishedRef: null
    };
}

const REPORT_ITEM: ActionMenuItem = { id: 'report', label: 'Пожаловаться' };

/** Выдуманные колоды для образцов карточки: полная, без автора и медиа, с малой аудиторией. */
const CARD_SAMPLES: readonly PublicDeckCard[] = [
    {
        id: 'sg-card-full', title: 'Испанские глаголы: 300 самых частых',
        description: 'Спряжение в настоящем и прошедшем времени, короткие примеры из жизни и проверка на слух. Подходит для уровня A2 и выше, занимает около месяца.',
        topic: 'Языки', language: 'Испанский', author: { username: 'anna.k', avatarSrc: null }, materialCount: 312, exerciseCount: 640,
        media: ['audio', 'image'], addedCount: 1234, learningNowCount: 87, updatedAt: '2026-10-03T09:00:00Z', shareUrl: 'https://mnema.app/d/AbCdEfGh12/ispanskie-glagoly'
    },
    {
        id: 'sg-card-bare', title: 'Столицы Европы', description: 'Страна и столица, без картинок.', topic: 'География', language: 'Русский',
        author: { username: null, avatarSrc: null }, materialCount: 44, exerciseCount: 44, media: [], addedCount: 9, learningNowCount: 3,
        updatedAt: '2025-12-20T09:00:00Z', shareUrl: 'https://mnema.app/d/ZyXwVuTs98/stolitsy-evropy'
    }
];

const PLACEHOLDER_ASSET = 'd5000000-0000-4000-8000-0000000000aa';

/** «Меню и окна», «Вкладки и пейджер», «Статусы и ход» (в том числе чип автора), «Обратная связь», «Карточки и области». */
@Component({
    selector: 'app-sg-surfaces',
    encapsulation: ViewEncapsulation.None,
    imports: [SgSpecimenComponent, AuthorChipComponent, ActionMenuComponent, PublicDeckCardComponent, ShareButtonComponent, ShareLinkFieldComponent, ToggletipComponent, AiPromptWindowComponent, BatchPagerComponent, NewBadgeComponent, UsageMeterComponent,
        NativeDocumentRendererComponent, PromoPopupComponent, PublicFooterComponent, SupportContactComponent, MailContactComponent, SgLegalComponent, AutoLoadComponent,
        AccessLevelComponent, PublicationBlockComponent],
    providers: [{ provide: PublicationApiService, useClass: DemoPublicationApi }, { provide: ItemApiService, useClass: DemoItemApi }],
    templateUrl: './sg-surfaces.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SgSurfacesComponent {
    private readonly toasts = inject(ToastService);
    private readonly shareLinks = inject(ShareLinkService);
    private nextToast = 0;
    protected readonly privateDeck = SG_PRIVATE_DECK;
    protected readonly sharedDeck = SG_SHARED_DECK;

    protected readonly scrollCount = signal(20);
    protected readonly scrollRows = computed(() => Array.from({ length: this.scrollCount() }, (_, index) => index + 1));
    protected readonly scrollCursor = computed(() => this.scrollCount() < 60 ? `after-${this.scrollCount()}` : null);
    protected readonly scrollLoading = signal(false);
    protected readonly scrollError = signal<string | null>(null);
    protected readonly failNextPage = signal(false);
    private scrollTimer: ReturnType<typeof setTimeout> | null = null;

    constructor() { inject(DestroyRef).onDestroy(() => { if (this.scrollTimer !== null) clearTimeout(this.scrollTimer); }); }

    protected loadScrollPage(): void {
        if (this.scrollLoading() || this.scrollCursor() === null) return;
        this.scrollError.set(null);
        this.scrollLoading.set(true);
        this.scrollTimer = setTimeout(() => {
            if (this.failNextPage()) {
                this.scrollError.set('Пример сетевого сбоя. Загруженные записи сохранились.');
                this.failNextPage.set(false);
            } else this.scrollCount.update(count => Math.min(60, count + 20));
            this.scrollLoading.set(false);
        }, 300);
    }

    protected readonly menuItems: readonly ActionMenuItem[] = [SHARE_MENU_ITEM, REPORT_ITEM];
    protected readonly menuStates: readonly ActionMenuItem[] = [SHARE_MENU_ITEM, { id: 'soon', label: 'Недоступное действие', disabled: true }, { id: 'danger', label: 'Опасное действие', danger: true }];
    protected readonly cardSamples = CARD_SAMPLES;
    protected readonly menuStatus = signal('Выберите пункт меню: здесь будет сказано, что произошло.');
    protected readonly menuFallback = signal<string | null>(null);

    protected async menuChosen(id: string): Promise<void> {
        this.menuFallback.set(null);
        if (id === SHARE_MENU_ITEM.id) {
            const url = 'https://mnema.app/styleguide';
            const outcome = await this.shareLinks.share(url, 'Каталог Mnema (образец)');
            this.menuStatus.set({ shared: 'Системное меню приняло ссылку.', copied: 'Ссылка скопирована.', cancelled: 'Меню «Поделиться» закрыто без выбора.', failed: 'Скопировать не удалось.' }[outcome]);
            if (outcome === 'failed') this.menuFallback.set(url);
        } else this.menuStatus.set(`Выбран пункт «${id}».`);
    }

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
