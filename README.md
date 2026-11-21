# Mjolnir

## What Mjolnir is

Mjolnir sends browser tabs and files between your phone and your Windows PC through a small relay server that you host yourself. The relay holds a delivery until the other device picks it up (at most 7 days), and the phone is woken by an optional Firebase "doorbell" push that carries no content.

## Pairing: any two devices

Any two devices can link: PC and phone, PC and PC, or phone and phone. Both apps have the same two ways in (*Pair a device…* on the PC, *Pair a device* on the phone's Devices screen):

- **Show a code:** the device shows a code like `K7M-2QX` with `Expires in m:ss` (*Get a new code* after it runs out). When the other device enters it, `Pair with {name}?` appears with *Accept* and *Reject*. Nothing links until you accept here.
- **Enter a code:** type the code shown on the other device, then wait for that device to accept.

Every pairing is accepted on the code-showing device. Once paired, each device lists the other as a send target (`PC` or `Phone`), so you can throw tabs and files in either direction.

Modules: `:protocol` (shared models), `:server` (the Ktor relay), `:relay-client`, `:desktop` (Windows app), `:android`.

## Spaces: shared groups

A space is a named group of devices (for example "Friends"). Any member can send to any other member without pairing with each of them. Your own paired devices stay private: space members never see them, and you choose which of your devices join which spaces. A device can be in several spaces. A device that is both paired with you and in a space with you appears once, under *My devices*.

- **Create:** on the PC, Devices tab → *Spaces* → *New space…*; on the phone, Devices → *Spaces* → *New space*. The name can be up to 40 characters. The device that creates a space is its admin.
- **Invite:** the admin chooses *Invite…* on the space's card and shows a code with a countdown. It has the same format and 5-minute lifetime as a pairing code and is used up when claimed.
- **Join:** on the joining device choose *Join a space*, enter the code and wait for the admin. The admin sees `{device} wants to join {space}` and chooses *Accept* or *Reject*; on accept the joiner sees `Joined {space}`.
- **Admin controls:** only the admin can invite, accept or reject joins, remove members, *Rename space*, *Delete space* and *Make admin* (with a confirmation) on another member. The relay checks this on every request.
- **Leave:** other members see the member list and *Leave space*. An admin cannot just leave: first *Make admin* on another member, or delete the space. If the admin device has not connected to the relay for 30 days, any member can choose *Take over as admin* (with a confirmation).
- **Removal and deletion:** a member who leaves or is removed disappears from everyone's lists at once and can no longer send to the space; deliveries the relay already accepted still complete. Deleting a space drops it from everyone's lists: PCs show `{space} was deleted.`, and phones show `You're no longer in {space}.` the next time they open Devices, Spaces or Throw.
- **Sending:** the Send tab and the phone's *Throw to* group devices under `My devices` and then one heading per space; the card you click decides which space the send goes through. For space deliveries the notification reads `Caught … from {name} · {space}`, desktop Received rows read `{name} · {space} · 2m`, and phone rows read `from {name} · {space} · 2m`; deliveries between paired devices are labelled as before.

The relay limits are `MJOLNIR_MAX_SPACES` and `MJOLNIR_MAX_MEMBERS` (see the table above). The apps' limit messages (`You're in the most spaces allowed (50).`, `This space is full (50 devices).`) always say 50, even if you change these settings.

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
| `MJOLNIR_MAX_SPACES` | `50` | Spaces a device can be in (at least 1) |
| `MJOLNIR_MAX_MEMBERS` | `50` | Members per space (at least 2) |
| `MJOLNIR_GUEST_MAX_MB` | unset | Cap on the total size of one guest room (at least 1); never above the relay's total storage cap |
| `GOOGLE_APPLICATION_CREDENTIALS` | unset | Path to a Firebase service-account JSON; enables push |

Check it with `curl localhost:8080/health` (prints `ok`). Without Firebase credentials the log says `FCM disabled` and phones only get tabs while the app is open or on its periodic check.

To build and run the fat jar: `./gradlew :server:buildFatJar` then `java -jar server/build/libs/server-all.jar`.

## Guest room

A guest room lets someone with no Mjolnir exchange files, folders and links with you through a web page. It needs nothing installed on their side.

- **Open one:** on the PC, the sidebar *Guest room* tab → *Open a guest room*. On the phone, Devices → *Guest room* → *Open a guest room*. You get an invite link (`/g/{secret}` on your relay), a QR code on the PC, and a countdown. Share the link with *Copy link* (PC and phone), *Share…* (phone) or by showing the QR code.
- **The guest page:** the guest opens the link in any modern browser. The page checks for new items every 3 seconds. Folders you send download as a `.zip`. If the guest closes the tab they can rejoin from the same browser; a second browser sees `This room is already in use.`
- **Sending to the guest:** the guest appears as `Guest` under a `Guest room` heading in *Send* and *Throw to*. It is never chosen by default, so a send never goes to the guest by accident.
- **Rules:** a room lasts one hour, a device can have one room, and a room has one guest. Links the guest sends you are never opened automatically. Unpairing the guest ends the room. *End room* (or the hour running out) deletes everything you sent to the guest; what the guest sent you is kept.
- **Relay:** the invite link points at your relay, so the relay must be reachable over HTTPS from the guest's network (a relay that is only on your LAN will not work for someone elsewhere). `MJOLNIR_GUEST_MAX_MB` caps the size of one room; see the table above.
- **Browser test:** `./gradlew :server:installDist && node server/src/test/e2e/guest.e2e.mjs` runs the guest page end to end in a browser.

## Deploy the relay to Fly.io

Run these from the `server/` directory after `./gradlew :server:buildFatJar` (the Dockerfile copies `build/libs/server-all.jar`).

1. `fly launch --copy-config --no-deploy` uses the included `fly.toml`. Pick a unique app name when asked.
2. `fly volumes create data --size 3` creates the persistent volume mounted at `/data`. It must hold the storage cap (2 GB by default) plus room for the database, so 3 GB is the minimum for the defaults. If you raise `MJOLNIR_MAX_STORAGE_MB`, make the volume bigger.
3. `fly deploy --ha=false` builds the image and starts the relay on a single machine (the relay keeps its data in one SQLite file, so it must never run on two). Check `https://<app>.fly.dev/health`.
4. Optional push (needs the running machine, so do it after the first deploy): put the Firebase service-account JSON on the volume and point the relay at it:
   ```
   fly ssh console -C "mkdir -p /data/secrets"
   fly ssh sftp shell      # then: put service-account.json /data/secrets/firebase.json
   fly secrets set GOOGLE_APPLICATION_CREDENTIALS=/data/secrets/firebase.json
   ```
   Setting the secret restarts the relay. Alternatively store the JSON itself as a secret and write it to a file at startup. Never commit the JSON.

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
3. On first start Mjolnir asks for the relay URL (`https://…`). The hammer then sits in the system tray. Click it (or double-click), or choose *Open Mjolnir* from its menu, to open the main window.
4. The tray menu has *Open Mjolnir*, *Send to device…*, *Open received files* and *Quit Mjolnir*; *Send clipboard to {name}* joins it once you have sent to a device. Starting Mjolnir from the Start menu (also when it is already running) opens the main window; signing in to Windows starts it in the tray only.

### The main window

The sidebar has four tabs (a Guest room tab is hidden until a later feature):

- **Send:** pick a device, then paste (Ctrl+V) or drag links and files anywhere in the window. Photos preview with their dimensions.
- **Received:** the last 100 items you received.
- **Devices:** paired devices, *Pair a device…*, and *Rename…* to give a device a nickname on this PC. The phone has its own nicknames under Devices → Rename. A nickname shows on the Send cards and in notifications.
- **Settings:**
  - *Connection:* relay address, live status and a *Test* button.
  - *This PC:* device name.
  - *Receiving:* save folder (default `Downloads\Mjolnir`) and *Open received links in my browser straight away*.
  - *Windows:* *Start Mjolnir when I sign in to Windows*, *Show Mjolnir in Explorer's Send to menu*, and *Keep running in the tray when I close this window*.

Closing the window hides it to the tray; with *Keep running in the tray when I close this window* off, closing it quits Mjolnir.

Mjolnir adds itself to Explorer's *Send to* menu (right-click files or folders → *Send to* → *Mjolnir*; on Windows 11 it is under *Show more options*). The selection opens on the Send tab of the running Mjolnir's window (brought to the front), or starts it. Untick *Show Mjolnir in Explorer's Send to menu* in Settings to remove the entry.

**Uninstalling** leaves the *Send to* shortcut behind: untick *Show Mjolnir in Explorer's Send to menu* in Settings first, or delete `%APPDATA%\Microsoft\Windows\SendTo\Mjolnir.lnk` afterwards. Also untick *Start Mjolnir when I sign in to Windows* before uninstalling, or its sign-in entry (a `Run` registry value) stays behind.

Settings and state live in `%APPDATA%\Mjolnir` (`config.json`, `seen.json`, `outbox.json`). Delete that folder to start over.

### Test without a phone

`scripts/fake-phone.ps1` acts as a phone against your relay (state in `fake-phone.json`, downloads in `.\inbox\`):

```
.\scripts\fake-phone.ps1 -Relay https://<app>.fly.dev -Pair K7M-2QX   # use the code from "Pair a device…" → Show a code
.\scripts\fake-phone.ps1 -Send https://example.com,https://example.org
.\scripts\fake-phone.ps1 -SendFile .\report.pdf
.\scripts\fake-phone.ps1 -SendFolder .\Photos
.\scripts\fake-phone.ps1 -Inbox
```

The hammer icon is supplied by the project owner; `scripts/make-icons.py` generates the tray and app icons from it.
