# Peppa AI Messages

<img alt="Peppa AI Messages icon" src="reference-docs/graphics/icon.png" width="120" />

A personal fork of [Fossify Messages](https://github.com/FossifyOrg/Messages) (SMS app for Android)
with an **AI spam filter**. New SMS are checked by your own HTTPS API (for example one that asks
DeepSeek). Spam and ads go **silently** into a separate **Spam** folder: no notification, and they never
enter the phone's SMS database, so other SMS apps never see them.

Everything else is Fossify Messages: dual SIM, long SMS, blocking, archive, recycle bin,
export / import of all messages (JSON, and the XML of "SMS Backup & Restore").

- Installed app id: `com.peppasoft.smsman` (the Kotlin package is still `org.fossify.messages`), so it
  can be installed next to the original Fossify Messages. The id stays the same after the rename, so
  updates install over older versions and keep the spam folder and settings.
- Not published in any store. Download the APK from
  [Releases](https://github.com/fatehiman/peppa-ai-messages/releases) and install it with ADB (see below).
- The original README is in [reference-docs/fossify-upstream-README.md](reference-docs/fossify-upstream-README.md).

## What is added

| Where | What |
| --- | --- |
| Settings → **Spam filter (AI)** | On/off, **API endpoint URL**, **API token**, "Never check messages from my contacts" (on), "Never check messages that look like login codes" (on), **Test the connection** |
| Settings → **Manage allowed numbers** | Numbers or sender names that always go to the inbox and are never sent to the API. `0912…` also matches `+98912…`. "Not spam" adds the sender here |
| Settings → **Manage allowed keywords** | Words: a message that contains one always goes to the inbox, never to the API (for example `کد تایید`, a bank name) |
| Settings → **Old messages** | **Delete old messages automatically every day** (switch) and **Delete messages older than** (days, default 90). See below |
| Main menu → **Show the spam folder** | List of caught messages (sender, date, SIM 1/2, reason, e.g. `AI: 92%`, `Blocked keyword: …`). Tap one: read it, **Not spam** (moves it to the inbox, adds the sender to allowed numbers and tells the server) or **Delete**. Menu: **Delete all spam** |
| Conversation menu → **Report as spam** | Moves the received messages of the conversation into the spam folder, deletes the conversation, and tells the server the sender is a spammer |
| Settings → Notifications → **Show new messages as floating bubbles** | On by default, Android 11+. A new message also shows a floating chat bubble (like Google Messages). Tap it: the conversation opens in a small window over the current app, so that app stays open. Android must allow bubbles for the app (if not, turning the switch on offers to open that Android screen; on Samsung also Settings → Notifications → Advanced settings → Floating notifications → **Bubbles**). Works only when "Lock screen notification visibility" is "Sender and message". Spam-folder messages have no notification, so no bubble. Code: `getBubbleMetadata` in `NotificationHelper.kt`, `BubbleThreadActivity` (the conversation screen with the manifest flags bubbles need) |
| App | New name and icon ([reference-docs/graphics/icon.png](reference-docs/graphics/icon.png)). Fossify's "You are using a fake version of the app" warning is removed. Fossify Commons shows it in 3 places when the app id does not start with `org.fossify.` (randomly on about 1 in 51 screen opens, on every 100th start, and instead of "Customize colors" after 100 starts). Our `SplashActivity` does not extend `BaseSplashActivity`, and a build step in [build-logic/](build-logic/src/main/kotlin/RemoveFakeVersionWarning.kt) changes the library classes in every build: `showModdedAppWarning`, `fakeVersionCheck` and `FakeVersionCheck` get an empty body, and the "contains fossify" test always passes. The release APK has no copy of the text. After a Fossify Commons update, check: `unzip -p <apk> 'classes*.dex' \| grep -ac "You are using a fake"` must print `0` |

The spam folder is separate from Fossify's **Archived conversations** (that folder is only for
conversations you archive by hand).

### How a new SMS is handled (in this order)

"Allowed" rules come **before** "blocked" rules.

| # | Rule | Result |
| --- | --- | --- |
| – | Sender is in **Android's system block list** (Settings → Manage blocked numbers) | Android itself deletes the SMS before any app gets it (calls are blocked too). Nothing is saved. |
| 1 | Sender is a **contact** (setting, on by default) | Inbox, no API call |
| 2 | Sender is an **allowed number**, or the text has an **allowed keyword** | Inbox, no API call |
| 3 | Text looks like a login code (`OTP`, `رمز`, `کد تایید` … plus 4–8 digits; setting, on by default) | Inbox, no API call |
| 4 | Blocked number that reaches the app (for example a pattern), **blocked keyword**, or "block unknown numbers" + not a contact | Spam folder, no API call |
| 5 | Spam filter off, or no URL / token | Inbox |
| 6 | **API**: `spam: true` | Spam folder |
| 7 | API says not spam, error, no internet, or no answer in **8 seconds** | Inbox |

Spam folder = no notification, and the SMS is never written to the phone's SMS database.
**A message is never lost because of the API.**

### Delete old messages

A phone with many years of SMS makes every SMS app slow. Export your messages first
(Settings → Export messages), then:

- **Delete messages older than**: the number of days (1–36500, default 90).
- **Delete old messages automatically every day**: when on, messages older than that are deleted
  once a day **without asking** (the check runs when the app opens and when an SMS arrives, at most
  once per 24 hours).
- When you turn the switch on, or change the days while it is on, the app counts the old messages
  and asks: *"N messages are older than X days. Delete them now?"*
  - **Delete**: they are deleted now.
  - **Cancel**: nothing is deleted now, and the app warns that they will be deleted automatically in
    about 24 hours. Turn the switch off to keep them.
- Deleted: SMS and MMS (all folders: inbox, sent, drafts) and spam-folder messages older than the
  limit. Kept: **locked** messages and scheduled messages.

Code: [SpamFilter.kt](app/src/main/kotlin/org/fossify/messages/helpers/SpamFilter.kt) (`decide` = the rules above),
[SmsReceiver.kt](app/src/main/kotlin/org/fossify/messages/receivers/SmsReceiver.kt),
[KeywordList.kt](app/src/main/kotlin/org/fossify/messages/helpers/KeywordList.kt) (blocked / allowed lists),
[Spam.kt](app/src/main/kotlin/org/fossify/messages/extensions/Spam.kt) (move to / from the spam folder),
[OldMessages.kt](app/src/main/kotlin/org/fossify/messages/extensions/OldMessages.kt) (delete old messages),
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
3. On the PC: `adb install -r peppa-ai-messages-<version>.apk`
   (ADB installs are not affected by Google's developer verification rules for 2026/2027).
4. Open **Peppa AI Messages**, accept it as the **default SMS app**, allow the permissions.
5. Settings → Spam filter (AI): set the **API endpoint URL** and the **API token**, then **Test the connection**.
6. Send yourself a test SMS from another phone.

To go back to Samsung Messages / Google Messages: make that app the default SMS app again.
All normal SMS stay in the phone's SMS database. Messages in the spam folder are not there (that is
the point); delete them, or press "Not spam" on the ones you want to keep, before you uninstall.

## Build and release

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

Versions (`gradle.properties`): `VERSION_NAME` = upstream version + `-peppa.N` (e.g. `1.10.1-peppa.3`),
`VERSION_CODE` = upstream code × 100 + N (e.g. `2503`), so it always grows, also after an upstream merge.
Each release is a GitHub release with the tag `v<VERSION_NAME>` and the signed APK attached.

The launcher icon is made from `reference-docs/graphics/icon.png`: the speech bubble is cut out as the
adaptive-icon foreground (`res/drawable-nodpi/ic_launcher_foreground.png`, plus a monochrome copy for
themed icons) on the blue gradient `res/drawable/ic_launcher_background_peppa.xml`.

## Update from upstream Fossify

```bash
git fetch upstream
git merge upstream/main     # fix conflicts, build, test
```

The changes for this fork are kept small and marked with `sms-man` comments, so merges stay easy.

## License

GPL-3.0, same as Fossify Messages ([LICENSE](LICENSE)). This fork is based on the work of the
Fossify team and the original Simple Mobile Tools authors. It uses its own name and icon and is not
an official Fossify app.
