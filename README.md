# SMS Man

A personal fork of [Fossify Messages](https://github.com/FossifyOrg/Messages) (SMS app for Android)
with an **AI spam filter**. New SMS are checked by your own HTTPS API (for example one that asks
DeepSeek). Spam and ads go **silently** into a separate **Spam** folder: no notification, and they never
enter the phone's SMS database, so other SMS apps never see them.

Everything else is Fossify Messages: dual SIM, long SMS, blocking, archive, recycle bin,
export / import of all messages (JSON, and the XML of "SMS Backup & Restore").

- Installed app id: `com.peppasoft.smsman` (the Kotlin package is still `org.fossify.messages`), so it
  can be installed next to the original Fossify Messages.
- Not published in any store. Install the APK with ADB (see below).
- The original README is in [reference-docs/fossify-upstream-README.md](reference-docs/fossify-upstream-README.md).

## What is added

| Where | What |
| --- | --- |
| Settings → **Spam filter (AI)** | On/off, **API endpoint URL**, **API token**, "Never check messages from my contacts" (on), "Never check messages that look like login codes" (on), **Test the connection**, clear the "Not spam" list |
| Main menu → **Show the spam folder** | List of caught messages (sender, date, SIM 1/2, reason, e.g. `AI: 92%`). Tap one: read it, **Not spam** (moves it to the inbox and tells the server) or **Delete**. Menu: **Delete all spam** |
| Conversation menu → **Report as spam** | Moves the received messages of the conversation into the spam folder, deletes the conversation, and tells the server the sender is a spammer |

### How a new SMS is handled

1. The normal Fossify filters run first (blocked numbers, blocked keywords, block unknown numbers).
2. The spam check is **skipped** (the SMS goes to the inbox) when:
   the filter is off or the URL/token is empty, the sender is a contact, the text looks like a
   login / verification code (`OTP`, `رمز`, `کد تایید` … plus 4–8 digits; `کد تخفیف` is *not* a code),
   or you marked this sender "Not spam" before.
3. Otherwise the app calls the API and waits **at most 8 seconds**.
4. `spam: true` → saved in the Spam folder only. Anything else (not spam, error, timeout, no
   internet) → normal inbox with a notification. **A message is never lost because of the API.**

Code: [SpamFilter.kt](app/src/main/kotlin/org/fossify/messages/helpers/SpamFilter.kt),
[SmsReceiver.kt](app/src/main/kotlin/org/fossify/messages/receivers/SmsReceiver.kt) (`isSpam`),
[Spam.kt](app/src/main/kotlin/org/fossify/messages/extensions/Spam.kt) (move to / from the spam folder),
[SpamMessagesActivity.kt](app/src/main/kotlin/org/fossify/messages/activities/SpamMessagesActivity.kt),
Room table `spam_messages` (database version 12, `MIGRATION_11_12` in
[MessagesDatabase.kt](app/src/main/kotlin/org/fossify/messages/databases/MessagesDatabase.kt)).

## The spam API (contract)

The app works with any server that follows this contract.

```http
POST /api/sms/check
Authorization: Bearer <token>
Content-Type: application/json

{"sender": "+98912...", "body": "SMS text"}
```

```json
{"ok": true, "spam": true, "reason": "ai", "percent": 92}
```

Only `spam` (boolean) is required in the answer. `reason` (`sender` | `phrase` | `ai` | `none`) and
`percent` (0–100 or `null`) are shown in the spam folder. Any non-2xx status means "not spam".

Feedback goes to the URL `feedback` next to the check URL (`.../api/sms/check` → `.../api/sms/feedback`):

```json
{"sender": "+98912...", "body": "...", "spam": false}
```

The author's server is `https://notes.peppasoft.com/api/sms/check` (project `notes`, file
`spam-api-manual-usage.md` there). The token is private: ask the server owner.

## Install on the phone (Samsung A54 or any Android 8+)

1. **Back up first.** In your current SMS app export everything (for example with "SMS Backup & Restore").
2. On the phone: Settings → About phone → Software information → tap **Build number** 7 times →
   Developer options → turn on **USB debugging**. On Samsung also turn off
   **Settings → Security and privacy → Auto Blocker** (it blocks USB installs). You can turn it on again later.
3. On the PC: `adb install -r sms-man-<version>-release.apk`
   (ADB installs are not affected by Google's developer verification rules for 2026/2027).
4. Open **SMS Man**, accept it as the **default SMS app**, allow the permissions.
5. Settings → Spam filter (AI): set the **API endpoint URL** and the **API token**, then **Test the connection**.
6. Send yourself a test SMS from another phone.

To go back to Samsung Messages / Google Messages: make that app the default SMS app again.
All normal SMS stay in the phone's SMS database. Messages in the SMS Man spam folder are not
there (that is the point); delete them, or press "Not spam" on the ones you want to keep, before
you uninstall.

## Build

Needs JDK 17 and the Android SDK (platform 36).

```bash
# local.properties (not in git):  sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
export JAVA_HOME=".../jdk-17"
./gradlew assembleFossDebug      # app/build/outputs/apk/foss/debug/   (app id com.peppasoft.smsman.debug)
./gradlew assembleFossRelease    # app/build/outputs/apk/foss/release/ (needs keystore.properties)
```

Release signing reads `keystore.properties` in the project root (not in git; format in
`keystore.properties_sample`). **Keep the keystore and its password safe**: an update must be signed
with the same key, otherwise you have to uninstall the app (and lose the spam folder) first.

## Update from upstream Fossify

```bash
git fetch upstream
git merge upstream/main     # fix conflicts, build, test
```

The changes for this fork are kept small and marked with `sms-man` comments, so merges stay easy.

## License

GPL-3.0, same as Fossify Messages ([LICENSE](LICENSE)). This fork is based on the work of the
Fossify team and the original Simple Mobile Tools authors.
