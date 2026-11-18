# Mjolnir

## What Mjolnir is

Mjolnir sends browser tabs and files between your phone and your Windows PC through a small relay server that you host yourself. The relay holds a delivery until the other device picks it up (at most 7 days), and the phone is woken by an optional Firebase "doorbell" push that carries no content.

Modules: `:protocol` (shared models), `:server` (the Ktor relay), `:relay-client`, `:desktop` (Windows app), `:android`.

## Run the relay locally

```
./gradlew :server:run
```

The relay listens on port 8080. Settings are environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | `8080` | HTTP port |
| `MJOLNIR_DB` | `./mjolnir.db` | SQLite database file |
| `MJOLNIR_FILES` | `./files` | Directory for uploaded files |
| `MJOLNIR_MAX_FILE_MB` | `100` | Per-file cap (can only lower the 100 MiB limit) |
| `MJOLNIR_MAX_STORAGE_MB` | `2048` | Total storage cap for unsent and undelivered files |
| `GOOGLE_APPLICATION_CREDENTIALS` | unset | Path to a Firebase service-account JSON; enables push |

Check it with `curl localhost:8080/health` (prints `ok`). Without Firebase credentials the log says `FCM disabled` and phones only get tabs while the app is open or on its periodic check.

To build and run the fat jar: `./gradlew :server:buildFatJar` then `java -jar server/build/libs/server-all.jar`.

## Deploy the relay to Fly.io

Run these from the `server/` directory after `./gradlew :server:buildFatJar` (the Dockerfile copies `build/libs/server-all.jar`).

1. `fly launch --copy-config --no-deploy` uses the included `fly.toml`. Pick a unique app name when asked.
2. `fly volumes create data --size 3` creates the persistent volume mounted at `/data`. It must hold the storage cap (2 GB by default) plus room for the database, so 3 GB is the minimum for the defaults. If you raise `MJOLNIR_MAX_STORAGE_MB`, make the volume bigger.
3. Optional push: put the Firebase service-account JSON on the volume and point the relay at it:
   ```
   fly ssh console -C "mkdir -p /data/secrets"
   fly ssh sftp shell      # then: put service-account.json /data/secrets/firebase.json
   fly secrets set GOOGLE_APPLICATION_CREDENTIALS=/data/secrets/firebase.json
   ```
   Alternatively store the JSON itself as a secret and write it to a file at startup. Never commit the JSON.
4. `fly deploy` builds the image and starts the relay. Check `https://<app>.fly.dev/health`.

Use the `https://<app>.fly.dev` address as the relay URL in the PC and phone apps. Relay URLs must be `https://` (plain `http://` only for localhost).

## Build the APK

The phone app is built from the `:android` module, which needs an Android SDK (platform 36, build-tools 36.0.0). Point Gradle at it with `ANDROID_HOME`, or `sdk.dir=/path/to/sdk` in `local.properties` (git-ignored). Without an SDK every other module still builds.

```
./gradlew :android:assembleDebug     # android/build/outputs/apk/debug/android-debug.apk
```

CI builds the same APK and uploads it as the `mjolnir-apk` artifact. To bake a default relay URL into the build, add `-Pmjolnir.relayUrl=https://<app>.fly.dev`. Debug builds also allow plain `http://` to `10.0.2.2`, `localhost` and `127.0.0.1` (for the emulator). Install with `adb install -r android-debug.apk` or copy the file to the phone. The app needs Android 10 (API 29) or newer.

## Enable phone notifications (Firebase)

Without this the relay still works; the phone simply is not woken immediately.

1. Create a project in the [Firebase console](https://console.firebase.google.com) and add an Android app with package name `app.mjolnir`.
2. Download `google-services.json` and place it in `android/` (it is git-ignored; do not commit it), then rebuild the APK. The Google services plugin is only applied when that file exists.
3. In Project settings, Service accounts, generate a new private key. This is the service-account JSON the relay uses (see the Fly.io step above, or set `GOOGLE_APPLICATION_CREDENTIALS` when running locally).
4. Restart the relay. The log should say `FCM enabled`. The phone registers its token with `PUT /v1/devices/me/push-token`, and each send then rings it with a data message `{"type":"inbox"}`.
5. Open the app once after installing the new APK: it fetches its FCM token and gives it to the relay (again whenever Firebase rotates it, and after the device re-registers).

What the phone does with what it receives:

- A push (or opening the app, or a check every 15 minutes when there is no push) drains the inbox in the background, even with the phone locked.
- Links appear on the *Caught* screen and in a `Caught … from {pc}` notification; its *Open all links* action opens them in the browser. Nothing is opened automatically.
- Files and folders are downloaded to private staging first (an interrupted download resumes), hash-checked, and only then saved to `Download/Mjolnir` (folders keep their structure; a name that is taken gets ` (1)`). Tap a file on the *Caught* screen to open it.
- On mobile data, deliveries over the large-file threshold (Settings, default 25 MB) wait for Wi-Fi with a `Waiting for Wi-Fi: …` notification; *Download now* fetches that one delivery over mobile data. Choose *Use mobile data* in Settings to never wait.

Without `google-services.json` the build still works; items then arrive when the app is opened or on the 15-minute check.

## Install the Windows app

The PC app is a tray app for Windows 10/11 (x64), packaged as an MSI.

1. Get the MSI: download the `mjolnir-msi` artifact from the latest `windows` CI run, or build it on Windows with `./gradlew :desktop:packageMsi` (output in `desktop/build/compose/binaries/main/msi/`). To bake a default relay URL into the build, add `-Pmjolnir.relayUrl=https://<app>.fly.dev`.
2. Run the MSI. It installs for the current user only (no admin rights needed) and adds Mjolnir to the Start menu.
3. On first start Mjolnir asks for the relay URL (`https://…`). The hammer then sits in the system tray; click it to open *Send to phone…*. The tray menu also has *Pair a phone…*, *Paired devices…*, *Open received files* and *Settings…*.
4. Received files are saved to `Downloads\Mjolnir` (changeable in Settings). Tick *Start Mjolnir when I sign in to Windows* in Settings to start it automatically.

Mjolnir adds itself to Explorer's *Send to* menu (right-click files or folders → *Send to* → *Mjolnir*; on Windows 11 it is under *Show more options*). The selection opens in the Send window of the running Mjolnir, or starts it. Untick *Show Mjolnir in Explorer's Send to menu* in Settings to remove the entry.

**Uninstalling** leaves the *Send to* shortcut behind: untick *Show Mjolnir in Explorer's Send to menu* in Settings first, or delete `%APPDATA%\Microsoft\Windows\SendTo\Mjolnir.lnk` afterwards.

Settings and state live in `%APPDATA%\Mjolnir` (`config.json`, `seen.json`, `outbox.json`). Delete that folder to start over.

### Test without a phone

`scripts/fake-phone.ps1` acts as a phone against your relay (state in `fake-phone.json`, downloads in `.\inbox\`):

```
.\scripts\fake-phone.ps1 -Relay https://<app>.fly.dev -Pair K7M-2QX   # use the code from "Pair a phone…"
.\scripts\fake-phone.ps1 -Send https://example.com,https://example.org
.\scripts\fake-phone.ps1 -SendFile .\report.pdf
.\scripts\fake-phone.ps1 -SendFolder .\Photos
.\scripts\fake-phone.ps1 -Inbox
```

The tray icon is the Material Symbols "hardware" glyph (Apache License 2.0).
