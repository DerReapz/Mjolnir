# Accounts: username and PIN sign-in

Status: draft for the owner's approval (2026-11-22), updated with the owner's answers to the builder check.

## Why

The owner wants to stop **someone pretending to be another member** of a space or chat. Today the relay has no accounts: each device is an identity. Any device can register, choose any name, and rename itself at any time with no checks (`PATCH /v1/devices/me`). So two devices can both be "Fred's phone", a member can rename mid-chat to look like someone else, and a space admin accepting a join request sees only a self-chosen name.

The groups are small (friends and family on a self-hosted relay), so the goal is **"a name you see is really that person"**, not hardened, large-scale security.

## Goals

- **One account per person** with a username that is unique on the relay and can't be changed or copied, plus a PIN. Signing in proves you are that person.
- **The account is the identity everywhere people see each other:**
  - space members
  - chat members, owners and admins
  - @mentions
  - seen and read receipts
  - join requests and invites
  - message authors
- **All your devices are you.** Signing in on a device makes it yours. Joining a space or chat once covers every device you're signed in on. Read on one device means read on all of them.
- **Your own devices link by signing in.** No pairing code is needed between them.
- **Forgotten PINs:** any admin of a space the person belongs to can reset it (owner's decision). It is made visible, so it can't be used quietly.
- **Existing installs move over** without losing their spaces, chats, admin roles or history.

## Non-goals

- Email, phone numbers, PIN recovery by email, two-step codes, passkeys.
- Protecting against the relay owner. They run the server and can read its database.
- Limiting who may create an account. Registration stays open, rate-limited per IP as today. The threat is impersonation inside a group, not strangers.
- Accounts for guest-room guests. Guests stay anonymous browser visitors.

## Anti-goals (a failure even if it "works")

- A member can make others see a name that belongs to someone else.
- An admin can reset a PIN silently and use the account unnoticed.
- Updating the apps or relay loses someone's spaces, chats, messages or admin role.
- Day-to-day use gets heavier. Someone who is signed in should never be asked for the PIN again on that device unless it was reset or signed out.

## Decisions (owner)

- **Threat:** impersonation by another member. Light security is fine.
- **Authenticator:** username + 6-digit PIN (owner's choice, 2026-11-23), replacing the draft's password.
- **Accounts:** one account per person, across all their devices.
- **PIN reset:** done by the space admin, scoped to their own space (see PIN reset).
- **Names:** every identity is unique. Display names can't copy anyone else's display name or username.
- **Cross-person pairings:** dropped at the move-over. People re-connect through spaces where needed.
- **Approach:** option 1, "accounts become the identity" (below).

## Approaches considered

1. **Accounts become the identity (chosen).** The relay gets an `accounts` table, and every device belongs to one account. Space membership, the space admin, chat membership and owner, invites, mentions, receipts, read markers and unread counts are all keyed by account. Existing data is converted once.
   - Pros: matches "one person, all devices" everywhere.
   - Cons: the largest change. Most of the space and chat rules on the relay, and both apps' space and chat code, are touched.
2. **Accounts layered over devices.** Memberships stay per device. The relay adds your other devices to everything you join, and the UI groups them.
   - Pros: less churn.
   - Cons: receipts, mentions, unread and "who's in this chat" stay per device and have to be glued together for display, so they are fragile. Rejected.
3. **Name lock only.** An account only reserves a device's name.
   - Pros: smallest change.
   - Cons: not "one account per person"; members are still separate devices. Rejected.

## Design

### Accounts and sign-in (relay)

- **The account record** holds:
  - `username`: 3–24 characters, from `a–z`, `0–9`, `.`, `_` and `-`. It is stored lower-case, compared case-insensitively, unique, and can never be changed.
  - `displayName`: at most 40 characters, changeable. It is optional and defaults to the username. **Unique across the relay (owner's decision):** compared case-insensitively after trimming and collapsing whitespace. It may not equal any other account's display name or any other account's username, and a new username may not equal an existing display name. A clash gives `name_taken` on create, sign-up and rename. Lookalike characters (homoglyphs) are left to the builder: at minimum, refuse invisible and bidi control characters.
  - The PIN hash.
  - The creation time, plus `pinResetBy` / `pinResetAt` (see PIN reset).
- **PIN rules (owner's decision: a 6-digit PIN, chosen to be easy to type, store and still safe):**
  - Exactly 6 digits. Obvious PINs are refused with `weak_pin`: all the same digit (`000000`, `111111`, …) and straight runs up or down (`123456`, `654321`, `012345`, …).
  - **Stored safely:** hashed with a slow, salted hash (Argon2id or bcrypt, whichever runs on the JVM without native pain; builder's call) **plus a relay-wide secret "pepper"** kept outside the database. The relay generates it on first start into its data folder, or reads `MJOLNIR_PIN_PEPPER`. A copied database alone then can't be brute-forced through the small PIN space. A PIN is never logged.
  - **Guessing protection (what makes a 6-digit PIN safe):** per account, 5 wrong PINs lock sign-in for 15 minutes. Each further lockout doubles the wait, up to 24 hours. A correct PIN resets the count. There is also a per-IP limit. A wrong PIN gives the generic `wrong_username_or_pin`. A locked account answers `locked` with the minutes left, shown as `Too many tries. Try again in {m} min.`
  - **On the device:** after sign-in the device keeps its token as today, so the PIN is only typed when adding a new device, after a reset, or to change it.
- **Endpoints:**
  - **Create account:** the request carries username, PIN, display name, and this device's name and kind. It creates the account and the device and returns the device token. The response is the same shape as today's register, plus the account.
  - **Sign in:** username, PIN, device name and kind. On success it creates a new device under the account and returns its token. A wrong username or PIN gives one generic error, `wrong_username_or_pin`, so it never says which part was wrong.
  - **Sign out this device:** deletes the device and its token.
  - **Sign out another of my devices:** from your device list. The use is "my phone was lost".
  - **Change PIN:** needs the current PIN. Your other devices stay signed in.
- **Rate limits:**
  - Failed sign-ins: the per-account lockout above, plus a per-IP limit.
  - Creating an account uses today's registration limit (5 per hour per IP).
- **Tokens and identity:** device tokens work as today. A device's principal now carries `accountId` as well as `deviceId`. A device that isn't signed in can do nothing except create an account or sign in. That is the only exception, plus during the move-over (below).
- **Guests:** the guest room is unchanged. Guest devices have no account.

### PIN reset by a space admin

- **Who and when (owner's decision):** only the admin of a space, and only for people who are members of that space. *Reset PIN…* is on the member card in that admin's space. No one else can reset a PIN.
- **What it does:**
  - signs out **all** of that person's devices;
  - sets a temporary 6-digit PIN the admin chooses (the same weak-PIN rules apply);
  - marks the account as "must change PIN at next sign-in";
  - records who reset it and when.
- **What the person sees on next sign-in:** `Your PIN was reset by {admin name}`. They must then choose a new PIN before using the app.
- **Who else sees it (owner's decision):** only the space where the reset was done. That space's card shows `{admin} reset {person}'s PIN` for 7 days. Other spaces the person is in are not told. The owner accepted this: an admin who resets a PIN could use the account elsewhere without the other spaces being told, but the real person always finds out at their next sign-in.
- **Limits:**
  - An admin can't reset their own PIN this way; they use Change PIN.
  - A reset is rate-limited per admin.

### Identity everywhere: devices become people

- **What members are:** space members, space admins, chat members, chat owners, chat invites (invitee and inviter), join requests, mentions, receipts (seen/read markers), read positions and unread counts are all **accounts**.
- **Message authors** are accounts. The message also records which device sent it, but that is never shown.
- **How people are shown:** by display name, with the username where it helps tell people apart, for example "Fred (fred)" in member lists and join requests. Others see a device name only where it matters, such as the Send tab target "Fred · Phone".
- **Notifications** go to all of the account's devices that have push. The rule that skips notifying while the chat is open is still per device. When an account reads up to a message on one device, the others drop that chat's unread badge.
- **Space and chat rules carry over unchanged,** with "device" replaced by "account":
  - The admin must hand over before leaving.
  - Chat owners hand over or delete.
  - The 30-day admin takeover now counts the account's most recent activity on any device.
- **Removing a member** removes the account, and so every one of its devices, from the space and from that space's chats.
- **Limits:** `MJOLNIR_MAX_MEMBERS` counts accounts. `MJOLNIR_MAX_SPACES` is per account.

### Your own devices and sending

- **My devices** is every device signed in to your account. Signing in on a new device makes it appear there for all your devices. Pairing codes between your own devices are no longer needed.
- **Sending files and tabs** still goes to a **device**. A delivery has to land on one machine.
  - The Send tab and *Throw to* show *My devices*, then one heading per space with each person's devices under their name ("Fred · PC", "Fred · Phone").
  - Space deliveries are labelled with the person and the space.
- **Pairing a device you don't own** (with another person's device) is removed. Sharing with other people happens through spaces.
  - **Existing cross-person pairings are dropped (owner's decision).** They are dropped once both devices belong to different accounts, or at the move-over. Each side sees once: `Your link with {name} was removed. Share a space to send to each other.`
  - Existing pairings between two devices that end up in the same account become "My devices" naturally.
  - The guest room's own pairing (the `links` rows it uses) is unaffected.

### Moving existing installs over

- **Relay upgrade (my call):** there is no read-only in-between mode. A device without an account can only create an account or sign in, using its existing device token so the relay knows which device's data to move. Every other request gets `sign_in_required`. Deliveries addressed to it wait on the relay as usual (7-day limit) and arrive once it signs in.
- **Telling app versions apart:** apps send a protocol version header. An app from before accounts sends none, so it gets `update_required` on every request, and both apps show that as "Update the app".
- **On first launch after the update, each app shows:**
  - *Create account*. The device's existing memberships, admin roles, chat ownership, messages, receipts and read positions move onto the new account.
  - *Sign in*. For a second device of the same person, the device joins the existing account, and its memberships are **merged** into the account:
    - **Same space in both:** kept once. The account keeps the stronger role: admin beats member.
    - **Same chat in both:** kept once. The owner role is kept if either device had it. The read position is the further one.
    - **Messages the device wrote:** re-attributed to the account.
    - **Anything only the device had:** moves to the account.
- **During the move-over,** people who haven't moved yet still appear as their old device name, marked as not yet signed in, for example "Fred's phone (not signed in)".
- **Mixed old and new versions:** see "Telling app versions apart" above. Old apps don't need to keep working beyond that.

### Screens (both apps)

- **Welcome:** shown when the device isn't signed in. It has *Create account* and *Sign in*, plus the relay address (moved here from first-run setup).
  - **Create account:** username, display name, PIN and confirm PIN (six digit boxes each; a numeric keypad on the phone).
  - **Sign in:** username and PIN.
- **Account (in Settings):**
  - display name (editable) and username (read-only);
  - *Change PIN*;
  - *My devices*, each with *Sign out*;
  - *Sign out of this device*.
- **Must change PIN:** shown after a reset. It shows the notice `Your PIN was reset by {admin}` and then asks for a new PIN.
- **Space member card (admin only):** gains *Reset PIN…*, with a confirmation that explains it signs the person out everywhere and tells them who did it.
- **Join requests and invites** show the display name and username.
- **Pairing screens:** removed. *My devices* explains: "Sign in with your account on another device to add it here."

## Constraints

- **The relay stays a single self-hosted Ktor + SQLite process.** No new external services.
- **The schema changes in place,** with `CREATE TABLE IF NOT EXISTS` and added columns as before. A relay upgrade must never lose data, and must work from any database the current relay may have.
- **Copy is shared through relay-client `Messages`,** so the PC and phone read the same.
- **Both apps ship together with the relay.** The owner must replace the relay jar on the relay host, as for previous features.

## Left to the builder / open

- **PIN hashing:** Argon2id or bcrypt, its parameters, and how the pepper is stored. Builder's call, documented in the README.
- **Session lifetime:** device tokens don't expire, as today. A reset or sign-out revokes them. This is the builder's call; the owner may revisit.
- How the move-over identifies the old device, how stored ids inside message bodies and events are rewritten, merges that hit limits, and local caches and nicknames keyed by device id.
- Lockout handling for the per-username sign-in limit.
- **Plan split:** expected to be about three plans, done in order:
  1. Relay accounts, sign-in and migration.
  2. Spaces and chats keyed by account, in the relay and the shared client.
  3. App screens on PC and phone, plus docs.
