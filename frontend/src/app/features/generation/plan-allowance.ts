import { Injector } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { UsageApiService } from '../usage/usage-api.service';

/**
 * The whole credit allowance of the period (`usage.credits.total`): the figure a share of the limit is measured against. Read on demand and
 * once by whoever needs it; `null` when it cannot be read (the caller then falls back to the estimate's rounded percentage).
 */
export function readAllowance(injector: Injector): Promise<number | null> {
    try {
        return firstValueFrom(injector.get(UsageApiService).load()).then(usage => usage.credits.total, () => null);
    } catch {
        return Promise.resolve(null);
    }
}
