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
| Settings → **Spam filter (AI)** | On/off, **API endpoint URL**, **API token**, "Never check messages from my contacts" (on), "Never check messages that look like login codes" (on), **Test the connection** |
| Settings → **Manage allowed numbers** | Numbers or sender names that always go to the inbox and are never sent to the API. `0912…` also matches `+98912…`. "Not spam" adds the sender here |
| Settings → **Manage allowed keywords** | Words: a message that contains one always goes to the inbox, never to the API (for example `کد تایید`, a bank name) |
| Main menu → **Show the spam folder** | List of caught messages (sender, date, SIM 1/2, reason, e.g. `AI: 92%`, `Blocked keyword: …`). Tap one: read it, **Not spam** (moves it to the inbox, adds the sender to allowed numbers and tells the server) or **Delete**. Menu: **Delete all spam** |
| Conversation menu → **Report as spam** | Moves the received messages of the conversation into the spam folder, deletes the conversation, and tells the server the sender is a spammer |

The spam folder is separate from Fossify's **Archived conversations** (that folder is only for
conversations you archive by hand).

### How a new SMS is handled (in this order)

| # | Rule | Result |
| --- | --- | --- |
| – | Sender is in **Android's system block list** (Settings → Manage blocked numbers) | Android itself deletes the SMS before any app gets it (calls are blocked too). Nothing is saved. |
| 1 | Sender is a blocked number that reaches the app (for example a pattern) | Spam folder, no API call |
| 2 | Text contains a **blocked keyword** | Spam folder, no API call |
| 3 | "Block messages from unknown numbers" is on and the sender is not a contact | Spam folder, no API call |
| 4 | Sender is a **contact** (setting, on by default) | Inbox, no API call |
| 5 | Sender is an **allowed number**, or the text has an **allowed keyword** | Inbox, no API call |
| 6 | Text looks like a login code (`OTP`, `رمز`, `کد تایید` … plus 4–8 digits; setting, on by default) | Inbox, no API call |
| 7 | Spam filter off, or no URL / token | Inbox |
| 8 | **API**: `spam: true` | Spam folder |
| 9 | API says not spam, error, no internet, or no answer in **8 seconds** | Inbox |

Spam folder = no notification, and the SMS is never written to the phone's SMS database.
**A message is never lost because of the API.**

Code: [SpamFilter.kt](app/src/main/kotlin/org/fossify/messages/helpers/SpamFilter.kt) (`decide` = the rules above),
[SmsReceiver.kt](app/src/main/kotlin/org/fossify/messages/receivers/SmsReceiver.kt), [KeywordList.kt](app/src/main/kotlin/org/fossify/messages/helpers/KeywordList.kt) (blocked / allowed lists),
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
