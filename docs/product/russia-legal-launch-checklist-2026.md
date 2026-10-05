---
artifact:
  id: russia-legal-launch-checklist-2026
  type: launch-checklist
  title: "Mnema legal and payment launch status"
  status: proposed
  updated_at: "2026-10-05"
  owners: ["project-owner"]
---

# Legal and payment launch status

Owner-reported state on 2026-10-05; sensitive filing evidence stays outside GitHub.
This checklist records launch dependencies, not legal advice or a substitute for
review of the actual recipients, contracts and data flows.

## Current status

- The owner is an **ИП на НПД**. The main personal-data operator notification is
  confirmed, according to the owner. The cross-border notification is **not yet
  confirmed**. Main notification confirmation does not approve every foreign flow.
- The current production database is in Russia; the empty launch is owner-approved.
  Root/auth/www DNS remains DNS-only. Cloudflare proxying is not covered by the
  Turnstile worksheet. Cloudflare/foreign AI/OAuth recipient, country, purpose,
  legal basis, retention and privacy disclosures need review before activation.
- Production password login/register is blocked by Turnstile policy; foreign
  providers remain unconfigured in the active runtime. Credentials can be privately
  staged, but must not be activated as proof of legal approval. See
  [runtime configuration](../operations/vps-runtime.md#auth-configuration).
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
