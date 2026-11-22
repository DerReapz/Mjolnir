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
- **Send within the space:** B sends a tab and a file to C from the card under the Friends heading. C's notification reads `Caught … from B · Friends`; the PC's Received row reads `B · Friends · 2m` and the phone's reads `from B · Friends · 2m`.
- **Privacy:** A's phone is not in the space, so it never appears for B or C, and B and C never appear under it unless it joins.
- **Paired and in a space:** pair A and B privately; B shows once, under *My devices* on A, and a send from there carries no space label.
- **Mismatched codes:** entering a space invite under *Pair a device* shows `That's a space invite. Use Join a space.`; a pairing code under *Join a space* shows `That's a pairing code. Use Pair a device.`
- **Remove during a send:** start a large file from B to C, then on A *Remove* C. The send already accepted by the relay completes; a new send from B to C is refused and C disappears from B's list (and B from C's).
- **Leave:** C rejoins, then uses *Leave space*; C disappears from everyone's lists. A cannot leave while admin until it uses *Make admin* on B or deletes the space.
- **Delete the space:** on A choose *Delete space*; PCs show `Friends was deleted.`; phones show `You're no longer in Friends.` when they next open Devices, Spaces or Throw. The group heading disappears from Send and *Throw to*.
- **Take over:** unit-tested only. The 30-day silence threshold is on the relay clock, which cannot be shortened with an environment variable, so it is not checked on devices.

## Chats (text)
Needs two PCs (A, B) and a phone (C) in one space, with A as the space admin (so A can be the non-member admin in the moderation check). The relay must be the updated one.
- **Create and invite by the owner:** on B, *Chats* → *New chat…* in the space; *Invite…* C. No approval step: C sees `You're invited to {chat}` and taps *Join*. The chat appears on C's Chats tab.
- **Invite by a member:** on C, *Invite…* A. B (owner) sees `C wants to add A`; *Approve* then A sees the invite and taps *Join*. Repeat with *Deny* and check A never sees an invite. Check *Decline* on an approved invite. The invitee gets no event or invite before the owner approves. An invite also goes away when the invitee or the inviter leaves the space (it expires after 7 days otherwise).
- **Send:** each device sends text and a link; the link is clickable. Send an emoji-only message and a message near 4,000 characters; one over the limit shows `That message is too long (4,000 characters at most).`
- **Mentions:** type `@`, pick a name; the receiver gets `{name} mentioned you in {chat}: …` and an `@` marker in the list. Type a name without picking: nobody is notified.
- **Seen and read:** with C's screen off, B's latest message shows `Seen by C`; open the chat on C and it becomes `Read by C`, then `Read by everyone`. *Info* shows per-person times.
- **Edit and delete:** edit a message (`(edited)` appears everywhere); delete one (`Message deleted`). PC: ⋯ menu (*Download* too on a message with attachments); phone: long-press (*Download* on a message with attachments, *Share…*, *Edit*, *Delete*, *Info*).
- **Admin view and moderation:** the space admin who is not a member sees the chat, can read it and sees `You see this chat as the space admin. Join to write in it.`; delete a message and remove a member as admin (`Removed by the space admin`), rename the chat and delete a chat; the admin cannot edit another person's message. Join directly (no approval) to post.
- **Owner hand-over:** the owner cannot *Leave chat* (`Make someone else the owner to leave.`); *Make owner* on another member, then leave. The owner has no way to remove another member (only the admin does); the owner can *Delete chat* at any time.
- **Removed from the space:** remove a member from the space; the chat disappears for them.
- **Phone notifications, app closed:** with the app swiped away, a message and a mention both notify the phone.
- **Offline catch-up:** turn the phone's network off, send several messages from a PC, restore the network; the chat catches up in order.

## Chat media
Needs two PCs (A, B) and a phone (C) in one chat, on the updated relay. 
- **Send each kind from every app:** a photo, a GIF, a file and a folder from A, B and C. Photos and GIFs show as thumbnails; a file shows its name and `File · {size}`; a folder shows one `Folder · {n} files · {size}` bubble. Sends start immediately (no caption step).
- **GIF animates:** on A, B and C the GIF moves in the bubble and in the full-screen view.
- **Phone image view:** tap a photo on C; pinch-zoom works and double tap resets.
- **Download:** PC: ⋯ → *Download* saves to the save folder and says `Saved to {folder}`; the file opens. Phone: long-press → *Download* says `Saved to Downloads` and the file is in Downloads.
- **Share…:** on C long-press → *Share…* opens the system share sheet and the target app receives the file. Share a photo from another app into Mjolnir and choose *Send to a chat*.
- **Attach:** on C, *Attach* opens the system picker and several files can be chosen; on A, *Attach* accepts files and folders.
- **Drag and drop (PC):** drop files onto an open chat; they send to that chat, not to the Send tab. Copy files in the file manager, press Ctrl+V on the Chats tab with a chat open; they send to that chat.
- **Ctrl+V with a dialog open (PC):** with files on the clipboard, open *New chat…*, *Invite…*, chat settings, a message's *Info* or a photo full-screen, and press Ctrl+V; nothing is sent to the chat.
- **Cancel a send:** on A and on C start sending a large file and choose *Cancel* under the sending bubble; the bubble goes away and the message never arrives on the others.
- **More than 20 files:** send a folder with over 20 files; it arrives as several messages (any text on the first).
- **Deleted message:** delete a message with a file on A; its bubble shows `Message deleted` everywhere and the file can no longer be downloaded or opened. Deleting a chat does the same for all its files.
- **Offline catch-up:** turn C's network off, send a photo and a file from A; restore the network; C shows both without reopening the chat.
- **Interrupted send:** on C start sending a large file, swipe the app away, and turn airplane mode on, then off; the send finishes and the message arrives.
- **Non-member:** a member of the space who is not in the chat cannot open its files.

## Chat video
Needs a PC (A), a second PC (B) and a phone (C) in one chat, on the updated relay. Playback and the Windows installer cannot be checked headlessly, so every item here is a manual check.
- **Installer:** install the `mjolnir-msi` artifact (about 74 MB, larger than before because it bundles JavaFX) on a clean Windows PC; Mjolnir installs, launches, and plays an MP4 inline.
- **Inline everywhere:** an MP4 recorded on C plays inline on A (after the download progress ring) and on C; on C it also plays full screen (±10 s, scrubbing, `Download`). A PC-sent MP4 plays on C and B.
- **Sound:** a video with audio plays with sound on the PC.
- **Portrait clip:** a portrait phone clip shows upright on A and C, both the thumbnail and the playback.
- **Download:** PC ⋯ → *Download* and phone long-press → *Download* both save the video, and it opens.
- **Unsupported file (PC):** send an HEVC or WebM file; A shows `Can't play this video here` with *Open in player*, which opens it in the default player.
- **One at a time (PC):** start a second video; the first stops.
- **PC pauses:** open a dialog (*Info*, *Invite…*) or the photo viewer over a playing video and it pauses; scroll it partly out of view and it pauses.
- **Leaving the chat:** leave the chat (another chat or tab); playback stops, on A and on C.
- **Phone long-press:** long-press a playing video on C; the sheet (*Download*, *Share…*) still opens, and the video pauses under it, under *Info* and in the viewer.
- **Full screen on the phone:** open full screen, rotate, close; no audio continues. Open full screen while paused: the frame shows. The position is kept when closing and reopening.
- **Background (phone):** with a video playing, switch to another app; it pauses.
- **Errors (phone):** turn airplane mode on mid-stream; it shows buffering, then `Video unavailable` with *Retry*; restore the network and *Retry* resumes.
- **Big background send:** send a video that takes over 10 minutes from C and leave the app; the "sending" notification stays and the send completes.
- **Old relay data:** a video from an older message (no thumbnail or duration) shows a plain video tile without a badge.

## Guest room
Needs the PC app, a phone with Mjolnir, and a second phone or computer with a browser and no Mjolnir (the guest). The relay must be reachable over HTTPS from the guest.
- **Open and scan:** on the PC, *Guest room* tab → *Open a guest room*; scan the QR code with the guest's phone. The guest page opens with nothing to install and the PC shows the guest as connected.
- **Send both ways:** send a file, a folder and a link from the PC to the guest (pick `Guest` under the *Guest room* group on the Send tab for the link; it appears on the guest page within about 3 s; the folder downloads as a `.zip` that opens). From the guest page send a file and a link back; they appear in the PC's list, and the link is not opened automatically.
- **Guest joined:** when the guest first opens the link, a `Guest joined` notification appears on the PC and on the phone (phone: with the Guest room screen closed too).
- **Downloaded:** after the guest downloads a folder's `.zip` (and a plain file), the PC and the phone both show `downloaded by guest` on that row, also when the phone's Guest room screen was closed during the download.
- **Drag and drop:** with a room open, drag files onto the PC Guest tab; they are sent to the guest. This could not be automated.
- **Resume:** close the guest tab and open the link again in the same browser; the page resumes with its items.
- **Second browser:** open the link in a different browser; it shows `This room is already in use.`
- **End room:** *End room* on the PC makes the guest page show "closed". Items sent to the guest are gone; what the guest sent you is still in the PC's Received tab.
- **From the phone:** repeat from Devices → *Guest room* on the phone, using *Copy link* and *Share…* to pass the link to the guest.
- **Browser script:** `./gradlew :server:installDist && node server/src/test/e2e/guest.e2e.mjs` exercises the guest page in a browser.
