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
/** `adminCampaignView` of `contracts/usage/promo.json`: a campaign of the reward for a finished «Путь». */
export const campaign = {
    campaignId: '40000000-0000-4000-8000-000000000001',
    type: 'PATH_REWARD',
    offerType: 'DISCOUNT_PERCENT',
    percent: 10,
    plan: null,
    days: null,
    enabled: false,
    createdAt: '2026-10-20T09:00:00Z',
    issued: 12,
    redeemed: 3,
    issuedLast24h: 2
};
/** `adminIssuanceView`: only the hint, the dates and whether the code was redeemed. */
export const issuance = {
    codeHint: 'K7…RT',
    issuedAt: '2026-10-22T18:41:00Z',
    expiresAt: '2026-10-29T18:41:00Z',
    redeemedAt: null
};
export const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
