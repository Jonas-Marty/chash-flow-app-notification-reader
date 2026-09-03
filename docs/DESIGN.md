# FinReader — Android notification → cash-flow-jm bridge

Personal-use Android app that reads payment notifications (Twint, Revolut, credit
card, e-banking), extracts amount + description, and posts them as **pending
transactions** to the cash-flow-jm web app for confirmation.

Sideloaded APK only. No Play Store, no analytics, no third-party backend.

---

## 1. Backend contract (already exists — no server changes needed)

Base URL: configured in-app, e.g. `https://<host>`.
Auth: `Authorization: Bearer <token>` — token generated in cash-flow-jm
Settings → API Tokens (hashed into `api_tokens`, revocable).

| Method | Path | Use |
|---|---|---|
| POST | `/api/public/pending-transactions` | Create a pending transaction |
| GET  | `/api/public/pending-transactions?status=pending` | Connection test / show what is waiting |
| GET  | `/api/public/accounts` | Populate account picker in rule editor |
| GET  | `/api/public/categories` | Populate optional category picker |

### POST payload

```jsonc
{
  "source_account_id": "<uuid>",          // required
  "amount": 12.50,                        // required, > 0, 2 decimals
  "type": "expense",                      // expense | income | transfer (default expense)
  "occurred_on": "2026-09-03",            // local date of the notification
  "category_id": "<uuid>|null",           // optional
  "description": "Coop Luzern",           // <= 500 chars
  "external_source": "twint",             // <= 120 chars, shown as badge in web UI
  "external_ref": "<sha256 hex, 32 chars>",// <= 200 chars, idempotency key
  "external_info": "Title — full notification text"  // <= 2000 chars, raw text for review
}
```

Responses: `201` `{pending_transaction}` · `200` `{pending_transaction, deduplicated:true}`
· `400` invalid · `401` bad token · `404` unknown account/category · `500`.

Nothing reaches the real ledger: rows land in `pending_transactions` and are
confirmed manually on the web app's `/pending` page. A wrong regex therefore
costs a rejection click, not a corrupted ledger.

---

## 2. Data flow

```
NotificationListenerService.onNotificationPosted
  └─ monitored-app allowlist  ──► not listed? dropped immediately, never stored
       └─ Room: captured_notifications (raw title/text/bigText/postedAt)
            └─ rule engine (first enabled rule for that package that matches)
                 ├─ no match  → stays UNMATCHED in the Inbox (source material for a new rule)
                 ├─ excluded  → IGNORED
                 ├─ duplicate → DUPLICATE (local 120 s window, see §5)
                 └─ match     → build payload → Room outbox (QUEUED)
                                  └─ WorkManager "outbox-sync"
                                       └─ POST → POSTED / DEDUPED / FAILED
                                            └─ optional phone notification (+ Undo)
```

Offline, airplane mode, dead server: the outbox retains everything and
WorkManager retries with exponential backoff. Nothing is lost, nothing is
double-posted (server-side idempotency on `external_ref`).

---

## 3. Data model (Room)

**`monitored_app`** — `packageName` (PK), `appLabel`, `enabled`.
Only notifications from enabled packages are processed **or stored**.

**`captured_notification`** — `id`, `packageName`, `appLabel`, `notificationKey`,
`postedAt`, `title`, `text`, `bigText`, `subText`, `matchState`
(UNMATCHED | MATCHED | IGNORED | DUPLICATE), `matchedRuleId`, `outboxId`.
Retention: 30 days or 1000 rows, whichever comes first, purged by a daily worker.
Serves the Inbox screen and the rule tester.

**`rule`**
```kotlin
id, name, enabled, priority            // lower priority evaluated first
packageName                            // which app this rule applies to
titlePattern: String?                  // optional regex on the title
textPattern: String                    // regex with named groups over "title \n text"
excludePattern: String?                // if it matches → ignore the notification
amountGroup   = "amount"               // named group names
merchantGroup = "merchant"
currencyGroup = "currency"
numberFormat: AUTO | SWISS | EU        // 1'234.50 vs 1.234,50
defaultCurrency = "CHF"
txType: EXPENSE | INCOME | FROM_PATTERN
incomePattern: String?                 // FROM_PATTERN: matches → income, else expense
sourceAccountId                        // uuid from /api/public/accounts
categoryId: String?                    // optional default
descriptionTemplate = "{merchant}"     // {merchant} {amount} {currency} {title} {text}
noteTemplate: String?
autoPost = true                        // false → capture only, post via manual tap
```

**`outbox_item`** — `id`, `capturedId`, `ruleId`, `payloadJson`, `externalRef`,
`state` (QUEUED | SENDING | POSTED | DEDUPED | FAILED_RETRY | FAILED_PERMANENT),
`attempts`, `lastError`, `remotePendingId`, timestamps.

**`account_cache` / `category_cache`** — mirrors of the two GET endpoints so the
rule editor works offline. Refreshed on demand and at most hourly on app open.

Settings (EncryptedSharedPreferences): `baseUrl`, `apiToken`, `autoPostEnabled`,
`feedbackNotificationsEnabled`, `retentionDays`.

---

## 4. Parsing

Match target is `title + "\n" + (bigText ?: text)`, whitespace-normalized.

Amount normalization handles the formats that actually show up here:

| Input | Parsed |
|---|---|
| `CHF 12.50` | 12.50 |
| `1'234.50` | 1234.50 |
| `EUR 12,50` | 12.50 |
| `1.234,56` | 1234.56 |
| `-CHF 5.00` | 5.00 (sign ignored; direction comes from the rule) |

`AUTO` decides Swiss vs EU by which separator appears last. Result is rounded to
2 decimals and must be > 0, matching the server's zod schema.

### Currency — no FX conversion

The amount is posted exactly as captured; the app never converts. Which figure
gets posted is the rule's decision, made by where `(?<amount>…)` sits in the
pattern:

- **Revolut, showing both currencies** (`€12.50 spent · CHF 11.80`): put
  `(?<amount>…)` on the CHF figure when the target account is the CHF one, and
  capture the other side into any extra named group — every named group of the
  pattern is available as a `{placeholder}` in the description and note
  templates, e.g. note `Original: {origCurrency} {origAmount}`.
- **A EUR-only notification against a EUR account**: point the rule at that
  account and the EUR amount is posted as-is. Nothing special is needed.
- **Parsed currency ≠ the target account's currency**: still posted as-is, and
  `Original currency: EUR` is appended to the note so the mismatch is visible on
  the `/pending` page. This is the "you pointed the rule at the wrong account"
  warning, not a conversion.

### Placeholder modifiers

A placeholder may carry modifiers after a colon, applied left to right:
`{merchant:d}`, `{merchant:dl}`. They exist because a value containing a space
breaks a hashtag — `#{merchant}` on "Coop City" yields the tag `#Coop` followed
by a stray word.

| Modifier | Effect | `Café & Müller` becomes |
| --- | --- | --- |
| `d` | spaces to dashes | `Café-&-Müller` |
| `u` | spaces to underscores | `Café_&_Müller` |
| `c` | CamelCase, no spaces | `Café&Müller` |
| `l` | lowercase | `café & müller` |
| `a` | drop punctuation and accents | `Cafe Muller` |

`{merchant:ad}` — the usual choice for a tag — gives `Cafe-Muller`. An unknown
modifier letter is ignored rather than failing the rule, since the editor
previews the result before anything is posted.

`occurred_on` = local date of `postedAt`.

---

## 5. Deduplication

Android re-posts the same notification when it updates, and the outbox retries.
Two layers:

1. **Local**: same package + same amount + same normalized text within 120 s →
   marked DUPLICATE, not enqueued.
2. **Server**: `external_ref = sha256("<package>|<occurred_on>|<amount_cents>|<normalized_text>")`
   truncated to 32 hex chars, plus a `-N` suffix counting how many times that
   same base has already been enqueued. The endpoint returns the existing row
   with `deduplicated: true` instead of inserting a second one.

**Why keep the key at all** (it was questioned, fairly): layer 1 does not cover
the case that actually costs you money — a POST that times out on a flaky mobile
connection *after* the server committed the row. The outbox cannot tell that
apart from a POST that never arrived, so it retries, and without `external_ref`
you get two pending rows for one payment. With it, the retry comes back
`deduplicated: true` and the outbox marks the item DEDUPED.

The sequence suffix removes the usual objection to idempotency keys: two genuine
coffees at the same kiosk for the same amount on the same day produce
`…-0` and `…-1`, so they are two rows, as they should be. Nothing legitimate is
ever collapsed.

Cost if it were removed: one field in the payload. Kept.

---

## 6. Screens

1. **Setup** — base URL, API token, *Test connection*, grant notification access
   (deep link to system settings), optional battery-optimization exemption.
2. **Monitored apps** — searchable list of installed apps with launcher icons;
   check the ones to process. Nothing outside this list is ever read or stored.
3. **Rules** — list per app; editor with account/category dropdowns (from cache),
   regex fields, and a **live tester**: pick any captured notification and see
   the extracted amount / merchant / description and the exact payload that
   would be posted. Also "create rule from this notification" straight from the Inbox.
4. **Inbox** — last captured notifications with state chips (posted, pending,
   unmatched, failed, duplicate); actions: retry, post manually, create rule,
   delete, view raw text.
5. **Settings** — auto-post master switch, feedback notifications on/off
   (they said: useful at first, noisy later), retention, export/import rules as
   JSON via the system file picker, export captured notifications for debugging.

Feedback notification (when enabled): `Captured CHF 12.50 · Coop Luzern` with an
**Undo** action.

**Undo works before sending, not after.** The public API exposes only
`GET`/`POST` on `pending-transactions` — there is no DELETE or PATCH — so the app
cannot retract a row it has already posted without a server change, and the whole
point of this design is that the server needs none. Instead the outbox item is
held for a short window (`notBefore = now + undoWindow`, default 20 s, and only
while feedback notifications are on) before the worker may send it. Undo inside
that window cancels the item and marks the capture IGNORED; after it, the
notification action reports "Already sent to the web app — reject it on the
Pending page instead."

Turning feedback notifications off sets the window to zero: nothing to undo from,
so nothing is delayed.

---

## 7. Permissions

| Permission | Why |
|---|---|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | the whole point; granted manually in system settings |
| `INTERNET` | POST to the API |
| `POST_NOTIFICATIONS` | own feedback notifications (Android 13+) |
| `<queries>` launcher intent | list installed apps for the picker (avoids `QUERY_ALL_PACKAGES`) |

No Accessibility service, no screen reading, no storage permission, no location.

---

## 8. Tech

Kotlin 2.2 · Jetpack Compose (Material 3) · Room · WorkManager ·
OkHttp + kotlinx.serialization · manual DI (`AppContainer` — the graph is a
dozen objects, Hilt would be more ceremony than help) · EncryptedSharedPreferences
for the token · minSdk 29 (Android 10) · targetSdk/compileSdk 36 · single module.

The toolchain (JDK 17, Android SDK 36, build-tools 36.1.0) lives in
`.devcontainer/`, driven by `scripts/build-apk.sh`, so the host only needs Docker.

Unit tests cover amount normalization, rule matching, template rendering and
`external_ref` derivation against real captured samples.

Build: GitHub Actions on push/tag → release APK uploaded as a workflow artifact.
Release signing via a keystore stored in repo secrets; falls back to a debug
build if the secrets are absent.

---

## 9. Rollout

**Phase 1 — learning mode.** First install ships with zero rules. Monitored apps
are selected, notifications are captured but nothing is posted. Real Twint /
Revolut / credit-card / bank notification texts accumulate in the Inbox.

**Phase 2 — rules.** Each captured sample becomes a rule via "create rule from
this notification", tuned in the live tester. Auto-post enabled per rule.

**Phase 3 — routine.** Feedback notifications turned off; the phone posts
silently and confirmation happens in the web app's `/pending` page.

## 10. Out of scope for v1

Transfers between accounts · FX conversion · SMS/email ingestion ·
description → category learning · remote rule sync · multi-user.

## 11. Resolved along the way

- **Exact packages for the credit-card and e-banking apps** — resolved on-device
  via the monitored-apps picker; nothing hard-coded.
- **Repo** — `Jonas-Marty/chash-flow-app-notification-reader`.
- **Undo after posting** — not possible against the current API; downgraded to a
  pre-send hold (§6).
- **okhttp 5.x** — its Android artifact requires compileSdk 37, which AGP 8.13
  does not support; pinned to 4.12.0.
