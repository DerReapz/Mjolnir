# Mjolnir: checks that need real hardware

Every automated test passes (server, relay-client, desktop, Android unit tests, lint, APK and MSI builds in CI). These checks need a deployed relay, a Windows PC and an Android phone, so they couldn't run in the build environment.

## Before you start
1. Deploy the relay (README → *Deploy the relay to Fly.io*, including `fly deploy --ha=false`). Optionally set up Firebase (README → *Enable phone notifications*).
2. Install the MSI from the CI run's `windows` artifact, and the APK from the `mjolnir-apk` artifact.
3. The Windows-only checks can run before the phone app exists, using `scripts/fake-phone.ps1`.

## The checklists
The step-by-step lists are in the plan (`docs/superpowers/plans/2026-11-18-mjolnir.md`):
- **Windows tray app:** Task 13, Step 7 (14 checks)
- **Explorer *Send to*:** Task 14, Step 5 (4 checks)
- **Phone throw screen:** Task 18, Step 5 (12 checks); item 11 is confirmed in Task 19
- **Phone background throwing / Wi-Fi rule:** Task 19, Step 5 (6 checks)
- **Phone receiving:** Task 20, Step 5 (7 checks)

## Extra points the code reviews asked to confirm on a device
- **Start at sign-in:** the Run-key value is stored *quoted* (`"C:\…\Mjolnir.exe"`) so paths with spaces launch.
- **PC sleep:** after the PC sleeps and wakes, tabs thrown while it slept open on their own within about a minute (OkHttp ping + heartbeat).
- **Share + rotate:** share a large file to the phone and rotate it during the import; the file still lands in the tray.
- **Hammer animation:** the flying hammer starts exactly where the button's hammer is, and flies off the top of the screen.
- **Phone folders:** a folder name containing `%` or `_` gets a unique name (`Photos (1)`) when sent twice (MediaStore query).
- **Long names:** a file named with ~100 CJK characters or emoji saves on the phone.
- **Android 14+:** the "Receiving…" / "Throwing…" progress notification appears while large files transfer.
- **Icons:** the tray icon is visible on both dark and light taskbars; the launcher icon looks right with themed icons on (Android 13+).
