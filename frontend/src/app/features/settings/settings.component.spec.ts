import { of } from 'rxjs';

import { SettingsComponent } from './settings.component';
import { I18nService } from '../../core/services/i18n.service';
import { ToastService } from '../../core/services/toast.service';

describe('SettingsComponent', () => {
    let component: SettingsComponent;
    let deckApi: jasmine.SpyObj<any>;
    let toast: jasmine.SpyObj<ToastService>;

    beforeEach(() => {
        deckApi = jasmine.createSpyObj('DeckApiService', ['patchDeck', 'getDeletedDecks', 'hardDeleteDeck']);
        toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info', 'warning', 'show', 'dismiss']);

        component = new SettingsComponent(
            {} as any,
            new I18nService(),
            {} as any,
            deckApi,
            jasmine.createSpyObj('AiApiService', ['listProviders', 'createProvider', 'deleteProvider']),
            jasmine.createSpyObj('UserApiService', ['getMe', 'deleteMe']),
            jasmine.createSpyObj('AuthService', ['logout']),
            jasmine.createSpyObj('Router', ['navigate']),
            toast
        );
    });

    it('shows a toast after restoring an archived deck', () => {
        component.archivedDecks = [{ userDeckId: 'deck-1' } as any];
        deckApi.patchDeck.and.returnValue(of({}));

        component.restoreDeck('deck-1');

        expect(component.archivedDecks).toEqual([]);
        expect(toast.success).toHaveBeenCalledWith('settings.archiveRestoreSuccess');
    });
});
