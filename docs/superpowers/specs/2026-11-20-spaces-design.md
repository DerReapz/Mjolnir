# Mjolnir spaces: design

Status: draft for approval. Date: 2026-11-20. Builds on `2026-11-18-mjolnir-design.md` (rev 4) and `2026-11-20-pc-main-window-design.md`. Build order: main window → spaces → guest room.

## Why
Friends will install Mjolnir too. The owner wants to keep their own devices private while having shared groups ("spaces") where everyone in the group can freely send to and receive from everyone else, with no pairwise pairing.

## Goals
- A **space** is a named group of devices (e.g. "Friends"). Any member can send to any other member.
- The device that creates a space is its **admin** and controls membership: it invites, approves each joiner, removes members, renames or deletes the space, and can hand admin to another member device.
- Each device joins individually, by entering an invite code and being approved by the admin (the same feel as pairing).
- Private pairings ("My devices") are unchanged and never visible to space members. The owner chooses which of their devices join which spaces; a device can be in several spaces.
- Destinations are grouped by space everywhere: PC Send tab, phone `Throw to`, Devices screens.

## Non-goals
- Accounts, people-level identity, or syncing spaces across a person's devices.
- Broadcasting one send to a whole space (sends still go to one device at a time).
- Members inviting others (admin-only, chosen by the owner).
- Chat, presence beyond the existing connected/not state, or read receipts.

## Anti-goals
- Anyone seeing a device they don't share a pairing or a space with.
- Anyone joining a space without the admin approving that exact device.
- A removed or departed member still being able to send to the space.

## Approaches considered
1. **Spaces as their own records on the relay (chosen).** New `spaces` and `space_members` tables. Authorisation to send becomes "paired **or** share a space". A device's peer list is its paired devices plus its fellow members, each tagged with where it comes from.
2. Fake a space by silently pairing every member with every other member. This was rejected: leaving or removal would mean finding and undoing hidden pairings, and a member could end up both paired with someone and sharing a space with them, with conflicting removal rules.

## How it works
### Creating and administering
- PC: Devices tab → **Spaces** section → *New space…* (name, up to 40 characters). Phone: Devices screen → *Spaces* → *New space*.
- The admin sees a *Spaces* card per space with:
  - the member list (device name or nickname, `PC`/`Phone`, `· admin` on the admin);
  - *Invite…*
  - *Remove* on each member
  - *Rename space*
  - *Delete space*
  - *Make admin* on a member, which needs a confirmation.
- Non-admin members see the member list and *Leave space*.

### Inviting and joining
- *Invite…* shows an invite code with a countdown. It uses the same code format and lifetime as pairing codes, so a code is valid for 5 minutes and is used up when claimed.
- On the joining device: *Join a space* → enter code → `Waiting for {admin device} to accept…`.
- The admin gets `{device} wants to join {space}` with *Accept* / *Reject*. This mirrors the pair-request dialog, including double-tap safety.
- After a reject or failure, a fresh code is shown, matching the pairing behaviour.
- On accept, the joiner sees `Joined {space}`. Every member's list updates live over the WebSocket.

### Leaving, removal, deletion
- A member who leaves or is removed disappears from everyone's lists immediately (`space_changed` event). Deliveries already accepted by the relay still complete.
- When a space is deleted, everyone's lists drop it. Members see `{space} was deleted.`
- If the admin device is reset or unregistered, the longest-standing member becomes admin automatically. If no members remain, the space is deleted.

### Sending and receiving
- Sending to a space member works exactly like sending to a paired device: same outbox, same limits, same Wi-Fi rule.
- If a device is both paired with you and shares a space with you, it appears once, under *My devices*.
- Notifications and the Received list include the space: `From {name} · {space}`. Existing copy for paired devices is unchanged.
- Nicknames (from the main-window spec) apply to space members too.

## UI changes to the main-window spec
- **Send tab:** device cards are grouped under small uppercase headings: `My devices`, then one heading per space, in creation order. Empty groups are hidden. The `last used` subtitle and the selection rules are unchanged.
- **Phone `Throw to`:** the dropdown has the same grouped headings.
- **Devices tab (PC) / Devices screen (phone):** the paired devices list is first, as today, followed by a *Spaces* section with *New space…* and *Join a space*.

## Relay rules (must hold)
- `/v1/peers` returns paired devices plus fellow members of each space the caller belongs to, each with `via`: `paired` or `{spaceId, spaceName}`. Nothing else.
- `/v1/send` is allowed only when the recipient is paired with the sender or shares at least one space with it at send time.
- Only the admin device can invite, accept or reject joins, remove members, rename, delete, or transfer admin. Every one of these is checked server-side.
- Invite codes are rate-limited the same way as pairing codes.
- Limits: up to 50 spaces per device and 50 members per space, configurable.
- Guest devices (guest-room spec) can never join spaces.

## Compatibility
- `Peer` gains an optional `via` field. Older apps ignore it and keep working; they just see space members as ordinary destinations.

## Constraints
- Forge visual language. New copy is limited to the strings quoted here; everything else stays as it is today.
- Server tests cover every relay rule above. Client tests cover the join and admin state machines and the grouping logic.

## Left to the builder
- Exact endpoints, event payloads and table layout.
- How the join request reaches the admin when it's offline (store it and re-deliver on connect, like pairing requests, or let the code expire).
- Ordering within a group: by name, with last used first.
