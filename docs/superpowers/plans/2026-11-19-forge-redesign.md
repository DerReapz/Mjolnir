# Forge Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Restyle the Windows tray app and the Android app in the user-chosen "A · Forge" direction (dark charcoal, molten amber accent), matching the approved mockups.

**Source of truth:** the canvas https://claude.ai/artifact/1icxaRRXueAkghY1UxXpa3 — artboards "A · Forge — phone" (`Main.dc.html`) and "A · Forge — PC Send window" (`ForgeDesktop.dc.html`). Their markup is reproduced as the spec below; behaviour is unchanged.

**Runs after:** any-device linking (L1, L2) is complete.

## Design tokens (exact)
| Token | Value |
|---|---|
| background | `#141414` |
| surface | `#1F1F1F` |
| surfaceAlt (title strip, side panel) | `#1A1A1A` / `#171717` |
| border | `#2E2E2E`; dashed drop border `#4A4A4A` |
| text | `#F5F2EC` |
| muted text | `#A8A29E` |
| accent | `#F5A524`; hover/pressed `#FFC35C`; on-accent text `#141414`; glow `#F5A524` at 13% alpha |
| headings, buttons, wordmark | Space Grotesk 500/700 (OFL) |
| body | Inter 400/500/600 (OFL) |
| radii | cards 12, inputs/selector 14, buttons 8–10, badges 6 |
| touch targets | ≥ 44 dp/px (rows 56 on phone, 40–52 on PC) |

Fonts: bundle the TTFs (SIL OFL; include `OFL.txt` next to them) — Android `res/font/`, desktop `resources/fonts/`. If they cannot be downloaded in the build environment, fall back to the platform sans (`FontFamily.SansSerif`) with the same weights and say so in the report. The hammer is the existing icon, drawn white (inverted) on dark surfaces.

## Global Constraints
- No behaviour, string or flow changes beyond the new section labels listed here: phone `Throw to` (selector label), `In your tray · {n}`, `On the way`; PC `Send to` (side-panel heading) and the device-card subtitle `{PC|Phone}` plus ` · last used` on the remembered target.
- Dark theme only (Forge is a dark design); Android status/navigation bars dark.
- Text contrast ≥ 4.5:1 on its background (muted `#A8A29E` on `#141414` passes; never put muted text on `#2E2E2E`).
- Existing tests stay green; add tests only for any new pure logic.

---

### Task R1: Desktop Forge theme
**Files:** `desktop/.../ui/` (new `ForgeTheme.kt` with the tokens, typography and component helpers; `TrayApp.kt` `MjolnirTheme`, `SendWindow.kt`, `PairWindow.kt`, `DevicesWindow.kt`, `SettingsWindow.kt`, the pair-request dialog), `desktop/src/main/resources/fonts/`.
- Send window layout per `ForgeDesktop.dc.html`: header strip (white hammer 20px + "Send to device" in Space Grotesk 700), body two columns — left: paste/drop area (dashed `#4A4A4A`, surface fill, radius 10), item rows (surface, radius 8, accent checkboxes, folder rows with muted detail + `Remove`), `Add files…` / `Add folder…` secondary buttons; right 240px side panel (`#171717`, left border): `Send to` heading (12px uppercase muted, letter-spacing 1), one card per paired device (name 600, subtitle muted `PC`/`Phone` + ` · last used`), selected card 2px accent border, others 1px border; full-width accent `Send` button at the bottom (on-accent text, Space Grotesk 700 16).
- Pair window, Devices, Settings and the "Pair with {name}?" dialog: same tokens; primary actions accent-filled, secondary outlined; the pairing code shown large in Space Grotesk 700 with letter spacing.
- [ ] Implement; `./gradlew :desktop:test :desktop:compileKotlin`; render each window headless to PNG if Compose allows it (`ImageComposeScene`) into the scratchpad and compare against the mockup; commit `feat(desktop): Forge visual theme`.

### Task R2: Android Forge theme
**Files:** `android/.../ui/` (new `ForgeTheme.kt`: Material3 dark `ColorScheme` mapped from the tokens, typography with the two families, shapes), `ThrowScreen.kt`, `HammerButton.kt`, `MjolnirNav.kt`, `CaughtScreen.kt`, `DevicesScreen.kt`, `SettingsScreen.kt`, `MainActivity.kt` (edge-to-edge dark bars), `res/font/`, `res/values/themes.xml` (dark window background `#141414` to avoid a white flash).
- Throw screen per `Main.dc.html`: header (white hammer 32dp + `MJOLNIR` Space Grotesk 700 22, letter spacing 0.5); target selector card (surface, 1dp border, radius 14, min height 56): `Throw to` muted 12 · device name 600 16 · `PC`/`Phone` badge (accent fill, on-accent text, radius 6) · chevron; `In your tray · {n}` section (uppercase muted 13, letter-spacing 1) with surface cards (radius 12, min height 56, accent checkboxes, name 500 + muted detail line); `On the way` cards (1dp border, radius 12) with a 6dp accent progress bar on a `#2E2E2E` track; hammer button 176dp circle, surface fill, 3dp accent border, 10dp accent-glow ring, white hammer 120dp, label `Hold & flick to throw` in Space Grotesk 500 16; bottom nav with stroke icons, active item accent + 600, others muted.
- Caught, Devices (incl. Show a code / Enter a code), Settings restyled with the same tokens; the pairing code displayed large in Space Grotesk 700.
- [ ] Implement; `./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug`; Compose `@Preview`s for each screen; if feasible render previews to PNG (Paparazzi/Roborazzi not required — skip if it needs new heavy deps) and compare with the mockup; commit `feat(android): Forge visual theme`.
