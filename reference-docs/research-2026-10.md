# Research: an own SMS app with an AI spam filter (2026-10-16)

Goal: a private SMS app for one phone (Samsung A54, 2 SIMs, SMS only, no MMS) that sends new SMS
to an own API (DeepSeek) and moves spam silently into a spam folder.

## Facts that shaped the design

- **Only the default SMS app can hide or move an SMS.** Since Android 4.4 other apps can read
  incoming SMS but cannot stop them. So the app must replace Samsung Messages / Google Messages.
- To be the default SMS app, Android requires an SMS receiver (`SMS_DELIVER`), an MMS receiver
  (`WAP_PUSH_DELIVER`), a "respond via message" service and a compose screen, even if MMS is unused.
- **There is no spam folder in the Android SMS database.** It must live in the app's own database.
  Chosen design: spam is never written to the system database, so no other app shows it.
- The default SMS app has full read/write access to the system SMS database (`content://sms`):
  insert, edit, delete, export, import. Google Messages and Samsung Messages read the same
  database, so switching back keeps all SMS. RCS chats are *not* in it (each app keeps them privately).
- Dual SIM: `SubscriptionManager` + `SmsManager` per subscription; each SMS has a `sub_id`.
- Long SMS: `sendMultipartTextMessage` sends several SMS parts, never MMS
  (Unicode/Persian: about 67 characters per part).
- Install without a store: Google's developer verification started in Brazil, Indonesia, Singapore
  and Thailand (2026-09-30) and goes global in 2027. **ADB installs are exempt.** There is also an
  "advanced flow" on the phone (setting, restart, 24 h wait). On Samsung, Auto Blocker must be off
  for USB installs.
- The spam API answers in about 1 s (DeepSeek); the SMS receiver must finish in about 10 s, so the
  app waits at most 8 s and then delivers the SMS normally (fail-open).

## Options that were compared

1. **Fork Fossify Messages** (GPL-3, Kotlin, active; v1.10.1 in 2026-10) — chosen. It already has
   dual SIM, export/import (also the XML of "SMS Backup & Restore"), blocking, archive, recycle bin.
2. Write an own app from zero — 3–6 weeks of work for the same result.
3. Fork QUIK (QKSMS successor, GPL-3, active) — older and heavier code (Realm, RxJava).
4. SpamBlocker (F-Droid, MIT) next to the stock app — only hides notifications, spam stays in the inbox.

No open-source SMS app with an LLM spam filter was found (2026-10).

## Sources

- https://9to5google.com/2026/08/18/google-gradually-rolling-out-androids-advanced-sideloading-ahead-of-developer-verification/
- https://www.androidauthority.com/how-to-bypass-android-developer-verification-sideloading-rules-3718002/
- https://www.androidauthority.com/android-developer-verification-rollout-sideloading-flow-3653395/
- https://www.fossify.org/apps/messages/
- https://f-droid.org/packages/org.fossify.messages
- https://f-droid.org/packages/dev.octoshrimpy.quik.fdroid/
