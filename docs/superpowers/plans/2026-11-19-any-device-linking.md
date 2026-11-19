# Any-Device Linking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let any two Mjolnir devices link and exchange tabs/files: PC↔phone (as today), PC↔PC and phone↔phone, with many partners per device.

**Architecture:** The relay and all receive paths are already device-kind agnostic. The change is in the apps: (1) a shared claim-and-wait helper in `relay-client` used by both apps' "enter a code" flows; (2) the desktop gains "enter a code" and sends to any paired device; (3) the phone gains "show a code" (with a live accept prompt over `events()`) and throws to any paired device.

**Spec:** `docs/superpowers/specs/2026-11-18-mjolnir-design.md` (rev 4; §4 flow 5 and §8 copy).

## Global Constraints
- No relay/server changes; no protocol changes.
- Persisted config keys stay as they are (`PhoneConfig.targetPcId`, `DesktopConfig.lastPhoneId`) so existing installs keep their choices; only in-memory/UI names may change.
- User-facing strings verbatim from spec §8 rev 4. Target rows read `{name}` with a `PC` / `Phone` label.
- Every pairing is accepted on the code-showing device; the entering device never links without that acceptance.
- Test conventions as before (JVM: kotlin.test/JUnit 5; Android: JUnit 4; FakeRelayApi).

## Review Focus
1. Phone showing a code while the screen is backgrounded/rotated: the live `events()` subscription must not leak, and a `PairRequested` must not be lost or answered twice.
2. A device that has both a PC and a phone paired: the target lists, "last target" memory and the throw/send notices name the right device.
3. Pairing a PC with itself or a phone with itself remains impossible (relay returns 400 for own code) and shows a sensible message.

---

### Task L1: Shared claim helper + desktop any-device linking

**Files:**
- Create: `relay-client/src/main/kotlin/app/mjolnir/client/Pairing.kt`; Test: `relay-client/src/test/kotlin/app/mjolnir/client/PairingTest.kt`
- Modify: `desktop/.../SendState.kt` (targets of any kind), `desktop/.../ui/{TrayApp,PairWindow,SendWindow,DevicesWindow}.kt`, `desktop/.../AppController.kt`
- Test: `desktop/src/test/.../SendStateTest.kt`, new `desktop/src/test/.../PairEntryTest.kt` (or AppControllerTest additions)

**Interfaces (produces):**
```kotlin
// relay-client Pairing.kt
sealed interface ClaimOutcome {
    data class Paired(val peer: Peer) : ClaimOutcome
    data class Failed(val message: String, val kind: RelayErrorKind? = null) : ClaimOutcome   // kind set for UNAUTHORIZED etc.
}
suspend fun claimAndAwait(api: RelayApi, input: String, onWaiting: (Peer) -> Unit,
                          pollMs: Long = 2_000, limit: Duration = 5.minutes): ClaimOutcome
// normalize (bad → "That code should be 6 letters and numbers"); claim (NOT_FOUND → "Code not found or expired. Get a new code on the other device.",
// RATE_LIMITED → "Too many tries. Wait a minute.", own code 400 → "That's this device's own code. Enter it on the other device."),
// onWaiting(owner); poll pairingStatus every pollMs under withTimeoutOrNull(limit): ACCEPTED → Paired(owner),
// REJECTED → "{name} said no.", EXPIRED/timeout → "The code expired. Get a new code on the other device.", NETWORK/SERVER keep polling,
// UNAUTHORIZED → Failed(Messages.RESET, UNAUTHORIZED); CancellationException rethrown.
```
- Desktop `SendState.withTargets(peers: List<Peer>, lastTargetId: String?)` replaces `withPhones` (no kind filter); `targets` replaces `phones`; dropdown shows `{name} · PC|Phone`.
- Tray: `Send to device…`, `Send clipboard to {name}`, `Pair a device…`. PairWindow gains a toggle `Enter a code instead` / `Show a code instead`; the enter view has a code field + `Pair`, shows `Waiting for {name} to accept…`, then `Paired with {name}` or the failure message, via `claimAndAwait` run in `AppController.scope` (not the window scope); on success refresh peers.

- [ ] Step 1: failing tests — PairingTest (bad format, not found, rate limited, own code, waiting then accepted, rejected, expired, network blip keeps polling, unauthorized, cancellation) with FakeRelayApi + virtual time; SendStateTest (PC and phone peers both listed; last target remembered across kinds); desktop entry test (claim completes after window scope cancelled).
- [ ] Step 2: run → fail. Step 3: implement. Step 4: `./gradlew :relay-client:test :desktop:test :desktop:compileKotlin` → pass.
- [ ] Step 5: commit `feat: shared pairing claim helper; desktop pairs with and sends to any device`.

### Task L2: Phone any-device linking (show a code, throw to any device)

**Files:**
- Modify: `android/.../ui/PairingViewModel.kt` (use `claimAndAwait`; add show-code mode), `android/.../ui/DevicesScreen.kt`, `android/.../ui/ThrowViewModel.kt` (`targets` of any kind), `android/.../ui/ThrowScreen.kt`, `android/.../ui/HammerButton.kt`
- Modify: `README.md` (pairing section: any two devices), `docs/manual-verification.md` (add PC↔PC and phone↔phone checks)
- Test: `android/src/test/.../ui/{PairingViewModelTest,ThrowViewModelTest}.kt`

**Interfaces:**
- `PairingViewModel` keeps entering (via `claimAndAwait`; on success set `targetPcId` if null) and adds `fun showCode()`: `createPairingCode()`, state `PairingUi.Showing(code: String, expiresAtEpochMs: Long, request: Peer?)`; while showing, collect `api.events()` in `viewModelScope`; on `RelayEvent.PairRequested` set `request`; `fun answer(accept: Boolean)` calls accept/reject once (guarded against double taps), then `PairingUi.Paired(name)` / back to Showing-without-request; stop collecting on `stopShowing()`/onCleared. Expired → `Get a new code`.
- Devices screen: `Pair a device` → choice `Enter a code` / `Show a code`; Showing view displays `XXX-XXX`, `Expires in m:ss`, and the `Pair with {name}?` Accept/Reject dialog.
- Throw screen: target dropdown lists every paired device with a `PC` / `Phone` label; `Pair a device first` replaces `Pair a PC first`; notices already use the target's name.

- [ ] Step 1: failing tests — PairingViewModel: show code → PairRequested via FakeRelayApi.events → answer(true) → accepted once → Paired; answer twice → one accept; reject → back to showing; leaving stops collection; entering path delegates to claimAndAwait and sets target. ThrowViewModel: phone peer is a valid target; throw to a phone sends to its id.
- [ ] Step 2–4: fail → implement → `./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug` and `./gradlew check` → pass.
- [ ] Step 5: commit `feat(android): pair by showing or entering a code; throw to any device`.
