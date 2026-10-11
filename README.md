# Nova IPTV

An Android player for Xtream Codes IPTV accounts, for phones and Android TV.

## Features

- Xtream Codes sign-in (server, username, password) — or paste a full M3U link and the fields fill in
- **Three separate zones**: 📺 Live TV, 🎬 Films, 🎞 Series — each with its own groups, favorites and order
- **Several accounts shown together**: every account's groups appear in the same zone list, in their own section (▾ open / ▸ closed). Each channel/film shows which account it comes from.
- **Search across all accounts** at once (channels, films, series)
- **Favorites**: a ★ Favorites group at the top of each zone, mixing all accounts (♡ button or long-press OK / long-press on phone). Reorderable.
- **Organise with ✎ Edit** (top bar, in each zone):
  - ▲ ▼ reorder groups (within their account) and channels / films / series inside a group
  - Hide / Show groups, channels, films and series (hidden items only appear, faded, in edit mode)
  - Close/open each account's section of groups
  - "Show everything I hid in this zone" to undo
- Programme guide (now / next and upcoming) for the highlighted channel on TV, and in the player
- Recently watched
- Player: full screen, audio track and subtitle choice, aspect ratio (Fit / Zoom / Stretch), resume where you stopped
- Remote control: Up / Down or Channel + / − to change channel, OK for the controls
- Automatic reconnection when a stream drops
- **Dead channels are skipped automatically**: if a live channel doesn't start within the set time (default 15 s), or drops and can't reconnect, the player moves to the next channel in the list (stops after a run of failures so it never loops forever). Can be turned off in Settings.
- Rolling buffer:
  - **Safety cushion** (Settings): seconds of video collected before playback starts. Live TV runs that far behind real time, and that video keeps playing through short internet drops.
  - **Live TV buffer: Memory or Disk.** Disk mode downloads the channel in the background into rolling files, so you can pause live TV, rewind 30 s at a time, jump back to live, and ride out longer drops. Oldest parts are deleted at the size limit (default 600 MB ≈ 15 min HD).
  - **Movie & episode cache**: what you watch is cached on disk; oldest parts are deleted automatically when full (default 1 GB).
  - **Where buffers live**: hidden app storage, or `Download/NovaIPTV/Buffer` (visible in a file manager).
- **Downloads**: Download button on movies, episodes and in the player. Saved as normal video files to `Download/NovaIPTV/Movies` and `Download/NovaIPTV/Series`, play offline from the Downloads tab. Resumes after connection drops.
- **Recording**: ● Rec button in the player records the live channel you're watching to `Download/NovaIPTV/Recordings` as a .ts file (playable in VLC and in the app). Uses the same stream, no extra connection. Needs the MPEG-TS live format.

- **USB drive / SD card**: Settings → Storage folder lists internal storage and every plugged-in drive. Downloads, recordings and (optionally) the buffers go there; if the drive is unplugged the app falls back to internal storage.
- **Tunneled playback** (Settings → Playback engine): smoother video / better lip-sync on many Amlogic boxes.
- **Remote shortcuts** in the player: Menu / Info = show or hide controls · long-press OK = record (live) or download (movie/episode) · Up/Down = change channel · Rewind / Fast-forward keys = ±30 s in disk-buffer mode.

## Tested target: Ugoos AM8 (Android TV 14)

- The app has no native code, so the same APK runs on ARM 64-bit (Ugoos AM8 and most TV boxes), 32-bit ARM and x86.
- Recommended on the AM8: wired Ethernet; Ugoos audio passthrough ON if you use a soundbar/AV receiver (Dolby/DTS); keep Ugoos AFR (frame-rate matching) on; try Tunneled playback.
- For whole-drive USB access: Settings → Storage folder → Allow whole-drive access. If the box has no such screen, run once from a computer:
  `adb shell appops set com.nova.iptv MANAGE_EXTERNAL_STORAGE allow`
  (or simply choose the drive's "app folder", which needs no permission).

## Getting the APK with GitHub (no software to install)

1. Create a free account at github.com.
2. Create a new repository. Choose **Private** (the signing key is inside the project).
3. Upload the project:
   - easiest: on the new repository page click **uploading an existing file**, then drag in **all the contents** of the `NovaIPTV` folder (including the hidden `.github` folder — on Mac press Cmd+Shift+. in Finder to show it; on Windows enable "Hidden items" in the View menu).
   - Commit to the `main` branch.
   - If the `.github` folder didn't upload (the Actions tab says there are no workflows): open **Actions → set up a workflow yourself**, name the file `build-apk.yml`, paste the contents of `.github/workflows/build-apk.yml` from this project, and commit.
4. Open the **Actions** tab. The "Build APK" job starts by itself and takes about 5–8 minutes.
5. When it shows a green tick, open **Releases** (right side of the repository page) → latest "Nova IPTV build" → download **NovaIPTV.apk**.
   (Also available in Actions → the run → Artifacts, as a zip.)

Every later change you upload builds a new APK automatically. Updates install over the old version without losing your accounts.

## Installing

- **Phone:** open the APK, allow "Install unknown apps" for your browser / file manager when asked.
- **Android TV:** install the free *Downloader* app, enable *Install unknown apps* for it in Settings → Apps → Security, then open the APK's Releases link in Downloader. Or copy the APK with a USB stick and open it with a file manager.

## Building in Android Studio instead

Open the `NovaIPTV` folder in Android Studio (Ladybug or newer), wait for sync, then **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

## Notes

- Downloads use one of your account's connections while they run. If your plan allows only 1 connection, live TV may be refused during a download.
- On Android 9 and older the app asks for storage permission to use the Download folder; if refused, files go to Android/data/com.nova.iptv/files instead.
- If you reinstall the app (not update), Android may block it from re-opening files the old install saved in Download/NovaIPTV; they remain playable in other apps.

- Some movies use audio formats (DTS, some AC3/E-AC3) that certain devices cannot decode; the player shows a message when that happens.
- If channels won't start, try Settings → Live stream format → HLS, or ask your provider whether it requires a specific User-Agent.
