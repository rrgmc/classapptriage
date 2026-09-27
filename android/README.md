# ClassApp Triage (Android)

Native Android app (Kotlin, Jetpack Compose, Material 3, Android 8.0+ / minSdk 26)
that lists a ClassApp inbox, splits it into **Important** and **Routine**
messages with editable rules, and deletes or marks routine messages as read so
only the important ones remain.

It implements the specs in [`docs/`](../docs):

| Android | Spec | Notes |
| --- | --- | --- |
| `api/Queries.kt`, `api/ClassAppClient.kt` | [`docs/API.md`](../docs/API.md) | GraphQL documents, endpoint, query params and error handling (the `errors` envelope is decoded before the HTTP status). |
| `triage/Triage.kt` | [`docs/TRIAGE.md`](../docs/TRIAGE.md) | Rule semantics and JSON format. |

Keep the code and the specs in sync.

## Features

- **Login** with email or phone + password. If the account requires a one-time
  code, it is sent automatically and the app asks for it. Only the access token
  (and refresh token) is stored, encrypted with an AES-GCM key held in the
  Android Keystore; the app never saves the password itself. Passwords can
  be kept in the system password manager (Credential Manager, e.g. Google
  Password Manager): the login screen offers saved ones, and after a password
  login the system asks whether to save it. The last email/phone is
  pre-filled. App data is excluded from
  backups. The app never asks for an API key or token: the login form is
  shown only when no token is stored (first run, after *Log out*, or after the
  server rejects the stored token with HTTP 401 — then it says the session
  expired, prefills the last email/phone and keeps the selected inbox).
- **Inbox picker** from the logged-in user's entities (remembered; switchable
  from the menu).
- **Message list** with Important / Routine tabs, showing sender, label, age (hours or days)
  and the rule that classified each message. Loads the most recent 200
  messages by default (configurable), with "load older messages" and
  pull-to-refresh.
- **Message view**: tap a message to open it full screen (subject, body with
  links, attachments, images inline). Opening it marks it read; the top bar
  can mark it unread again (it stays unread while the screen is open) or
  delete it.
- **Actions**: on the Routine tab, *Mark all read* and *Delete all* (with
  confirmation). Long-press any message to multi-select, then mark read,
  mark unread, delete, or create a rule from its sender/label.
- **Rules editor**: add, edit, reorder and delete Important and Routine rules;
  label choices come from the organization's labels. Rules can be exported,
  imported, shared or copied as JSON in the portable format of
  [`docs/TRIAGE.md`](../docs/TRIAGE.md). *Reset to defaults* restores the built-in rules.
- **Notifications** for new Important messages, checked about once an hour.
  They are on by default and can be turned off (or back on) with *Notify new
  important messages* in the messages screen's menu, or per channel in the
  system notification settings. On Android 13+ the app asks for the notification
  permission once; declining turns the feature off. The check is a WorkManager
  periodic job, so Android batches it with other background work and defers it
  in Doze. It runs only with a network connection and when the battery isn't
  low, and each run fetches a single page (50) of the inbox's unread folder
  (`UNREAD_BY_NTF`). Only messages the current rules classify as Important,
  and not sent by the inbox itself, are notified, each once. The first check
  after enabling (or after switching inbox) only records what is already
  unread. A notification disappears at the next check once its message has
  been read elsewhere; tapping it opens the message. Logging out cancels the
  check.

- **Languages**: English and Brazilian Portuguese, following the device
  language (on Android 13+ it can also be chosen per app in system settings).
  The API `locale` follows the app language, so server messages match.
  Strings live in `res/values/strings.xml` and `res/values-pt-rBR/strings.xml`.

Classification follows [`docs/TRIAGE.md`](../docs/TRIAGE.md): a message is Routine only if a routine
rule matches and no important rule does; anything unmatched stays Important.
Deleting sets the ClassApp `DELETED` status for your user (as the web app does).

## Build

Requires JDK 17+ and the Android SDK (platform 35). Point Gradle at the SDK
with `ANDROID_HOME` or a `local.properties` containing `sdk.dir=...`.

```sh
cd android
./gradlew testDebugUnitTest   # unit tests (triage rules, client protocol)
./gradlew lintDebug
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/ClassAppTriage-<version>-debug.apk
./gradlew installDebug        # install on a connected device/emulator
```

### Release builds

`./gradlew assembleRelease` produces
`app/build/outputs/apk/release/ClassAppTriage-<version>-release.apk`, signed
with the key described by `android/keystore.properties` (git-ignored):

```properties
storeFile=classapptriage-release.jks
storePassword=...
keyAlias=classapptriage
keyPassword=...
```

Keep the keystore and its passwords safe: Android only installs an update
over an existing install when it is signed with the same key. Without
`keystore.properties` the release APK is built unsigned. Debug builds use
the application id suffix `.debug` and the committed `debug.keystore`, so
"ClassApp Triage (debug)" installs alongside the release app and debug APKs
built anywhere update each other.

Or open `android/` in Android Studio.

## Layout

```
app/src/main/java/com/rrgmc/classapptriage/
  api/        GraphQL client, queries, models
  triage/     rule engine (docs/TRIAGE.md)
  data/       TokenStore (encrypted session), RulesStore (rules.json), SettingsStore
  notify/     hourly Important-message check (WorkManager) and notifications
  ui/         Compose screens: login, entity picker, messages, rules
```
