# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Apps for the ClassApp (classapp.com.br) school-communication platform that split an inbox into Important and Routine messages. They talk to the undocumented GraphQL endpoint of the ClassApp web app (`https://web.classapp.com.br/graphql`).

- `android/`: native Android app (Kotlin, Compose, minSdk 26). See `android/README.md`.
- `ios/`: planned, not started yet.
- `docs/API.md`: the protocol spec shared by all apps. `docs/TRIAGE.md`: rule semantics and the portable rules JSON.

## Commands

```sh
# Needs JDK 17+ and Android SDK platform 35 (ANDROID_HOME or android/local.properties)
cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Conventions

- This repository is **public**. Never commit credentials, tokens, release keystores or `keystore.properties`. Never commit personal data either: real names, account/entity/message ids, school names, e-mails, phones. Use placeholders in docs and tests. The web `client_id` in `ClassAppClient.kt` / `docs/API.md` is ClassApp's public web-client id and is intentionally committed. So is `android/debug.keystore` (a shared debug-only key).
- Apps implement `docs/API.md` and `docs/TRIAGE.md`. When behavior changes, update the spec and every app so they stay in sync, and so rules files stay interchangeable across platforms.
- Any API behavior discovered while working (value formats, undocumented fields, error quirks) goes into `docs/API.md` §10, marked live-verified or schema-only.
- Android: `api/Queries.kt` holds the GraphQL documents; `triage/Triage.kt` is the reference triage implementation. User-visible strings live in `res/values/strings.xml` and `res/values-pt-rBR/strings.xml`; keep both updated.

## CI

`.github/workflows/android.yml` runs test, lint and a debug build on pushes to `master` and on pull requests, when they touch `android/**` or the workflow itself. It can also be dispatched manually. Pushing a `v*` tag also attaches the debug APK to that tag's GitHub release, creating the release if needed. Still run the build locally before pushing.
