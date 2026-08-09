<!-- Published from `mobile/` in the Agents Deck plugin repository. Edit it there. -->

> **[Download the APK](https://github.com/alenkazoloto/agent-deck-android/releases/latest)** — 1.0, a debug build, signed with the debug key.
> Android 8.0 (API 26) or newer.
>
> **This checkout does not build on its own.** The app compiles the plugin's own
> `core/mobile` wire-format files straight out of `../src/main/kotlin`, so that the phone and
> the IDE cannot disagree about a field name — and those files belong to the plugin, which is
> not published here. Building needs the plugin repository with this directory inside it as
> `mobile/`. What is published here is the app's source to read, and the APK to install.

# Agent Deck — Android companion

Triage and steer your Claude Code and Codex agents from a phone: see every conversation the
plugin knows about grouped by what needs you, read a transcript, send a prompt into a
specific conversation, stop a run, and manage the scheduled-prompt rows.

> **The phone can only reach your agents while the IDE is running.** There is no daemon, no
> relay and no server operated by this project. The plugin *is* the server: it binds a port
> inside the running IDE, and when that IDE quits there is nothing on the other end. The app
> then shows the last snapshot it received, stamped with its age ("as of 21:14") — never a
> live-looking view. Waking a sleeping laptop, running an agent in the cloud, and "run while
> the laptop is shut" are all out of scope by design.

## Build

The Android SDK is the only prerequisite (platform `android-35`, build-tools `35.0.0`).
Point the build at it with either `ANDROID_HOME` or a `local.properties`:

```bash
cd mobile
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
```

The APK lands at `mobile/app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r mobile/app/build/outputs/apk/debug/app-debug.apk`.

Tests: `./gradlew testDebugUnitTest`.

This is a **standalone Gradle build**. It is deliberately *not* included from the plugin's
root `settings.gradle.kts`: the plugin's build, its test suite and its screenshot loop must
never pay the Android Gradle Plugin's cost, and an agent running `./gradlew test` at the repo
root must not need an Android SDK.

### How the wire format is shared

The protocol is not copied. `app/build.gradle.kts` reads these files straight out of the
plugin's own tree on every build and hands them to the Android compiler:

```
core/mobile/MobileProtocol.kt     core/JsonExt.kt         core/Model.kt
core/mobile/MobileTranscript.kt   core/SessionAttention.kt core/TranscriptHealth.kt
core/mobile/MobilePairing.kt      core/Windows.kt
```

There is exactly one editable copy of the wire format and it is the plugin's; the extracted
files live under `mobile/app/build/`, which is gitignored. If the plugin renames or moves one
of them the build fails by name rather than as a wall of unresolved references.

One exception, and it is called out here because it is the kind of thing that rots quietly:
**`core/Accounts.kt` cannot compile for Android.** It calls `java.nio.file.Files.readString`,
a Java 11 method absent from `android.jar` at every `compileSdk` (adding `desugar_jdk_libs_nio`
to the *compile* classpath does not help — the platform's own `java.*` wins), and it also
pulls in `ClaudeHome` and a macOS keychain subprocess. `Model.kt` and `SessionAttention.kt`
need exactly one symbol from it, `Accounts.DEFAULT_ID`, so the build **lifts that literal out
of the plugin's own source** instead of forking the file. Change the constant in the plugin
and the next Android build changes with it; delete the declaration and the build says so.

Versions are pinned to the last AndroidX line that compiles against `compileSdk 35` (Compose
BOM `2025.06.01`, activity `1.10.1`, lifecycle `2.9.4`): the 2026 releases require
`compileSdk 36+`. AGP 9 ships Kotlin support built in, so there is no separate
`org.jetbrains.kotlin.android` plugin — only the Compose compiler plugin, whose version must
match the Kotlin version AGP embeds (9.3.1 → 2.2.10).

## Pair

In the IDE: **Settings › Connections › Mobile → Enable… → Pair a device…**. A QR code appears
for 120 seconds carrying the machine's addresses, port, certificate fingerprint and a
single-use 8-digit code.

In the app, either:

- **Scan the QR code**, or
- **type it in** — host, port, the 8-digit code and the fingerprint. The manual form is
  always on screen and is not a fallback: it is how the app is driven without a camera.

Both routes go through the same pairing exchange. The machine returns a device token once;
the app stores it in `EncryptedSharedPreferences` (falling back to ordinary private
preferences if the keystore refuses) and the plugin keeps only its SHA-256.

### The certificate pin is not negotiable

The bridge serves a self-signed certificate and the phone dials an IP, so neither a CA nor a
hostname can identify the machine — its public key does. The app pins SHA-256 of the
certificate's SubjectPublicKeyInfo from the pairing payload and compares it in constant time.

**A mismatch is a hard refusal.** There is no override and no "proceed anyway": on a shared
network that dialog is the whole attack. The app says the certificate does not match and
offers only *Pair again*.

## What the screens do

- **Fleet** — grouped **Waiting on you → Failed → Running → Done, unreviewed → everything
  else**, newest first inside each group, with the plugin's own live line on each row (the
  running tool ticker, the failure reason, or the waiting reason). Each row leads with a mark
  for its own attention state — shape *and* colour, so it survives greyscale and colour
  blindness — and closes with messages, context and then cost, in that order. Section headings
  stick, so fifty rows into a backlog the list still says which group you are in, and a group
  bigger than ten rows shows its first ten behind a **"Show all N"** expander so one 167-row
  backlog cannot bury every group under it. Project, account and vendor filters stay inline,
  visible **and pinned above the list** — never behind a "Filters (N)" button, and never
  scrolled off the top either. Pull to refresh; otherwise an SSE stream drives it. A row also
  acts **without being opened**: swipe to snooze it until its agent moves, long-press for a sheet
  with Open, Stop (running rows only), Snooze, Copy title, and the metadata the card has no width
  for. TalkBack gets that same set as custom actions and reads each row as one sentence — "Claude
  conversation, Approve the migration, Waiting on you, in plugin".
- **Conversation** — bubbles grouped into per-speaker blocks, so one avatar, one name and one
  timestamp cover a run of them rather than repeating on every turn. Assistant Markdown is real
  GFM (`multiplatform-markdown-renderer`): tables, block quotes, links and nested lists render
  as themselves, checkboxes as the same mark the task list uses, and every code block keeps a
  copy button. All of it is **selectable**, so a sentence can be copied with the platform's own
  long-press toolbar. Grouped "Tool calls (N)" sections are **expanded by default while a run
  is live** (the desktop's collapsed default is a known wrong default) — a default, not a lock:
  a group you collapse stays collapsed when the run ends. New output follows the tail only
  while you are already reading it; scrolled up, the viewport stays put and a **"N new turns"**
  pill offers the jump. A page longer than the bridge sends says so ("Showing the last N
  turns"). The working bubble speaks in the conversation's own vendor voice — "Codex is
  working…" over a Codex run — and carries **Stop**, where you are already looking. The
  composer is one pill: quick replies while the draft is empty (they **compose into it** rather
  than firing past it), and **Stop & send** only while a run is live with something typed.
- **Scheduled** — pull to refresh; pause, resume, run now, cancel, and a cancel-all that sends
  the ids it is looking at, so a row that arrived after you looked at the list is not cancelled
  by it. Cancelling asks first and says plainly that it cannot be undone — the row is gone from
  the machine's queue. Every row leads with its project, so two prompts from different repos are
  not the same row twice. You can also **create** one from the phone, which is the thing you
  remember while away from the desk; the surface appears only where the machine advertises
  `schedule-create`, because an older plugin would ignore the due time and run the prompt now.
- **Settings** — the machine (name, the address that answered, the others it knows, key
  fingerprint, last snapshot), per-trigger notification toggles that deep-link into their own
  Android channel, appearance (theme, and Material You on Android 12+), about, and diagnostics.
  Pair more than one machine and a switcher appears in the top bar; drafts and cached
  transcripts are kept per machine, so switching costs neither.

### Getting around, and being told

Fleet · Scheduled · Settings are a bottom bar (a navigation rail above 600 dp; above 840 dp the
fleet sits beside what it opened, so opening a row stops replacing the list you triage from).
New chat is a FAB on Fleet. The waiting badge is on **Fleet** — the destination that holds those
rows — and tapping it scopes the list to them; it is netted against what you have snoozed, so it
never argues with the list under it. Back pops the stack it was pushed on rather than always
returning to Fleet, the open screen survives process death, and
`agentdeck://conversation/<key>`, `…/scheduled`, `…/settings` open from outside.

Notifications are one channel per trigger — needs you, failed, finished, and so on — so you tune
them in Android's own settings; they group under a summary rather than buzzing once per agent,
and carry **Reply**, **Stop** and **Open**. Permission is asked after the first pairing, with a
sentence explaining what will be sent, never on cold start. **The transport is the honest part:**
there is no push server yet, so alerts arrive while the app is open or while you have enabled the
optional foreground connection (whose ongoing notification names the machine it is holding). A
network change reconnects on the callback instead of waiting out a backoff, and each disconnected
state names its own end — "This phone is offline" is not "workshop is not answering".

A conversation you have opened is cached, so it still reads in a tunnel, stamped with the age of
the copy ("Saved copy · as of Jul 31, 23:14"). A home-screen widget and a quick-settings tile
carry the waiting count and the top row.

### A refused send never looks like a delivered one

When the machine refuses — spend limit, deletion hold, quiesce gate, no open project — the
composer **keeps your text** and shows the refusal. The sentence you see is written by the
plugin and shown verbatim; the app never rewords it or invents its own. Drafts also survive
leaving and returning to a conversation, and process death — as does the pairing form, whose
host, port, code, fingerprint and device label survive a rotation or a phone call mid-typing,
and whose disabled **Pair** button names the field it is still waiting for.

Times carry their day whenever they are not today's: a snapshot stamped "Jul 30, 21:14" and a
prompt due "Aug 1, 09:00" cannot be misread as this morning.

## Running it against a live IDE

`scripts/deck-run.sh` builds the working tree's app, installs it, connects it to the bridge
in the IDE running on this machine and refuses to finish until the app is actually talking to
it. It is the counterpart to the fixture screenshots below: nothing here is synthetic, every
row on the screen came off a real `MobileBridgeService`.

```bash
scripts/deck-run.sh                     # build, install, connect, verify
scripts/deck-run.sh --code 96880664     # unattended, code read from the IDE beforehand
scripts/deck-run.sh --repair            # forget the stored machine and pair again
scripts/deck-run.sh --lan               # dial a real address instead of the reverse tunnel
```

It derives the host, the port and the certificate fingerprint itself — the fingerprint is read
off the live TLS certificate rather than copied by eye — and types them into the app's own
manual pairing form. The **8-digit code is the one thing it cannot derive**: only the IDE
mints it, which is the entire secret of the exchange, so the script asks for it at the last
moment (after the build, so none of the 120 seconds is spent waiting on Gradle). Open
**Settings › Connections › Mobile → Pair a device…** when it asks.

By default the app dials `127.0.0.1` through `adb reverse`. That is a loopback tunnel over
the adb channel: right for exercising the app, and **evidence of nothing at all** about
whether a phone on a LAN or a cellular network can reach this machine. `--lan` makes the app
dial a real address of this machine, which is the only variant that tests the network.

## Screenshots without a paired phone

`scripts/deck-screenshot.sh --state <name>` paints any screen on the emulator and prints the
PNG path. The state is synthetic — `app/src/debug/.../fixture/DeckFixtures.kt`, a debug-only
source set — but everything below `AgentDeckApp` is the shipped code, so what you are looking
at is the real layout, theme and insets.

```bash
scripts/deck-screenshot.sh --list                          # the state names
scripts/deck-screenshot.sh --state fleet-capped            # one screen
scripts/deck-screenshot.sh --state fleet-capped --scroll 6 # after six flicks
scripts/deck-screenshot.sh --state convo-codex --dark --font-scale 1.3
scripts/deck-screenshot.sh --all                           # every state into docs/img/
```

Every state ships with the negative control that isolates it — `fleet-capped`/`fleet-uncapped`,
`convo-truncated`/`convo-whole`, `convo-codex`/`convo-claude` — because a screenshot of the good
state proves nothing on its own.

On Apple Silicon the emulator's default Vulkan GPU initialises and then hangs ("detected a
hanging thread 'QEMU2 main loop'") without ever attaching to adb, which looks exactly like a
boot that is merely slow. The script boots with `-gpu swiftshader_indirect -no-snapshot
-no-window`; if you start the emulator yourself, use those flags.

## Privacy

No transcript, prompt or credential leaves your own devices and your own network. The app
talks to one machine, over TLS pinned to that machine's key, and to nothing else. Unpairing
wipes the token, the cached fleet snapshot and every saved draft.
