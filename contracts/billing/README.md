# Billing contract v1 (`billing-v1`)

Checkout of Plus and Pro through the T-Bank hosted payment form, the order state machine, the signed bank
notification and the hand-over of a paid month to usage through `EntitlementInbox`. **Status: implemented** in
`app.mnema.learning.billing` by [#389](https://github.com/MattoYuzuru/Mnema/issues/389) (epic
[#79](https://github.com/MattoYuzuru/Mnema/issues/79)). Out of scope here: recurring payments and `Charge` (#390), the
profile «Подписка и оплата» page (#391), НПД receipts, refunds and reconciliation (#392).

| File | Content |
|---|---|
| [`billing.json`](billing.json) | `POST /api/billing/checkout`, `GET /api/billing/orders/{orderId}`, `POST /api/billing/tbank/notifications`, the `Order` shape and statuses, examples |
| [`tbank/`](tbank) | Recorded T-Bank exchanges: `init-*`, `get-state-*`, `notification-*` (signed with the fixture terminal `1700000000000DEMO` / password `fixture$Pa55word`) and `token-vectors.json` (the two official examples) |

Authority: [AI layer product contract §5](../../docs/product/ai-layer-2026-10.md) for prices,
[usage contract](../usage/README.md) for entitlements, the [legal and payment launch status](../../docs/product/russia-legal-launch-checklist-2026.md)
for what may take real money, and the T-Bank internet-acquiring API: [Init](https://developer.tbank.ru/eacq/api/init),
[GetState](https://developer.tbank.ru/eacq/api/get-state), [Token](https://developer.tbank.ru/eacq/intro/developer/token),
[notifications](https://developer.tbank.ru/eacq/intro/developer/notification),
[statuses](https://developer.tbank.ru/eacq/intro/developer/operation-statuses) and
[TLS certificates](https://developer.tbank.ru/eacq/intro/certificates/) (read 2026-10-09).

## Model

- **Order** — one purchase attempt of one plan for one period, created by the server from the plan catalog
  (`allowances-v1.json` `priceRubPerMonth`) and the account's pending promo discount for that plan (whole rubles,
  half up). The `orderId` (UUID) is the bank `OrderId`; the bank `PaymentId` is stored when `Init` answers. Amounts are
  kopecks. No card data is ever received or stored: the hosted form belongs to the bank.
- **State machine** — `CREATED → PENDING → PAID | FAILED`, `PAID → REFUNDED`, and `REVIEW` for money an operator must
  reconcile: a confirmed payment that does not match the order (`AMOUNT_MISMATCH`), a partial refund of a payment that was
  never granted (`PARTIAL_REFUND`) or an order the bank still reports in progress 72 hours after its link (`STALE`).
  `PAID` is absorbing for granting: a later `REJECTED` never takes a month back, a repeated `CONFIRMED` grants nothing new.
  A bank `CONFIRMED` moves any `CREATED`, `PENDING` or `FAILED` order to `PAID` (money taken must grant access).
  `REJECTED`, `AUTH_FAIL`, `CANCELED`, `DEADLINE_EXPIRED` and `ATTEMPTS_EXPIRED` fail an open order; an `Init` the bank
  refused fails it `INIT_REFUSED` (the next checkout opens a new `OrderId`). Wire statuses are in `billing.json`; `CREATED`
  is reported as `PENDING`.
- **Only the bank confirms.** The entitlement is granted only after `GetState` (signed request, TLS to the bank)
  reports `CONFIRMED` with the order's `TerminalKey`, `OrderId` and `Amount`. A notification is a trigger, verified
  first by `TerminalKey` and `Token`; the return URL and its query string are never trusted. Three triggers ask
  `GetState`: a verified notification for any order the bank may still change (`CREATED`, `PENDING`, `FAILED`, `PAID`
  for a refund), the return page reading a `PENDING` order (rate-limited per order) and the reconciler for unfinished
  orders whose notification did not arrive and, for 72 hours, failed orders with a bank payment.
- **Grant** — in the transaction that marks the order `PAID`: `EntitlementInbox.accept` with
  `snapshotId = billing:{orderId}`, `source BILLING`, the plan, `periodStart` = the later of the confirmation time and
  the end of the account's latest paid period of the same plan (a second payment extends, it does not overlap),
  `periodEnd = periodStart + 1 calendar month (Europe/Moscow)`, `validUntil = periodEnd`. The promo discount the order
  used is consumed in the same transaction.
- **Idempotency** — `createCheckout` uses the platform command receipts (`Idempotency-Key`). A notification is recorded
  per `(PaymentId, Status)` in the transaction that applies its `GetState` answer, so it counts as handled only once
  applied: a retry after a 503 is processed in full, a replay of a handled one asks the bank at most once per refresh
  interval. The order row lock serializes concurrent notifications, the return page and the reconciler. The notification
  answer `OK` is written only after the transaction committed; its `GetState` takes at most 5 s, inside the bank's wait.
- **Token** — SHA-256 (lowercase hex) of the values of the root scalar members plus `Password`, sorted by key and
  concatenated; nested objects and arrays (`DATA`, `Data`, `Receipt`, `Params`) and `null` members are left out;
  booleans are `true`/`false`, numbers their JSON text. Incoming tokens are compared in constant time.
- **Trust** — the bank presents a certificate chain to the Russian Trusted Root CA (Минцифры) and, during its
  certificate transition, to the TrustAsia TLS RSA Root CA; T-Bank asks merchants to trust both. Only the T-Bank client
  trusts them, through its own in-memory trust store built from the bundled PEMs (SHA-256
  `D2:6D:2D:02:31:B7:C3:9F:92:CC:73:85:12:BA:54:10:35:19:E4:40:5D:68:B5:BD:70:3E:97:88:CA:8E:CF:31` for the Russian root,
  `06:C0:8D:7D:AF:D8:76:97:1E:B1:12:4F:E6:7F:84:7E:C0:C7:A1:58:D3:EA:53:CB:E9:40:E2:EA:97:91:F4:C3` for TrustAsia, from
  TrustAsia's repository and the Mozilla CCADB; both checked at start); the JVM default trust store is unchanged.
  `GetState` answers are not signed, so these trust anchors (without a leaf or intermediate pin) are the only
  authenticity control of the bank's answer — an accepted risk of B1.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `learning.billing.checkout` (`MNEMA_BILLING_CHECKOUT`) | `OFF` | `OFF`, `TESTERS` (only `learning.billing.testers`) or `ON`. Real money stays `OFF` until the bank/legal/receipt gates of the launch checklist pass |
| `learning.billing.testers` (`MNEMA_BILLING_TESTER_ACCOUNT_IDS`) | empty | Comma-separated account UUIDs allowed to check out in `TESTERS` |
| `learning.billing.tbank.terminal-key` (`MNEMA_TBANK_TERMINAL_KEY`) | empty | Terminal key; without it and the password checkout is `NOT_CONFIGURED` and notifications answer 403 |
| `learning.billing.tbank.password-base64` (`MNEMA_TBANK_PASSWORD_BASE64`) | empty | Terminal password, base64 of its UTF-8 bytes (bank passwords may contain `$`, which the production configuration channel forbids) |
| `learning.billing.public-base-url` (`MNEMA_PUBLIC_BASE_URL`) | empty | `https://mnema.app`: builds `NotificationURL` and the return URL |
| `learning.billing.tbank.base-url` | `https://securepay.tinkoff.ru/v2` | Bank API |
| `learning.billing.payment-ttl` | `PT1H` | `RedirectDueDate` and the order's `expiresAt` |
| `learning.billing.connect-timeout`, `learning.billing.request-timeout` | `PT5S`, `PT15S` | `Init` and the `GetState` of the return page and the reconciler |
| `learning.billing.notification-timeout` | `PT5S` (at most) | The whole `GetState` a notification makes, connect included: the bank waits about 10 s for `OK` |

The terminal must be one-stage (`PayType=O`, no separate `Confirm`) and have no online cash register attached: the owner
is on НПД and receipts go to «Мой налог» (#392), so `Init` carries no `Receipt`.
