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

## Any-device pairing
Needs two devices of each kind where noted (use a second PC or phone, or the fake phone for the Windows-only parts).
- **PC to PC:** on PC A choose *Pair a device…* and show a code; on PC B enter it. PC A shows `Pair with {name}?`; *Accept* gives `Paired with {name}` on both. PC B does not link before PC A accepts. Send a tab and a file from each PC to the other.
- **Phone to phone:** on phone A open Devices, *Pair a device*, *Show a code* (shows `XXX-XXX` and `Expires in m:ss`); on phone B choose *Enter a code*. Phone A shows `Pair with {name}?`; *Accept* gives `Paired with {name}`. Throw a link and a file from each phone to the other; the throw target list labels it `Phone`.
- **PC and phone, both directions:** show a code on the phone and enter it on the PC, then the reverse.
- **Reject and expiry:** *Reject* shows a fresh code on the code-showing device and the other shows `{name} said no.`; leave a code until it expires and check *Get a new code*. Double-tap *Accept* and check the device is paired once.
- **Leaving the view:** leave the Show a code view on the phone, then enter its code from another device; no dialog appears and the entering side eventually reports the code expired.

## Extra points the code reviews asked to confirm on a device
- **Start at sign-in:** the Run-key value is stored *quoted* (`"C:\…\Mjolnir.exe" --background`) so paths with spaces launch; `--background` keeps the window closed at sign-in, while the Start menu shortcut (no flag) opens it, also when Mjolnir is already running.
- **PC sleep:** after the PC sleeps and wakes, tabs thrown while it slept open on their own within about a minute (OkHttp ping + heartbeat).
- **Share + rotate:** share a large file to the phone and rotate it during the import; the file still lands in the tray.
- **Hammer animation:** the flying hammer starts exactly where the button's hammer is, and flies off the top of the screen.
- **Phone folders:** a folder name containing `%` or `_` gets a unique name (`Photos (1)`) when sent twice (MediaStore query).
- **Long names:** a file named with ~100 CJK characters or emoji saves on the phone.
- **Android 14+:** the "Receiving…" / "Throwing…" progress notification appears while large files transfer.
- **Icons:** the tray icon is visible on both dark and light taskbars; the launcher icon looks right with themed icons on (Android 13+).

## Main window
Real-desktop checks that automated tests could not cover (Windows PC with a paired phone or the fake phone).
- **Tray click:** clicking (or double-clicking) the tray icon opens the main window; so does *Open Mjolnir* in the tray menu.
- **Tray menu:** it contains *Open Mjolnir*, *Send to device…*, *Open received files* and *Quit Mjolnir*. *Send clipboard to {name}* appears between *Send to device…* and *Open received files* only once a device has been sent to.
- **Close hides:** closing the window hides it to the tray and Mjolnir keeps receiving. With *Keep running in the tray when I close this window* off, closing quits Mjolnir.
- **Paste and drop:** Ctrl+V pastes and drag-and-drop works anywhere in the window (not only on the drop area), for links, files and images.
- **Second launch:** launching Mjolnir again, or Explorer *Send to* → *Mjolnir*, brings the existing window to the front on the Send tab with the items already added.
- **Photo preview:** the Send preview shows a photo's dimensions.
- **Status:** the sidebar shows *Relay connected*; disconnect the network and it changes to *Connecting…*, then back to *Relay connected* when the network returns.
- **Test button:** in Settings → Connection, *Test* with a bad address fails within 5 s; with the right address it reports success.
- **Rename on the PC:** Devices → *Rename…* updates the Send cards and notifications; the new name survives a restart.
- **Rename on the phone:** the phone's Devices → Rename updates the phone's throw target selector.
- **Received tab:** it lists the last 100 items, newest first.

## Spaces
Needs three devices (any mix of PCs and phones, call them A, B and C) on one relay, plus A's own phone that is paired with A but not in the space.
- **Create and invite:** on A, Devices → *Spaces* → *New space…* "Friends". Choose *Invite…* and enter the code on B with *Join a space*; A shows `{B} wants to join Friends`; *Accept* gives `Joined Friends` on B. Repeat for C. All three member lists show A with `· admin`.
- **Send within the space:** B sends a tab and a file to C from the card under the Friends heading. C's notification and Received list read `From {B} · Friends`.
- **Privacy:** A's phone is not in the space, so it never appears for B or C, and B and C never appear under it unless it joins.
- **Paired and in a space:** pair A and B privately; B shows once, under *My devices* on A, and a send from there carries no space label.
- **Mismatched codes:** entering a space invite under *Pair a device* shows `That's a space invite. Use Join a space.`; a pairing code under *Join a space* shows `That's a pairing code. Use Pair a device.`
- **Remove during a send:** start a large file from B to C, then on A *Remove* C. The send already accepted by the relay completes; a new send from B to C is refused and C disappears from B's list (and B from C's).
- **Leave:** C rejoins, then uses *Leave space*; C disappears from everyone's lists. A cannot leave while admin until it uses *Make admin* on B or deletes the space.
- **Delete the space:** on A choose *Delete space*; B and C see `Friends was deleted.` and the group heading disappears from Send and *Throw to*.
- **Take over:** unit-tested only. The 30-day silence threshold is on the relay clock, which cannot be shortened with an environment variable, so it is not checked on devices.
