import access from '../../../../../contracts/admin/access.example.json';
import report from '../../../../../contracts/admin/report.example.json';
import directory from '../../../../../contracts/admin/directory.example.json';
import user from '../../../../../contracts/admin/user.example.json';
import audit from '../../../../../contracts/admin/audit.example.json';
import support from '../../../../../contracts/admin/support.json';
/** Shared executable wire fixtures. Only tests import this module. */
export const wire = {
    access,
    report,
    directory,
    user,
    audit,
    support
};
export const accountId = directory.accounts[0].accountId;
export const ownerId = '20000000-0000-4000-8000-000000000001';
export const promo = {
    codeId: '30000000-0000-4000-8000-000000000001',
    hint: 'AB…89',
    type: 'TIER_DAYS',
    plan: 'PLUS',
    days: 7,
    months: null,
    percent: null,
    validFrom: '2026-10-09T00:00:00Z',
    validUntil: null,
    maxRedemptions: 10,
    oncePerAccount: true,
    channel: null,
    enabled: true,
    redemptions: 2,
    createdAt: '2026-10-09T00:00:00Z'
};
export const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
