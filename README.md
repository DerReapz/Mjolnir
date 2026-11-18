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

## Enable phone notifications (Firebase)

Without this the relay still works; the phone simply is not woken immediately.

1. Create a project in the [Firebase console](https://console.firebase.google.com) and add an Android app with package name `app.mjolnir`.
2. Download `google-services.json` and place it in `android/` (it is git-ignored; do not commit it).
3. In Project settings, Service accounts, generate a new private key. This is the service-account JSON the relay uses (see the Fly.io step above, or set `GOOGLE_APPLICATION_CREDENTIALS` when running locally).
4. Restart the relay. The log should say `FCM enabled`. The phone registers its token with `PUT /v1/devices/me/push-token`, and each send then rings it with a data message `{"type":"inbox"}`.
