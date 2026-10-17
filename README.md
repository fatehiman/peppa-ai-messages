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

## Documentation

The app works without any server. Two optional features need a small HTTPS server of your own. The
author's servers are private, so these guides describe the API format, with examples and a minimal
server you can start from:

| Guide | For |
| --- | --- |
| [SPAM-API.md](SPAM-API.md) | **Spam filter (AI)**: `POST …/check` (is this SMS spam?) and `POST …/feedback` ("Report as spam" / "Not spam"). Request and answer format, errors, timeouts, curl examples, a minimal Python (FastAPI) server |
| [TELEGRAM-PICTURES-API.md](TELEGRAM-PICTURES-API.md) | **Telegram profile pictures**: `GET …/me` (connection test) and `GET …/phone/{+number}/photo?format=image`. Status codes and what the app does with them, curl examples, a minimal Python (FastAPI + Telethon) server, how to keep the Telegram account safe |

Other pages in this file: [what is added](#what-is-added) · [how a new SMS is handled](#how-a-new-sms-is-handled-in-this-order) ·
[delete old messages](#delete-old-messages) · [install on the phone](#install-on-the-phone-samsung-a54-or-any-android-8) ·
[build and release](#build-and-release) · [update from upstream](#update-from-upstream-fossify)

## What is added

| Where | What |
| --- | --- |
| Settings → **Spam filter (AI)** | Your own server: [SPAM-API.md](SPAM-API.md). On/off, **API endpoint URL**, **API token**, "Never check messages from my contacts" (on), "Never check messages that look like login codes" (on), **Wait for the API answer** (8, 10, 15, 20, 25 or 30 seconds, default 15; the time also covers connecting, which can be slow on mobile data), **Test the connection** (shows the answer time, for example `answer in 1.7 s`) |
| Settings → **Manage allowed numbers** | Numbers or sender names that always go to the inbox and are never sent to the API. `0912…` also matches `+98912…`. "Not spam" adds the sender here |
| Settings → **Manage allowed keywords** | Words: a message that contains one always goes to the inbox, never to the API (for example `کد تایید`, a bank name) |
| Settings → **Old messages** | **Delete old messages automatically every day** (switch) and **Delete messages older than** (days, default 90). See below |
| Main menu → **Show the spam folder** | List of caught messages (sender, date, SIM 1/2, reason, e.g. `AI: 92%`, `Blocked keyword: …`). Tap one: read it, **Not spam** (moves it to the inbox, adds the sender to allowed numbers and tells the server) or **Delete**. Menu: **Delete all spam** |
| Conversation menu → **Report as spam** | Moves the received messages of the conversation into the spam folder, deletes the conversation, and tells the server the sender is a spammer |
| Settings → Notifications → **Show new messages as floating bubbles** | On by default, Android 11+. A new message also shows a floating chat bubble (like Google Messages). Tap it: the conversation opens in a small window over the current app, so that app stays open. Android must allow bubbles for the app (if not, turning the switch on offers to open that Android screen; on Samsung also Settings → Notifications → Advanced settings → Floating notifications → **Bubbles**). Works only when "Lock screen notification visibility" is "Sender and message". Spam-folder messages have no notification, so no bubble. Code: `getBubbleMetadata` in `NotificationHelper.kt`, `BubbleThreadActivity` (the conversation screen with the manifest flags bubbles need) |
| Conversation → tap the **name at the top** | Opens the contact in the phone's contacts app. An unknown number opens "add to contacts", a group opens the conversation details. Code: `openTitleContact` in `ThreadActivity.kt` |
| Conversation → **delivery reports** | With Settings → "Enable delivery reports" on, **every** delivered sent message shows the double tick (Fossify shows it only under the last message). The last sent message still shows one tick until it is delivered |
| Conversation → select 1 message → ⋮ → **Properties** | Received message: **Sent at** (the time the sender sent it, from the SMS center) and **Received at** (the time it arrived on the phone). Sent message: **Sent at** and **Delivered at** (needs delivery reports). The times are read from the phone's SMS database (`date_sent`). "Sent at" of a received SMS is saved only for SMS received with this version or by an app that saves it (most do); messages moved back from the spam folder have no "Sent at" |
| Icons of **numbers that are not contacts** | Fossify draws the first letter of the name, so every `+98…` number got a **+** icon. Now, in this order: (1) the **Telegram profile picture** (see the next row), (2) the first letter of the **call log note** of the number (Samsung's Phone app lets you add a note to a number; it is saved in Samsung's call log table `content://logs/historys`, column `sec_memo`), (3) the **first digits** without the country code: `+989133419544` → `913`, `+98500…` → `500`. Contacts and text senders (`BANK`) keep the normal letter. Used in the conversation list, the conversation, notifications, bubbles, shortcuts and search. Code: `Avatars.kt`, `CallLogNotes.kt` |
| Settings → **Icons and profile pictures** | **Use the call log note of a number as its icon (Samsung)**: off by default; turning it on asks for the call log permission (`READ_CALL_LOG`). The notes are read again each time the main screen opens (at most once a minute). **Telegram profile pictures for numbers that are not contacts** and **… for contacts without a photo**: two switches, both off by default (a contact's own photo always wins). **Telegram API URL** (`https://telegram.peppasoft.com/api/v1`, project `E:\www\telegram-manager`; without `/phone/...` or `/me`; your own server: [TELEGRAM-PICTURES-API.md](TELEGRAM-PICTURES-API.md)), **Telegram API token**, **Only numbers that start with** (comma separated, default `+989` = all Iran mobiles; `+98500…` and short codes are never looked up) and **Test the connection** (`GET /me`) |
| Telegram pictures: when and how | Asked only when an SMS arrives and when a conversation is opened, never while scrolling the list (so Telegram does not see many lookups at once). One request at a time in the background (connect 20 s, answer 60 s), the screen never waits. `GET <url>/phone/<+number>/photo?format=image`. The picture is made smaller (at most 256 px, about 10–20 KB, so 1000 pictures ≈ 15 MB). A picture, or "no Telegram user / no picture" (404), is kept for **20 days**, then asked again. Errors are tried again after 1 hour; `429`/`503` pause all requests (`Retry-After`, else 10 min); `401`/`403` pause 1 hour. **Cleanup**: every time a picture is shown (list, conversation, notification) the number counts as "seen"; once a day the files of numbers not seen or asked for **90 days** are deleted, and such a number is downloaded again when it comes back. Files: `filesDir/telegram_avatars/<number>.jpg`, `.none` ("no picture"), `.used` (last seen). Changing the URL deletes them. Code: `TelegramAvatars.kt` |
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
| 7 | API says not spam, error, no internet, or no answer in time (**Wait for the API answer**, default 15 seconds) | Inbox |

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

## The spam API (short)

```http
POST /api/sms/check          Authorization: Bearer <token>
{"sender": "+98912...", "body": "SMS text"}
-> {"ok": true, "spam": true, "reason": "ai", "percent": 92}
```

Only `spam` (boolean) is required in the answer. Any error or timeout means "inbox". Feedback goes to
`…/feedback` next to the check URL. The full format, examples and a sample server are in
[SPAM-API.md](SPAM-API.md).

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
