# DSHA

> A lightweight, natively fast AI chat client for Android. Built with Jetpack Compose; the R8-minified release APK is about 3.8 MB.

**[中文](README.md) | English**

[![Version](https://img.shields.io/badge/version-2.4.1-blue)](https://github.com/cmbh0/DshAengt/releases)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

## Introduction

An Android AI chat client aimed at long-form, continuous creative writing. It talks to any OpenAI-compatible endpoint, stores everything locally, and has no accounts and no cloud sync.

Good for:

- long-form writing, world building, role-play
- long multi-turn conversations that need context usage tracking and compaction
- self-hosted or local OpenAI-compatible services

## Contents

- [Introduction](#introduction)
- [Features](#features)
- [Not included](#not-included)
- [Getting started](#getting-started)
- [Usage](#usage)
- [Configuration](#configuration)
- [Development](#development)
- [Tech stack](#tech-stack)
- [Project layout](#project-layout)
- [Privacy & security](#privacy--security)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Multiple providers**: any OpenAI-compatible endpoint, with a built-in DeepSeek preset. A profile is one endpoint plus one key; a fresh install starts on the DeepSeek preset — paste a key and fetch the model list once. The model picker is grouped by endpoint, so switching a model switches endpoint, key and model together.
- **Quick tour as floating cards**: six cards sit **on top of the live chat screen** — not a separate page, and no scrim. The screen behind is blurred and *only* blurred: brightness and colours stay exactly as they are. Every illustration is drawn with the app's own component vocabulary (bubbles, input bar, tool rows, drawer) and animates. There is **no "Next" button**; pages advance by swiping, and only the last one shows "Start". Tapping outside does nothing (a slipped thumb should not throw the user out) — closing is the × in the corner, the system Back key, or that last button. Replay it from Settings → About.
- **Three protocol channels**: the official DeepSeek endpoint can use the **Anthropic-compatible** extended channel (web search / link reading / citations / server-side titles / time-and-place reminder), every other endpoint stays on OpenAI-compatible, and **OpenAI Responses** (ChatGPT Codex / GPT-5) is a separate adapter with semantic events, function calls, and reasoning summaries — not a renamed `/chat/completions`.
- **Context system (taken from Operit)**: the summary is a **self-contained, whole-replacement** historical reference. It is generated with the previous summary fed back in, always in four fixed sections (core task status / interaction & scenario / conversation progress / key information), followed by a per-message *dialogue review* that keeps the original tone. Two triggers only: 70% context usage, or 16 user messages since the last summary (both configurable). There is **no "keep the last N messages"** — everything after the summary is sent as usual; a keep-window would just be a blind spot the summary never sees. Local messages are never deleted, and the summary lives in the conversation where it can be read and edited. Images and files are still trimmed by turn (newest 2 / 3 by default).
- **Table card**: Markdown tables now use a full 1dp grid, tinted header, alternating rows, and rounded clipping, so three-column tables no longer blur together. Inline code, bold, and wrapping still work.
- **Smart search**: `deepseek_web_search`. The process renders as a timeline (search / browse pages / find in page / read links), the answer carries `[ⁿ]` citation marks, and the source strip and source sheet share one numbering.
- **General web tools**: `web_search` (Bing) and `web_open` (page text), gated by the built-in browser skill. The cloud workspace stays focused on files and commands — a second search stack there would be the same feature implemented twice.
- **MCP tools**: standard `mcpServers` shape, both HTTP (`url` plus custom headers, `http` and `https` alike) and local stdio (`command` + `args` + `env`), with a configurable timeout, manual entry or JSON paste-import, and a one-tap connection test that echoes the server name and tool list. Every row carries a transport icon and a live status line instead of a bare string. A configured server can be **exported as standard JSON** (one or all), ready to paste into another device.
- **Skills**: a `SKILL.md` directory is a skill and can be installed from a zip; any skill can also be **exported as a zip** (scripts and templates included, re-importable as-is).
- **Scheduled tasks**: two actions — a reminder (a notification at the set time) and a **scheduled command** (`sh -c`, pipes and `&&` allowed, exit code and output written into the result notification). Commands run in a foreground service, which sidesteps the ten-second broadcast window (capped at 120 s each); failures always notify, successes can stay quiet (a nightly backup that reports "success" every night just trains you to mute the failures too). One-off, daily, weekdays or chosen weekdays, with the next run recomputed after every fire so timezone changes and manual clock edits never throw it off.
- **AI image generation**: the text model hands the description to an image model (OpenAI-compatible `/images/generations`, accepting both `b64_json` and services that only return a `url`). **The aspect ratio comes from the conversation** (phone wallpaper → 9:16, cover → 3:4, banner → 16:9) and is then mapped to a size the model actually accepts (`gpt-image` / `dall-e-3` only allow three). The placeholder while drawing has the **same shape** as the result; the image expands in place and opens full-screen on tap. A `url` response is displayed straight from the CDN without downloading, while `b64_json` is stored in the app's private directory (not the workspace, and never the storage root); the download button saves a copy wherever the user picks. The settings page can **fetch the model list** from the endpoint, and the size defaults to **auto** while still allowing a pinned ratio or size — an explicit ratio in the chat wins. **The tool is not attached when no image endpoint is configured** — the model simply cannot see the capability and says so, instead of hammering a call that is bound to fail.
- **Drawer multi-select**: selected rows are **full-width square bands**, not rounded highlight blocks, so adjacent selections join into one block; the current-conversation tint is suppressed while selecting.
- **Tool approval**: one call shows one approval row — under the collapsed strip while collapsed, inside its own card once expanded.
- **Web preview**: opening a `.html` from the workspace (or the Preview button on a tool card) starts a tiny
  HTTP server bound to `127.0.0.1`. Relative assets, `fetch('data.json')` and `page2.html` behave like a real
  site; in-page navigation keeps the preview, Back goes to the previous page, Refresh shows the latest edit.
  Directories without an index are listed. Cloud conversations use the same path, pulling pages and their
  assets from the remote on demand.
- Streaming output: SSE token by token, live Markdown rendering, pixel-identical before and after finalisation.
- **Thinking flow**: reasoning, preamble text and tool calls render in the exact order they happened — a second round's thinking sits directly under the previous tool call, with no "Thinking #N" labels. One header ("Thought deeply (Ns)") covers the whole process: a pulsing dot and a thin rail mark the thinking blocks, the header shimmers while the model is still working, and the card box plus its blue top rail are gone.
- **Continue generating**: a reply you stopped, or one cut off by the output limit, can be continued in place (trailing-assistant prefix).
- **Math**: `$…$` / `$$…$$` / `\[…\]` rendered natively by Compose (no WebView + KaTeX).
- **Mermaid diagrams**: ```mermaid fences render as diagrams with a switch back to source; the script ships in the APK, so it works offline.
- **Read aloud**: three channels to choose from — the system TTS engine (no backend, no permission), the official DeepSeek voices, or any OpenAI-compatible `/audio/speech` endpoint (URL / key / model / voice / container format / speed / custom headers, with a preview button; the endpoint may be a full path, a `…/v1` base or a bare base). An incomplete custom endpoint falls back to the system engine silently, so nothing ever "clicks and stays mute". A separate **auto read-aloud** switch (off by default) speaks each reply once it is final; code blocks and tables are stripped before reading.
- Message tree organised by `parentId`. Regenerating creates an in-conversation branch; switching versions carries the downstream along instead of deleting history; long-press forks into a new conversation.
- **Rewind to here**: long-press any assistant reply and the files go back to their state before that reply
  (files it created are deleted, files it edited are restored) while the conversation steps back to the same
  point — the old branch is kept and can be reached again through the version switcher. Editing a user
  message and resending rewinds first, so the new reply is not generated on top of changes it never saw.
  Snapshots cover the write tools (`write_file` / `edit_file` / `patch_file`) and live in app-private storage;
  the rewind itself is recorded too, so it can be undone. Cloud conversations use the same index, isolated per env.
  A `rewind_files` tool lets the model undo its own last turn, rewind a single file, or `dry_run` first.
- **New chat**: tapping New in the top bar only **clears the current view** — nothing is written to the database and nothing appears in the drawer. The conversation becomes real the moment you send the first message, and its title comes from that message. No orphan "New chat" shells piling up in the drawer.
- Context management: CJK-weighted token estimate, usage ring on the input bar, Operit-style summaries (four sections + dialogue review + previous-summary inheritance), dual thresholds (70% / 16 user messages, both editable by typing an exact value), and **no attachment turn trimming** — every image and file in the history is sent.
- Attachments: camera / gallery / files, none of which need runtime permission; images, plain text, docx and PDF. Imported images are stored as files in app-private storage with only the file name kept in the database, so imports have no quota and a row can never grow too large to read. Tapping an image opens a full-screen viewer with swipe between images. Once sent, images sit **outside and above** your bubble, right-aligned, as **square rounded thumbnails** (cropped, up to two columns) — three portrait screenshots no longer fill the whole chat log, and tapping one shows the original. An attachment-only message still opens the long-press menu.
- **Images are validated before sending**: the real format is detected from the file header, formats the server does not accept are transcoded to JPEG locally, and unreadable images are skipped with a notice instead of breaking the conversation forever.
- **Any file as an attachment**: no type restriction any more — images go through the vision channel, plain text is inlined, and everything else (zip / apk / audio / video / documents…) is written to disk and uploaded to the workspace `uploads/` with the request; the model receives a path and opens it with tools when needed. Cloud workspaces upload with a streaming OkHttp body (read-and-write, never buffered whole); local workspaces copy straight into `uploads/`; an attachment uploads once per conversation. Attachment cards open in the system app.
- **Long input becomes a file**: past 4000 characters the whole draft is compressed into `long-text-<timestamp>.txt`, attached, and the input box is cleared, so a huge paste never makes one text field crawl.
- Model capability aware: when the selected model does not accept images, they are skipped with a notice instead of being rejected by the server.
- **Workspaces**: a built-in workspace (app-private directory, with named subfolders) sits alongside cloud environments. In the input-bar sheet the *Built-in workspace* row is the collapser itself — one tap both selects it and expands it, and the expanded area holds the root, the named folders and the create entry. Cloud rows always show the name you gave the environment, never the host address.
- **Several model profiles at once**: a profile is one endpoint plus its key plus its models. Official DeepSeek in one, a proxy in another, local Ollama in a third — they live side by side, and the model panel groups them **by profile** (never by a family name guessed from the model id). Picking a model switches the profile with it, in one write, so a request can never carry endpoint A's key to endpoint B's model. Two profiles that both proxy `deepseek-v4-pro` stay distinguishable: selection is matched on model **and** profile, and each group header carries a second line with the host — or "No API key", which you want to see *before* picking, not after the request fails. Keys are encrypted one by one with the Android Keystore; if the keystore is momentarily unavailable the previous ciphertext is kept rather than overwritten with nothing.
- **Two ways into a cloud workspace**:
  - **HTTP** — drop the bundled one-shot deployment package on a server and run it, then fill in the address and token in the app. Good for panel-managed hosting.
  - **SSH** — give `host` / port / user and either a password or a **private key plus passphrase** (both supported). On first connect a small helper script is pushed to `~/.dsha/helper.sh` and stamped with a version; after that, listing directories, reading pages, writing, editing, `grep`, `glob` patterns, directory trees, uploads, downloads and command execution all go direct. It speaks the **same tool interface** as the HTTP path, so the model cannot tell the difference and no prompt has to be reconfigured when you switch. Host keys are shown as SHA-256 fingerprints, `ServerAliveInterval=30s` keeps the session alive, dropped connections reconnect on their own, and if the script cannot be pushed it falls back to inline commands — same features, a few more bytes per call. Passwords and key passphrases are treated like API keys: encrypted with the Android Keystore before they ever touch the disk.
- **Sidebar sections**: conversations are grouped by **workspace ownership**, not dumped into one reverse-chronological list — plain chats, built-in workspace and cloud workspaces each get their own section, with pinned on top. Every section carries a second line naming what lives there (root directory, environment name), so a few hundred conversations stay navigable. Move a conversation to another workspace and it changes section immediately.
- **Thinking-stage calls are void**: a model sometimes emits `tool_calls` while its reasoning channel is still streaming — those arguments are half-finished (no path chosen, command not assembled) and running them only damages the workspace. The judge is **order**, not a special tool: if a reasoning delta arrives *after* a tool-call delta, that whole batch is discarded and the model is told to finish thinking and send it again. Well-behaved models ("think → speak → act") never trip this. When a tool fails a second time with the same arguments the receipt gains a `[repeated failure]` note that pushes the model to change approach instead of retrying; and a reply that carries reasoning but no text triggers one automatic nudge rather than leaving you with an empty bubble.
- **Dual-pane file manager**: MT-style multi-select — swipe a row right and the **whole row follows your finger**, revealing a check circle on the left; crossing the 34 dp threshold buzzes once, and only releasing there actually selects it (fall short and it springs back). Swipe the first row then the third and everything in between is selected; each pane keeps its own selection. Selections are tracked per pane, but **delete removes everything selected in both panes** — selecting a screenful and losing one file would make multi-select pointless. Long-pressing a row while in selection mode toggles it instead of opening the single-item action sheet. In selection mode the bottom bar turns into select-all / invert / copy / move / delete, and *New* no longer only creates folders — it creates files too.
- **On-device Ubuntu**: a 30 MB ubuntu-base image is pulled from a domestic mirror, unpacked into app-private storage and run with the bundled proot — so the phone gets a real bash: `apt install gcc` works, `pip` works, `/bin/bash` is GNU bash. The AI reaches it through `linux_shell` / `install_packages`, and the workspace is mapped to `/workspace` inside the container (`src/main.py` and `/workspace/src/main.py` are the same file). apt and pip are pre-pointed at Tsinghua mirrors; `--link2symlink` (with its proxy directory inside the rootfs) plus a separate `/tmp` bind fix the hard-link and temp-file failures that make dpkg/npm installations die halfway. Absolute symlinks inside the rootfs are rewritten to relative ones on extraction, and the `/bin/sh` → `/usr/bin/env` boot chain is verified — otherwise you get "extracted successfully" followed by `execve ENOENT` on the first command. **Two proot builds ship together** — an Android-native bionic PIE build and the upstream static one — each paired with its own matching loader. After installing, the daily profile is tried first; if it fails, a bare run with `-v 2` is used to make proot show where it died. No output at all means that build cannot start on this device, so the other one is tried immediately instead of burning the install window on a dead end; with output, the chain walks down p8→p0. The winning combination is recorded in `launch.plan` (e.g. `bionic:p8`) and used from then on; only when nothing works does it fail, with the raw stderr of every profile attached.
- **Screen control**: two channels (accessibility / root) × two recognition pipelines (layout node tree / screenshot plus coordinates). You pick the channel and **it is never silently swapped afterwards** — choose root and only root is used; if it is not authorised you get one actionable sentence instead of "it happened to go through accessibility this time". Every action leaves a touch point and trail on screen, and screenshots are deleted as soon as the model has read them.
- Data out: JSON backup, **ZIP archive** (backup plus a Markdown document per conversation) and Markdown export; imports this app's backups, ZIP archives, Chatbox backups and DeepSeek official exports (deepseek_data-*.zip), with three import modes: add new only, overwrite same name, or keep both. Import results are reconciled item by item (how many in the archive, how many imported, which ones failed and why), so nothing disappears silently.
- Search: full-text over titles and bodies, with hit snippets in the results.
- **Scroll performance**: images live in a pixel-metered LRU (an eighth of the heap, no re-decode), rows above the viewport are warm-parsed off the main thread, and message models are `@Immutable` so Compose skips unchanged recomposition. Flicking through history no longer drops frames, with no feature removed.
- Long messages: collapsed past 10 lines, whole message stays tappable once expanded.
- **Select and copy**: the long-press menu enters *selection mode*, wrapping the body in a `SelectionContainer` so any fragment can be dragged across paragraphs and copied; long-press no longer opens the menu while selecting, and *Done* leaves the mode.
- Appearance: dark mode, accent colours, font scale, follow the system.
- **App log**: the switch lives at the bottom of *About* in a diagnostics block (nobody needs it day to day, so it has no business on the settings home page), off by default. When on it writes a runtime log and crash log (its own `UncaughtExceptionHandler`), rotates at 2 MB per file and keeps 7 days, and exports a ZIP you can hand over for diagnosis.
- Languages: interface, prompts and export templates in Chinese and English, switchable in settings (follow system / Simplified Chinese / English); typography is tiered per language (justified for Chinese, left-aligned for Western text).

## Not included

- Client-side rewriting of content. Requests go to your configured endpoint as-is.
- TTS voice cloning; built-in third-party model hosting and billing.
- Automatic backup, cloud sync, accounts.
- Navigation, Hilt, Koin, Retrofit, Markwon, material-icons-extended.

## Getting started

### Requirements

- JDK 17 (Android Studio's bundled JBR 21 works)
- Android SDK platform 37, build-tools 37.0.0
- Gradle comes from the wrapper

### Build

```bash
git clone https://github.com/cmbh0/DshAengt.git
cd DshAengt
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

On Windows, point at a JDK explicitly:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

## Usage

1. Install and launch.
2. Open Settings → API, pick a provider, fill in the API key, tap Test connection.
3. Go back to a chat and send a message.

## Configuration

### API

| Item | Description | Default | Required |
|---|---|---|---|
| Provider | DeepSeek / OpenAI / custom | DeepSeek | yes |
| Base URL | OpenAI-compatible endpoint | per provider | yes |
| Model | Chat model | Empty — pick from `/models` | yes |
| API Key | Server-side key, stored encrypted locally | none | yes |

### Building releases

Release signing reads `keystore.properties` (never committed):

```properties
storeFile=keystore/xxx.jks
storePassword=...
keyAlias=...
keyPassword=...
```

When the file is missing, `assembleRelease` falls back to the **debug signature** (not an unsigned APK) so that a fresh clone can still run the whole release flow.

## Development

```bash
./gradlew testDebugUnitTest    # unit tests (695)
./gradlew assembleDebug        # debug build, package suffix .debug
./gradlew assembleRelease      # release build, needs signing config
```

Unit tests cover the pure functions and the protocol layer; no emulator needed.

## Tech stack

| Item | Version |
|---|---|
| Gradle | 9.7.1 |
| AGP | 9.4.1 (Kotlin built in) |
| Kotlin | 2.4.10 |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk | 37 |
| minSdk | 26 (Android 8.0) |
| JVM | 17 |
| Room | 2.8.5 |
| OkHttp | 5.5.0 |
| JSch | 0.2.20 |
| kotlinx.serialization | 1.11.0 |
| DataStore | 1.2.1 |

Full dependency list: [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Project layout

```text
app/src/main/java/com/simplechat/app/
├─ data/          repository layer: conversations, backup import/export, attachments, Keystore crypto, token estimate
│  └─ import/     Chatbox / DeepSeek backups, docx text extraction, image size
├─ db/            Room: entities, DAOs, explicit migrations, message-tree pure functions
├─ net/           DTOs, SSE client and line parser, providers and model capability table
└─ ui/
   ├─ chat/       chat page: message list, input bar, panels, edit dialog, plus pure functions (text chunks / request building / ordering)
   ├─ history/    conversation drawer and full-text search
   ├─ settings/   settings (API, data management, appearance, about)
   ├─ markdown/   Markdown rendering and soft-break fixes
   ├─ common/     the one and only widget/visual-token layer (buttons, menus, dialogs, icons, transitions)
   └─ theme/      colours, type, shapes
```

## Privacy & security

- The API key is stored as AES-256-GCM ciphertext under Android Keystore, with keys isolated per UID. The repository contains no keys.
- Requests go straight from the device to your endpoint; no third party in between.
- Database migrations are explicit; `fallbackToDestructiveMigration` is not used.

## Documentation

The design documents and implementation records are **internal and not part of this repository** (the `§` references in code comments point at them). What is public lives in the repo:

- [`TODO.md`](TODO.md): current status and to-dos.
- [`CHANGELOG.md`](CHANGELOG.md): per-version changes.

## Referenced projects

- [ZakoCoder](https://github.com/AYssu/ZakoCoder): reference implementation for the on-device Ubuntu
  (proot) runtime — `--link2symlink` with `PROOT_L2S_DIR`, binding the host `/tmp` into the container,
  pointing `PROOT_LOADER` at a bundled loader, and the post-install self-test all came from reading it.
- [Operit](https://github.com/AAswordman/Operit) / [OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore):
  reference for MCP tool wiring and the AI image-generation flow (tool discovery, tool cards, letting
  the model drive the drawing instead of rewriting the reply client-side); on the terminal side, for
  running an **Android-native (bionic PIE) proot with its matching loader taken from
  `nativeLibraryDir`** — the first bundled build is that route, with the static build kept as fallback.

proot itself is GPL-2.0 and ships with the APK in two builds; its source is available upstream.

## Contributing

Issues and pull requests are welcome. Please keep the change focused and run `./gradlew testDebugUnitTest` before sending.

## License

Released under the [MIT](LICENSE) license.




