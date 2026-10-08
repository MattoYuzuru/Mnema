---
artifact:
  id: russia-legal-launch-checklist-2026
  type: launch-checklist
  title: "Mnema legal and payment launch status"
  status: proposed
  updated_at: "2026-10-08"
  owners: ["project-owner"]
---

# Legal and payment launch status

Owner-reported state on 2026-10-08; sensitive filing evidence stays outside GitHub.
This checklist records launch dependencies, not legal advice or a substitute for
review of the actual recipients, contracts and data flows.

## Current status

- The owner is an **ИП на НПД**. Status on 2026-10-08, according to the owner: the
  main personal-data operator notification is **confirmed**, and the
  **cross-border notification for Cloudflare (Turnstile) is submitted**. The owner
  decided to open production now: password login/registration behind Cloudflare
  Turnstile `required` plus Google, Yandex and GitHub OAuth. The owner accepts the
  risk that article 12 part 11 of 152-FZ (no transfer to a state outside the
  adequacy list until the ten-working-day period after the notice has passed) may
  not yet have elapsed. The legal-gate flags were removed from code
  (`privacy-approved`, the federated-login flag); real controls stay (Siteverify,
  rate limits, `blocked` kill switch).
- The public [privacy policy](../../frontend/src/app/privacy-page.component.html) and
  [terms](../../frontend/src/app/terms-page.component.html) are published from code
  (effective 2026-10-08, version 1.0) with the operator requisites from
  `frontend/src/app/shared/legal-operator.ts` (contact e-mail approved by the owner on
  2026-10-09: printed in the policy and terms, offered as a «Написать на почту» action
  in the footer without printing it). Legal bases: contract (article 6 part 1
  item 5) for the account, the service and the optional learning goal (a skippable
  personalization under the agreement), legitimate interest (item 7) for security.
  No purpose rests on consent at launch and no consent checkbox exists. A
  separate-document consent with a server-side record (version and time, a Flyway
  migration in Identity) becomes necessary before any purpose that rests on consent,
  such as marketing mail, analytics or AI/voice processing, is switched on.
- Current processors named in the policy: the Russian virtual-server provider
  (Selectel), Yandex Cloud (Postbox service mail; Object Storage encrypted offsite
  backups, 30-day expiry), Cloudflare (cross-border, Turnstile). Telegram
  processes support messages under its own rules and is not a channel for
  personal-data requests.
- The current production database is in Russia; the empty launch is owner-approved.
  Root/auth/www DNS remains DNS-only. Cloudflare proxying is not covered by the
  Turnstile disclosure. Foreign AI recipients are disabled and are not listed in the
  policy as active; the policy must be updated before they are enabled. Media,
  mail and account deletion remain unconfigured (see
  [runtime configuration](../operations/vps-runtime.md#auth-configuration)).
- An approved private operator-contact channel is not defined in the checked-in
  launch documents. Confirm it with the owner under human/legal task #351 before
  public activation that needs personal-data requests. Public GitHub discussions
  must not solicit account or other personal data; profile self-service and voice
  consent withdrawal do not replace the operator's contact channel.
- Learning's account purge is not implemented. Before public activation, the
  account-deletion owner must include the learning-profile goal/answer timestamp,
  owner-linked usage records and backups in the retention/erasure inventory.
  See [Learning retention](../../backend/services/learning/guide.md#usage-ledger-and-ai-budget-281).
- The owner defers the T-Business acquiring application until plans/paywall and
  the bank's site/test-account requirements are ready. The manager's written reply
  about **НПД receipts** is pending. No working payment terminal is claimed.

## Product and payment boundary

Accepted plans are **Free, Plus 449 ₽, Pro 990 ₽**; Max 1 900 ₽ is a gated future
teaser. Prices, limits and trial decisions come only from the
[AI product contract](ai-layer-2026-10.md) and [usage contract](../../contracts/usage/README.md).
Paywall/profile usage is #301 in #77. Payment and billing are #79: checkout,
renewals, payer settings/cancel, receipts, refunds and reconciliation are separate
implementation. A browser return URL never grants an entitlement.

Before receiving money, obtain bank terms and test/working terminal credentials,
recurrent capability, approved seller details/offer/privacy disclosures, explicit
unselected recurring consent, cancellation/refund rules and a verified receipt
flow. T-Bank's [non-PCI autopay flow](https://developer.tbank.ru/eacq/scenarios/payments/nonPCI/autopay/)
uses a first recurrent payment/RebillId and subsequent merchant-initiated Charge;
Mnema must own renewal scheduling, idempotency, cancellation and reconciliation.
Do not store PAN/CVV or put terminal credentials in browser code.

НПД requires a «Мой налог» receipt; a bank payment confirmation is not that receipt.
T-Bank's published [«Чеки» conditions](https://www.tbank.ru/business/help/business-payments/kassa/check/)
do not establish NPD support. Preserve the pending bank answer; verify success,
refund and gross-amount reconciliation before production payments. Current
[ФНС NPD guidance](https://npd.nalog.ru/faq/) and the actual merchant agreement govern
tax/receipt obligations. Real money remains closed until #79 acceptance and these
human dependencies pass.

## Promo owner-data retention (deferred)

The Learning account-purge owner and human/legal task #351 must agree retention,
access and deletion rules before an account-purge implementation. Inventory:

- `promo_attempt`, `promo_popup_state`, `promo_discount` and `promo_redemption`
  linked by `owner_id`, including campaign choices, discounts, redemption audit
  and address/device hashes;
- the administrator identity in `promo_code.created_by`;
- promo-scoped `command_receipt` rows (`command_scope='promo'`): `actor_id`, stored
  `result`, keyed-derived fingerprint and operation timestamps.

Hashes, opaque ids and replacement tombstones may remain linkable; they are not
proof of anonymity. Decide justified audit retention and its duration alongside
deletion, preserving activation-count and once-per-account semantics. The two-hour
attempt expiry is operational cleanup, not an account-wide purge. No such purge
is implemented by #302.

The inventory includes copies in backups. The [current VPS backup policy](../operations/vps-runtime.md#backup-monitoring-and-rollback)
retains local dumps without automatic retention deletion; offsite backup remains
deferred in #350. Define dump expiry/access and reapplication of deletion rules
after restore. Removing live rows alone cannot establish that retained copies
are gone. These are deferred decisions, with no backup deletion or restore claimed.
