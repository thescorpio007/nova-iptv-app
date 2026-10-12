# Nova IPTV for Windows (32-bit)

The Windows version of Nova IPTV. It runs on every PC from **Windows 7 SP1 to Windows 11**,
32-bit or 64-bit, and plays everything through the VLC engine.

## Download

GitHub builds it automatically whenever something in `windows/` changes.
Open the repository's **Releases** page and take the newest **"Nova IPTV for Windows"** release:

- **NovaIPTV-Windows-2.0-build-N-Setup.exe** — installer with Start-menu and desktop shortcuts,
  and (optional) "Open with Nova" in the right-click menu of video files. Your default player stays.
  On old PCs without .NET Framework 4.8 it installs it first (needs internet).
- **NovaIPTV-Windows-2.0-build-N-Portable.zip** — unzip anywhere (even a USB stick) and run `Nova.exe`.
  Settings stay in the `data` folder next to it.

Every build has its own number: the version shows as **2.0 (build N)** in Settings › About and on the welcome screen.

## Phase 1 features

- Several Xtream accounts shown together in Live TV / Films / Series, each account in its own section
- Paste a full M3U / Xtream link and the account fields fill in
- Live TV: groups, channels with logos, preview box, programme guide (now / next / later)
- Films & series: poster grid, information pages (plot, cast, director, rating, trailer), seasons and episodes
- Player (VLC): full screen, ▲▼ channel change, automatic reconnection, audio / subtitle / picture choice,
  next episode plays by itself
- ★ Favorites in every zone, search across all accounts
- Keyboard and air-mouse friendly; "Designed by A.Hamrouni" on every page

## Disk buffer, watchdog, recording, downloads

- **Disk buffer** (on by default): every live channel, film and episode is downloaded to
  `Downloads\NovaIPTV\Buffer` first and played from there. Folder and sizes are in Settings
  (live 600 MB ≈ 15 min HD, films 2 GB; oldest removed first). Live TV can be paused,
  rewound 30 s at a time (←) and brought back to live (L).
- **Watchdog**: a stream that stops, freezes or is refused is reconnected after 1 s, 2 s, 4 s … up to
  every 30 s, for as long as you watch. A channel that never sends video is skipped to the next one
  (on by default, Settings).
- **Recording**: R on a channel (list or player) saves it as .ts in `Downloads\NovaIPTV\Recordings\<channel>`,
  using the same connection as watching. It continues after the player closes until you stop it.
- **Downloads**: ⬇ Download / ⬇▶ Download & Watch on film pages, episode / season downloads on series
  pages, D in the player. Saved in `Downloads\NovaIPTV\Films` and `Series\<show>\Season N`, one at a time,
  resumed after drops and restarts. MKV / MP4 files whose index is at the end start normally: the single
  download jumps to the end, then comes back.
- **One-connection accounts**: a download waits while you watch on that account; watching another channel
  while recording asks first.

## Options from the Android app (2.0)

- **Open link** tab: play / Download & Watch / download any http(s), M3U or HLS link; recent links with date
  and time (Delete / Delete all); Advanced Referer and User-Agent per site; automatic browser retry on 401/403;
  drag & drop a file or link onto Nova; "Open with Nova" from the right-click menu.
- **Downloads**: 1 / 2 / 4 / 8 / 16 connections and a speed limit, separately for your IPTV servers and for
  other links — never more than the server allows, one connection always kept free for watching; HLS downloads.
- **External players** (VLC, MPC-HC, MPC-BE, PotPlayer, Kodi, mpv…) chosen per kind: live, films, series,
  catch-up, files & links, plus a fallback when the built-in player can't play something.
- **Backup & restore** with the same `.bak` file as the Android box (optional password), saved in
  `Downloads\NovaIPTV\Backups`; restore from a file or a OneDrive / Google Drive / Dropbox link.
- **Accounts link**: one accounts file on the web, read every day; Save accounts list.
- **Audio, subtitles & stream info** panel (T), load .srt / .vtt / .ass files, subtitle size / colour / box.
- **Edit mode** (Ctrl+E) to hide and reorder groups, channels and posters; Enter twice on a poster asks to hide it
  (No first); **parental PIN** (5-minute unlock, Lock now) and a hidden-items list in Settings.
- **Sorting** per zone (Ctrl+S), 🆕 Recently added group, Recently watched tab, resume where you stopped.
- **Catch-up** (C) for channels with an archive, groups / channels list and recent-channels bar over the video,
  watchdog "jump to live" or "full repair" (other account).
- **Search** filters: All / Channels / Films / Series, all servers or one (remembered).
- **Display**: clock position, posters per row, row height, programme on air under each channel,
  list transparency; play the last channel at start-up.
- **Remote control**: every button can be changed (Settings › Remote control & keyboard).
- **VPN**: "only play when a VPN is connected".

## Keys

| Where | Keys |
|---|---|
| Everywhere | Arrows move · Enter opens / plays · Esc or Backspace = Back · F = favorite · F5 = reload · Ctrl+1…8 = tabs · Ctrl+F = search · Ctrl+E edit · Ctrl+S sort |
| Live TV | Enter = preview, Enter again = full screen · R = record · ← back to the groups |
| Player, live | ▲▼ channel · ←→ 30 s back / forward · L = live · Space pause · R record |
| Player, films | ←→ 10 s (Ctrl = 1 min) · Space pause · D download · PgUp/PgDn episode |
| Player | A audio · S subtitles · T audio/subtitles/info panel · Z picture · E external player · F full screen · M mute · + − volume |
| Series page | Enter play · W download & watch · D download episode · ←→ season |

## Project layout

- `Nova.Core` — Xtream API, models, storage (no user interface)
- `Nova.Core.Tests` — tests for the core, run by every build
- `Nova.App` — the WPF app (`Nova.exe`), .NET Framework 4.8, x86
- `installer.iss` — Inno Setup script for the installer

To build on a PC: open `NovaIPTV.sln` in Visual Studio 2022 and build **Release**.
