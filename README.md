# FinReader

An Android app that reads payment notifications on my phone (Twint, Revolut, credit
card, e-banking) and posts them as **pending transactions** to my self-hosted
finance web app. Nothing lands in the ledger automatically — every row still has to
be confirmed on the web app's `/pending` page.

Personal use, sideloaded APK. Not intended for the Play Store.

## How it works

```
notification  ->  allowlisted app?  ->  captured  ->  rules  ->  outbox  ->  POST /api/public/pending-transactions
                       (Apps tab)      (Inbox tab)   (Rules tab)  (WorkManager, retries offline)
```

1. **Apps** — pick which apps may be read at all. Anything from another package is
   dropped before it is stored.
2. **Inbox** — captured notifications, with what each one matched. This is the
   learning mode: the app ships with zero rules, you look at real notifications and
   turn them into rules.
3. **Rules** — a regex per notification shape, with named groups
   (`(?<amount>…)`, `(?<merchant>…)`, `(?<currency>…)`) mapped onto a target account,
   category, description and note. The rule editor tests live against past captures
   and shows the exact payload that would be posted.
4. **Settings** — server URL and API token, auto-post on/off, feedback notifications,
   undo window, retention, rules export/import as JSON.

## Setup on the phone

1. Install the APK.
2. **Settings → base URL + API token.** Create the token in the web app under
   Settings → API Tokens. "Save & test" hits `GET /api/public/pending-transactions`.
3. "Refresh accounts & categories" to fill the pickers.
4. Grant notification access: Android Settings → Notifications → Device & app
   notifications → FinReader. The app links you there and shows the current state.
5. **Apps tab** — enable Twint / Revolut / your card and bank apps.
6. Make a payment, open **Inbox**, hit *Create rule*, adjust the pre-filled
   pattern, pick the account, save. Back in the Inbox, *Run rules again* re-runs
   the rules against that stored notification, so a capture that arrived before
   its rule existed still gets posted.

Each Inbox card also shows what cash-flow did with the transaction — *open in
cash-flow*, *accepted*, *rejected* — with a button that jumps straight to
`/pending` or, once accepted, to the entry in edit mode. *Check what cash-flow
did with them* refreshes those labels; the background sync does it too.

*Run rules again* works on an already-posted transaction as well: the pending
entry is deleted in cash-flow and recreated if a rule still matches. One you
have already accepted is off limits — undo it in cash-flow first.

Every named group of the pattern is a `{placeholder}` in the description and note
templates. Add modifiers after a colon when the value has to survive as a hashtag —
`#{merchant:d}` turns "Coop City" into `#Coop-City`. The full list is in
[docs/DESIGN.md](docs/DESIGN.md#placeholder-modifiers).

## When something goes wrong

**Settings opens with the running build** — `FinReader 0.1.5 (6)` and the commit
it was built from — so "did that APK actually install?" has an answer on the
phone. A `+` after the commit means the build carried uncommitted changes.
`scripts/build-apk.sh test` compiles and tests but packages nothing; only
`assembleRelease` / `assembleDebug` produce an APK.

**Settings → Diagnostics → Show report.** An uncaught exception is written to
`crash.log` in app storage before the process dies, so the trace is still there
after the restart; the report adds device/version info and the recent logcat of
this process. *Copy* or *Share* hands the whole thing over in one piece.

## Privacy

- Only notifications from apps you explicitly enabled are read or stored.
- Notification text never leaves the phone except in the POST to your own server
  (`external_info`, so you can see the original text next to the pending row).
- The API token lives in `EncryptedSharedPreferences`, is excluded from the rules
  export and from Android cloud backup (`allowBackup=false`).
- No `QUERY_ALL_PACKAGES`; the app queries launcher activities only, to draw the
  app list.
- Captures are purged after the retention window (default 30 days).

## Building

Everything Android-specific lives in a container, so the host needs nothing but
Docker.

```bash
scripts/build-apk.sh                  # debug APK
scripts/build-apk.sh test             # unit tests
scripts/build-apk.sh assembleRelease  # release APK
scripts/build-apk.sh bash             # shell in the build container
```

Output lands in `app/build/outputs/apk/`. The Gradle cache is a named Docker volume
(`finreader-gradle`), so only the first build is slow.

VS Code / Cursor users can instead "Reopen in Container" — `.devcontainer/` holds
the same image.

### Signing

Unsigned-config builds fall back to the debug key, which installs fine but cannot
upgrade an install made with a real release key. To sign properly:

```bash
keytool -genkeypair -v -keystore finreader.jks -alias finreader \
        -keyalg RSA -keysize 4096 -validity 10000
```

Then either drop a `keystore.properties` next to `settings.gradle.kts`:

```properties
storeFile=/absolute/path/finreader.jks
storePassword=…
keyAlias=finreader
keyPassword=…
```

or export `FINREADER_KEYSTORE`, `FINREADER_KEYSTORE_PASSWORD`, `FINREADER_KEY_ALIAS`,
`FINREADER_KEY_PASSWORD`. Keep the keystore out of the repo — `*.jks` is gitignored.

### CI

`.github/workflows/build.yml` runs the tests and uploads a release APK artifact on
every push to `main`; pushing a `v*` tag also attaches the APK to a GitHub release.
Add these repository secrets to get a properly signed build:

| Secret | Value |
| --- | --- |
| `FINREADER_KEYSTORE_BASE64` | `base64 -w0 finreader.jks` |
| `FINREADER_KEYSTORE_PASSWORD` | keystore password |
| `FINREADER_KEY_ALIAS` | `finreader` |
| `FINREADER_KEY_PASSWORD` | key password |

## Docs

[`docs/DESIGN.md`](docs/DESIGN.md) — the agreed requirements, data model and the
reasoning behind the parts that are not obvious.
