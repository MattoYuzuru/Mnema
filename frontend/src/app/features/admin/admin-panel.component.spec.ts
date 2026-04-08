import { of } from 'rxjs';

import { I18nService } from '../../core/services/i18n.service';
import { ToastService } from '../../core/services/toast.service';
import { AdminPanelComponent } from './admin-panel.component';

describe('AdminPanelComponent', () => {
    let component: AdminPanelComponent;
    let adminApi: jasmine.SpyObj<any>;
    let toast: jasmine.SpyObj<ToastService>;

    beforeEach(() => {
        adminApi = jasmine.createSpyObj('AdminApiService', ['grantAdmin']);
        toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info', 'warning', 'show', 'dismiss']);

        component = new AdminPanelComponent(
            adminApi,
            jasmine.createSpyObj('ReportApiService', ['closeReport']),
            jasmine.createSpyObj('Router', ['navigate']),
            new I18nService(),
            toast
        );
    });

    it('shows a toast after granting admin rights', () => {
        adminApi.grantAdmin.and.returnValue(of({}));
        spyOn(component, 'reloadAll');

        component.grantAdmin({ id: 'user-1' } as any);

        expect(toast.success).toHaveBeenCalledWith('adminPanel.grantAdminSuccess');
        expect(component.reloadAll).toHaveBeenCalled();
    });
});
