"""
Drives Nova IPTV on an Android emulator against the fake Xtream server and checks
the main features. Screenshots, logcat and a report go to results/.

Usage: python3 tests/ui_test.py path/to/app.apk
"""
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PKG = "com.nova.iptv"
SERVER = "http://10.0.2.2:8080"
OUT = "results"
os.makedirs(os.path.join(OUT, "shots"), exist_ok=True)

checks = []
shot_no = 0


def sh(cmd, timeout=90):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired:
        return ""


def adb(c, timeout=90):
    return sh("adb " + c, timeout)


def note(msg):
    print(msg, flush=True)
    with open(os.path.join(OUT, "steps.txt"), "a") as f:
        f.write(msg + "\n")


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    note(("PASS  " if ok else "FAIL  ") + name + (f"  ({detail})" if detail else ""))


def shot(name):
    global shot_no
    shot_no += 1
    sh(f"adb exec-out screencap -p > {OUT}/shots/{shot_no:02d}-{name}.png")


def nodes():
    adb("shell input keyevent 224", 20)  # wake the screen if it went off
    adb("shell rm -f /sdcard/ui.xml", 20)
    adb("shell uiautomator dump /sdcard/ui.xml", 40)
    adb(f"pull /sdcard/ui.xml {OUT}/ui.xml", 20)
    try:
        root = ET.parse(f"{OUT}/ui.xml").getroot()
    except Exception:
        return []
    found = []
    for nd in root.iter("node"):
        b = [int(v) for v in re.findall(r"\d+", nd.get("bounds", ""))]
        if len(b) != 4:
            continue
        found.append({
            "text": nd.get("text", ""), "desc": nd.get("content-desc", ""),
            "x": (b[0] + b[2]) // 2, "y": (b[1] + b[3]) // 2,
        })
    return found


def dismiss_system_dialogs(found):
    """Presses 'Wait' on Android's "... isn't responding" pop-ups (slow emulator)."""
    for n in found:
        if n["text"].strip() == "Got it":  # one-time "Viewing full screen" hint
            adb(f"shell input tap {n['x']} {n['y']}")
            note("(dismissed the full-screen hint)")
            time.sleep(1.5)
            return True
    if any("isn't responding" in (n["text"] or "") for n in found):
        for n in found:
            if n["text"].strip() in ("Wait", "WAIT"):
                adb(f"shell input tap {n['x']} {n['y']}")
                note("(dismissed a system 'not responding' pop-up)")
                time.sleep(2)
                return True
    return False


def find(text, timeout=25, exact=False):
    end = time.time() + timeout
    while time.time() < end:
        found = nodes()
        if dismiss_system_dialogs(found):
            continue
        for nd in found:
            for t in (nd["text"], nd["desc"]):
                if not t:
                    continue
                # Settings group headings are in capitals ("LIVE TV", "STORAGE"): they are not buttons.
                if not exact and t.isupper() and not text.isupper():
                    continue
                if (t.strip() == text) if exact else (text.lower() in t.lower()):
                    return nd
        time.sleep(1.5)
    return None


def tap(text, timeout=25, exact=False):
    nd = find(text, timeout, exact)
    if nd is None:
        raise AssertionError(f'"{text}" not found on screen')
    adb(f"shell input tap {nd['x']} {nd['y']}")
    time.sleep(1.5)
    return nd


def tap_scrolling(text, exact=False, tries=8):
    """Scrolls down the page until [text] is on screen, then taps it."""
    for _ in range(tries):
        nd = find(text, 6, exact)
        if nd is not None and 150 < nd["y"] < 2250:
            adb(f"shell input tap {nd['x']} {nd['y']}")
            time.sleep(1.5)
            return nd
        adb("shell input swipe 540 1700 540 900 400")
        time.sleep(1)
    raise AssertionError(f'"{text}" not found on screen (after scrolling)')


def type_text(s):
    adb("shell input text '" + s.replace(" ", "%s") + "'")
    time.sleep(0.8)


def hide_keyboard():
    if "mInputShown=true" in adb("shell dumpsys input_method", 30):
        adb("shell input keyevent 4")
        time.sleep(1.5)


def clear_field():
    adb("shell input keyevent 123")  # move to end
    adb("shell input keyevent " + " ".join(["67"] * 40))  # delete


def fill(label, value, secret=False):
    """Types into the field with this label and checks that the text arrived."""
    for attempt in range(5):
        tap(label, exact=True)
        time.sleep(1)
        if attempt > 0:
            clear_field()
        type_text(value)
        time.sleep(1)
        found = nodes()
        if dismiss_system_dialogs(found):
            continue
        if secret:
            if any(n["text"] and set(n["text"]) <= set("•\u2022*") and len(n["text"]) == len(value) for n in found):
                return
        elif any(value in (n["text"] or "") for n in found):
            return
        note(f"(retyping '{label}')")
    raise AssertionError(f'could not type into "{label}"')


def test_log():
    return adb("logcat -d -s NovaTest:I", 30)


def wait_log(pattern, timeout=45):
    end = time.time() + timeout
    while time.time() < end:
        out = test_log()
        if re.search(pattern, out):
            return True
        time.sleep(2)
    return False


def crashed():
    return PKG in adb("logcat -d -b crash", 30)


def resumed_activity():
    out = adb("shell dumpsys activity activities", 30)
    m = re.search(r"(topResumedActivity|mResumedActivity)[^\n]*", out)
    return m.group(0) if m else ""


def back():
    adb("shell input keyevent 224")
    adb("shell input keyevent 4")
    time.sleep(2.5)


def back_to_main(label):
    """Leaves the player and checks that the main screen comes back.
    Back is pressed in quick succession, because live TV asks for Back twice by default."""
    adb("shell input keyevent 224")
    for _ in range(6):
        if "PlayerActivity" not in resumed_activity():
            break
        adb("shell input keyevent 4")
        time.sleep(0.4)
    time.sleep(2)
    ra = resumed_activity()
    ok = "MainActivity" in ra
    check(f"Main screen comes back after closing the player ({label})", ok, "" if ok else ra[-150:])
    if not ok:
        with open(os.path.join(OUT, f"activities-{label}.txt"), "w") as f:
            f.write(adb("shell dumpsys activity activities", 60))
        # reopen the app so the remaining checks can still run
        adb(f"shell am start -n {PKG}/.ui.MainActivity")
        time.sleep(5)


def step(title, fn):
    note(f"\n=== {title} ===")
    try:
        fn()
    except Exception as e:  # keep going so we see as much as possible
        check(title, False, f"error: {e}")
        note(traceback.format_exc())
        tag = re.sub(r"\W+", "-", title.lower())
        # never leave the player open after a failed step
        for _ in range(6):
            if "PlayerActivity" not in resumed_activity():
                break
            adb("shell input keyevent 4")
            time.sleep(0.4)
        sh(f"cp {OUT}/ui.xml {OUT}/ui-{tag}.xml")
        note("resumed: " + resumed_activity())
        note("focus: " + " | ".join(l.strip() for l in adb("shell dumpsys window", 60).splitlines()
                                       if "mCurrentFocus" in l or "mFocusedApp" in l))
        shot("error-" + re.sub(r"\W+", "-", title.lower()))


# ---------------------------------------------------------------- the tests

def install():
    # Let the freshly booted emulator settle before starting.
    for _ in range(30):
        if "1" in adb("shell getprop init.svc.bootanim") or "stopped" in adb("shell getprop init.svc.bootanim"):
            break
        time.sleep(2)
    time.sleep(45)
    dismiss_system_dialogs(nodes())
    apk = sys.argv[1]
    out = adb(f"install -r {apk}", 180)
    check("APK installs", "Success" in out, out.strip()[-200:])
    adb(f"shell pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    adb("shell settings put secure immersive_mode_confirmations confirmed")
    # Keep the emulator screen on for the whole test.
    adb("shell svc power stayon true")
    adb("shell settings put system screen_off_timeout 2147483647")
    adb("shell input keyevent 224")
    adb("logcat -c")
    adb(f"shell am start -n {PKG}/.ui.MainActivity")
    time.sleep(8)
    shot("start")
    check("App opens without crashing", not crashed())


def login_first():
    check("Welcome screen appears on the first start", find("Add your IPTV server", 30) is not None and wait_log(r"WELCOME shown", 10))
    check("Welcome screen offers restore, the accounts link and the web browser",
          find("your backup file", 5) is not None and find("accounts link", 5) is not None and find("Web browser", 5) is not None)
    shot("welcome")
    tap("Add server")
    check("Login screen appears", find("Server URL", 30) is not None)
    check("Signature note on the sign-in page", find("Designed by A.Hamrouni", 10) is not None)
    fill("Server URL or M3U link", SERVER)
    fill("Username", "alice")
    fill("Password", "secret", secret=True)
    hide_keyboard()
    shot("login-filled")
    tap("Connect", exact=True)
    ok = find("Favorites", 40) is not None
    shot("after-login")
    check("Login with Xtream account works, zones appear", ok)
    check("Live TV zone shows channels", find("Working Channel", 30) is not None)
    check("Signature note on the main page", find("Designed by A.Hamrouni", 10) is not None)


def autoskip():
    # The default is now "Stay and retry": switch skipping on for this check.
    tap("Settings", exact=True)
    open_section("Channels that don't work")
    tap("Skip automatically", exact=True)
    tap("Live TV")
    time.sleep(2)
    tap("Dead Channel")
    skipped = wait_log(r"SKIP Dead Channel", 40)
    playing = wait_log(r"READY Working Channel", 40)
    shot("player-after-autoskip")
    check("Dead channel is detected and skipped automatically", skipped)
    check("Next channel plays after the skip", playing)
    check("No crash during live playback", not crashed())
    back_to_main("live")


def catchup():
    tap("⟲")
    check("Catch-up shows past programmes", find("Morning Show", 30) is not None)
    shot("catchup-list")
    tap("Morning Show")
    check("Catch-up programme plays", wait_log(r"READY Third Channel", 45))
    back_to_main("catch-up")


def films():
    tap("Films")
    tap("Test Film", 30)
    shot("film-page")
    check("Film page shows the provider's details (director, cast, duration)",
          find("Jane Director", 20) is not None and find("Actor One", 5) is not None and find("1h 24m", 5) is not None)
    check("Film page shows the plot and a Trailer button",
          find("A test plot", 5) is not None and find("Trailer", 5) is not None)
    check("Film page shows a Download button", find("Download to device", 10) is not None)
    check("Film page has no 'Hide this film' button (hiding is done in Edit / Reorder)", find("Hide this film", 3) is None)
    tap("Play", exact=True)
    check("Film plays", wait_log(r"READY Test Film", 45))
    shot("film-playing")
    # The screen reader can't read the screen while video moves: pause with the remote's
    # pause key (which also shows the controls), then look.
    adb("shell input keyevent 127")
    time.sleep(2)
    shown = find("Speed (1x)", 20) is not None
    check("Film controls appear (speed, aspect, tracks)", shown)
    tap("Audio & subtitles")
    check("Track panel lists audio tracks", find("Audio tracks", 15) is not None)
    found = False
    for _ in range(4):
        if find("Load subtitle file", 4) is not None or find("Subtitle settings", 2) is not None:
            found = True
            break
        adb("shell input swipe 1880 950 1880 250 400")  # scroll the settings panel (landscape, right side)
        time.sleep(1)
    if not found:
        nodes()
        sh(f"cp {OUT}/ui.xml {OUT}/ui-track-panel.xml")
    check("Track panel shows subtitle options (load file, settings)", found)
    back()
    back_to_main("film")
    check("Film page is still open after playing", find("Jane Director", 10) is not None)
    back()
    check("Back closes the film page", find("Films", 10) is not None and find("Jane Director", 3) is None)


def disk_read_ahead():
    tap("Films")
    time.sleep(2)
    # --- read-ahead: the server freezes for 15 s, playback must not stop
    tap_scrolling("Slow Movie")
    tap("Play", exact=True)
    ready = wait_log(r"READY Slow Movie", 45)
    check("Film plays through the disk read-ahead", ready)
    time.sleep(50)  # the server freezes at 70 % of the download for 15 s
    log = test_log()
    stopped = "BUFFERING Slow Movie" in log
    check("No stop while the server froze for 15 s (read-ahead on disk)", ready and not stopped,
          "stopped to buffer" if stopped else "")
    files = adb(f"shell run-as {PKG} ls -R files 2>/dev/null; ls -R /sdcard/Download/NovaIPTV/Buffer 2>/dev/null")
    note("read-ahead files: " + files.strip()[:400])
    back_to_main("read-ahead")
    leave_player_and_page()
    # --- download & watch
    tap("Films")
    time.sleep(2)
    tap("Test Film", 20)
    before = count_log(r"READY Test Film")
    tap_scrolling("Download & watch")
    check("Download & watch starts playing", wait_count(r"READY Test Film", before + 1, 45))
    time.sleep(5)
    back_to_main("download-and-watch")
    leave_player_and_page()
    # MKV with its index at the end: must start long before the download reaches 100 %
    tap("Films")
    time.sleep(2)
    tap_scrolling("Slow MKV")
    tap_scrolling("Download & watch")
    started = wait_log(r"READY Slow MKV", 25)
    check("Download & watch starts an MKV right away (index fetched from the end)", started)
    back_to_main("download-and-watch-mkv")
    leave_player_and_page()
    tap("Downloads", exact=True)
    check("The film is kept in Downloads after Download & watch", find("Test Film", 20) is not None)
    tap("Films")


def y_of(text):
    """Reading position of a poster: row first, then column."""
    nd = find(text, 15)
    return (nd["y"], nd["x"]) if nd else None


def before(a, b):
    """True if poster a comes before poster b (same row = within 120 px, then left to right)."""
    if a is None or b is None:
        return False
    if abs(a[0] - b[0]) < 120:
        return a[1] < b[1]
    return a[0] < b[0]


def choose_sort(label):
    tap("⇅")
    tap(label)
    time.sleep(2)


def sorting():
    tap("Films")
    time.sleep(2)
    choose_sort("Name A → Z")
    old, new = y_of("Old Movie"), y_of("Test Film")
    shot("films-sorted-name")
    check("Sort by name puts Old Movie before Test Film", before(old, new), f"{old} {new}")
    choose_sort("Recently added")
    old, new = y_of("Old Movie"), y_of("Test Film")
    check("Sort by recently added puts Test Film first", before(new, old), f"{old} {new}")
    choose_sort("Year (newest)")
    old, new = y_of("Old Movie"), y_of("Test Film")
    check("Sort by year (2024 before 1999, year read from the title)", before(new, old), f"{old} {new}")
    shot("films-sorted-year")
    choose_sort("My order")
    old, new = y_of("Old Movie"), y_of("Test Film")
    check("My order keeps the provider's order", before(old, new), f"{old} {new}")
    tap("☰")
    tap("Recently added")
    ok = find("Test Film", 20) is not None and find("Old Movie", 5) is not None
    shot("films-recently-added")
    check("Recently added group lists the newest films", ok)
    tap("☰")
    tap("Action")


def series():
    tap("Series")
    tap("Test Series", 30)
    check("Series page shows seasons", find("Season 1", 20) is not None)
    check("Series page shows details (director, cast, duration)",
          find("Series Director", 10) is not None and find("Cast Member", 5) is not None and find("45m / episode", 5) is not None)
    shot("series-detail")
    check("Series page has the Auto-play next episode switch (on)", find("Auto-play next episode", 10) is not None)
    tap("Pilot")
    check("Episode plays", wait_log(r"READY Test Series · S1 E1 Pilot", 45))
    # Jump close to the end: the "Next episode" card counts down, then episode 2 starts by itself.
    time.sleep(3)
    for _ in range(5):
        key(90)  # fast-forward 10 s
    card = wait_log(r"NEXT episode card", 40)
    shot("next-episode-card")
    check("Next episode card appears near the end", card)
    check("Next episode plays by itself", wait_log(r"READY Test Series · S1 E2 Second", 45))
    back_to_main("episode")
    back()


def favorites():
    tap("Live TV")
    time.sleep(2)
    tap("Add to favorites")
    shot("favorite-added")
    tap("☰")
    ok = find("Favorites (1)", 15) is not None
    shot("groups-with-favorite")
    check("Favorite is saved and counted", ok)
    tap("Favorites (1)")
    # The channel playing last (the remote test ends in the "Test" group) is the one added.
    check("Favorites group lists the channel", any(find(c, 6) is not None for c in ("Frozen Channel", "Dead Channel", "Working Channel")))


def second_account():
    tap("Settings", exact=True)
    open_section("Accounts")
    tap("+ Add account")
    fill("Name (optional)", "Second")
    fill("Server URL or M3U link", SERVER)
    fill("Username", "bob")
    fill("Password", "secret", secret=True)
    hide_keyboard()
    tap("Add account", exact=True)
    check("Second account is added", find("Accounts (2)", 30) is not None)
    shot("settings-two-accounts")
    tap("Live TV")
    tap("☰")
    ok = find("Second  (", 20) is not None
    shot("groups-two-accounts")
    check("Both accounts' groups appear in the same Live TV list", ok)
    tap("Bob Sports", 15)
    check("Second account's channels open", find("Bob Channel A", 20) is not None)
    shot("second-account-channels")


def long_press(nd, ms=1200):
    adb(f"shell input swipe {nd['x']} {nd['y']} {nd['x']} {nd['y']} {ms}")
    time.sleep(1.5)


def edit_mode():
    tap("Edit")
    time.sleep(1)
    shot("edit-mode")
    check("Edit mode: lines show their names only (no controls until you're on a line)", find("Hide", 4, exact=True) is None)
    line = find("Bob Channel A", 4) or find("Working Channel", 4) or find("Sports One", 4)
    if line is not None:
        adb(f"shell input tap {line['x']} {line['y']}")
        time.sleep(1.5)
    check("Edit mode: the line you're on shows Hide and ▲▼", find("Hide", 10, exact=True) is not None and find("▲", 3, exact=True) is not None)
    # groups: ⤓ sends News to the bottom of its section and it keeps a "being moved" mark
    tap("☰")
    news = find("News", 10, exact=True)
    sports = find("Sports", 5, exact=True)
    moved = False
    if news is not None:
        long_press(news)  # pick it up: its controls appear
        news = find("News", 5)
        bottoms = [n for n in nodes() if n["text"] == "⤓"]
        row = min(bottoms, key=lambda n: abs(n["y"] - news["y"])) if bottoms else None
        if row is not None:
            adb(f"shell input tap {row['x']} {row['y']}")
            time.sleep(2)
            mark = find("⇅", 8)
            sp = find("Sports", 5, exact=True)
            moved = mark is not None and sp is not None and sp["y"] < mark["y"]
    shot("edit-group-moved")
    check("⤓ moves a group to the bottom and marks it as being moved", moved)
    if moved:
        # put it back with ⤒ so the other steps see the usual order
        tops = [n for n in nodes() if n["text"] == "⤒"]
        mark = find("⇅", 5)
        if tops and mark:
            row = min(tops, key=lambda n: abs(n["y"] - mark["y"]))
            adb(f"shell input tap {row['x']} {row['y']}")
            time.sleep(2)
    tap("Bob Sports", 10)
    tap("Bob Channel A", 10)
    n = count_log(r"HIDDEN Bob Channel A")
    tap("Hide", exact=True)
    check("Hide: the line is removed at once (even in Edit)", find("Bob Channel A", 4) is None)
    check("Hide: a message says where to bring it back", wait_count(r"HIDDEN Bob Channel A", n + 1, 5))
    check("Hide: the remote goes to the next line (not the top tabs)", wait_log(r"EDIT next line after hide: ", 5))
    tap("Done")
    check("Hidden channel disappears", find("Bob Channel A", 6) is None)
    shot("after-hide")


def search_all():
    tap("Search", exact=True)
    tap("Search ")
    type_text("Film")
    hide_keyboard()
    ok = find("Films · 2 found", 60) is not None
    shot("search")
    check("Search across both accounts returns results (a film from each account)", ok)
    check("Search shows the other account's items", find("Bob Film", 10) is not None)
    # Only films, only the second server (its real name): the other server's film disappears.
    tap("🎬 Films", exact=True)
    tap("Second", 10, exact=True)
    check("Search filters: only films in one server", find("Films · 1 found in Second", 30) is not None and find("Bob Film", 5) is not None)
    shot("search-filtered")
    check("Search filters are remembered", wait_log(r"SEARCH in Second", 5))
    tap("All servers", exact=True)
    tap("All", exact=True)


def new_settings():
    tap("Settings", exact=True)
    open_section("Display")
    tap_scrolling("Top right", exact=True)
    time.sleep(2)
    clock = any(re.fullmatch(r"\d{1,2}:\d{2}( ?[AaPp][Mm])?", (n["text"] or "").strip()) for n in nodes())
    check("Clock appears on screen", clock)
    open_section("Films & series view")
    tap_scrolling("Columns on phones")
    check("Display settings show poster columns and Live TV row height",
          find("Poster size", 10) is not None or find("Titles under posters", 5) is not None)
    open_section("Live TV view")
    tap_scrolling("Compact", exact=True)
    open_section("Backup & restore")
    # A password-protected backup first (kept by the fake server for the "restore from a link" test)…
    tap_scrolling("Save backup", exact=True)
    check("Save backup asks for an optional password", find("Password (optional)", 10) is not None)
    fill("Password (optional)", "1234", secret=True)
    fill("Repeat password", "1234", secret=True)
    hide_keyboard()
    tap("Save", exact=True)
    check("Password-protected backup is saved", wait_log(r"BACKUP saved \(password-protected\)", 15))
    pull_newest_backup("backup-protected.bak")
    time.sleep(61)  # backups are named by the minute
    # …then a normal one.
    tap_scrolling("Save backup", exact=True)
    tap("Save", exact=True)
    time.sleep(2)
    files = adb("shell ls /sdcard/Download/NovaIPTV/Backups/")
    check("Backup .bak file is saved", ".bak" in files, files.strip()[:120])
    pull_newest_backup("backup.bak")
    open_section("Hidden items & parental control")
    tap_scrolling("Hidden items (1)")
    listed = find("Bob Channel A", 10) is not None
    shot("hidden-items")
    check("Hidden channel is listed in Settings", listed)
    tap("Show again", exact=True)
    check("Show again asks Yes / No first", find("No, keep it hidden", 8) is not None)
    tap("No, keep it hidden", exact=True)
    check("No keeps it hidden", find("Bob Channel A", 5) is not None)
    tap("Show again", exact=True)
    tap("Yes, show it", exact=True)
    check("Unhide empties the hidden list", find("Hidden items (0)", 10) is not None)
    tap("Close", exact=True)
    # Edit a server's details (the accounts are at the top of Settings)
    tap("Recent", exact=True)
    tap("Settings", exact=True)
    time.sleep(2)
    open_section("Accounts")
    if find("Edit", 10, exact=True) is None:
        nodes()
        sh(f"cp {OUT}/ui.xml {OUT}/ui-accounts.xml")
    tap("Edit", 5, exact=True)
    shown = find("Edit account", 10) is not None and find("Server URL (or full M3U link)", 5) is not None
    shot("edit-account")
    check("Edit button opens the account details", shown)
    tap("Save", exact=True)
    time.sleep(4)
    check("Saving the edited account keeps both accounts", find("Edit account", 3) is None and find("Accounts (2)", 15) is not None)


def restore_from_login():
    # Wipe the app (like a fresh install), then restore the backup saved earlier from the sign-in screen.
    adb(f"shell pm clear {PKG}")
    adb(f"shell pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    adb(f"shell appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
    adb(f"shell am start -n {PKG}/.ui.MainActivity")
    check("Welcome screen appears after wiping the app", find("your backup file", 30) is not None)
    shot("welcome-restore-button")
    tap("your backup file")
    picked = find("NovaIPTV-", 15)
    shot("welcome-restore-list")
    check("Restore window lists the saved .bak file", picked is not None)
    tap("NovaIPTV-")
    check("Restore asks Yes / No first", find("Yes, restore", 10) is not None and find("No", 3, exact=True) is not None)
    tap("Yes, restore", exact=True)
    ok = find("Favorites", 40) is not None
    shot("after-restore")
    check("Restoring from the sign-in screen brings back the accounts", ok)
    tap("☰")
    both = find("Second  (", 20) is not None
    tap("Bob Sports", 15)
    shot("restored-second-account")
    check("Restored lists show both accounts' groups", both)
    # The backup was saved while "Bob Channel A" was hidden, so it must still be hidden.
    check("Restored hidden channel stays hidden", find("No channels in this group", 15) is not None)


def pull_newest_backup(name):
    newest = adb("shell ls -t /sdcard/Download/NovaIPTV/Backups/").split()
    newest = [f for f in newest if f.endswith(".bak")]
    if newest:
        dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media", name)
        adb(f"pull /sdcard/Download/NovaIPTV/Backups/{newest[0]} {dest}")
        note(f"(fake server now serves {name})")


def set_accounts_version(v):
    import urllib.request
    urllib.request.urlopen(f"http://127.0.0.1:8080/set_accounts?v={v}", timeout=10).read()


def find_from_top(text, timeout=10):
    """Scrolls back to the top of the page, then down until [text] shows."""
    for _ in range(6):
        adb("shell input swipe 540 700 540 1900 300")
    nd = find(text, 3)
    return nd if nd is not None else scroll_to(text, tries=10)


def check_accounts_link_now():
    tap("Settings", exact=True)
    open_section("Accounts")
    tap_scrolling("⟳ Check now", exact=True)


def accounts_link():
    set_accounts_version(1)
    adb(f"shell pm clear {PKG}")
    adb(f"shell pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    adb(f"shell appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
    adb(f"shell am start -n {PKG}/.ui.MainActivity")
    tap("accounts link", 30)
    fill("Link to your accounts list (.txt)", SERVER + "/accounts.txt")
    hide_keyboard()
    tap("Check", exact=True)
    found = wait_log(r"ACCOUNTS LINK found 3: Main, Second, Old", 30)
    shot("accounts-link-found")
    check("The accounts list is read from the link (3 accounts)", found)
    check("The expired account is flagged before connecting", find("Expired", 15) is not None)
    tap("Connect 3 accounts", exact=True)
    check("Connecting adds the accounts and opens Live TV", find("Favorites", 40) is not None)
    check("Start-up check: one account failed, Nova enters with a warning", wait_log(r"STARTUP 3 accounts, 1 with problems", 30))
    check("Warning banner offers Adjust", find("Adjust", 15, exact=True) is not None)
    shot("startup-banner")
    # The list changes: "Second" removed, "Main" gets a new password.
    set_accounts_version(2)
    check_accounts_link_now()
    check("Removed line deletes that account, changed line updates it",
          wait_log(r"ACCOUNTS LINK updated 1, added 0, removed 1", 30) and find_from_top("Accounts (2)") is not None)
    check("Accounts from the link are tagged", find_from_top("from your list") is not None)
    shot("accounts-link-settings")
    # An empty file must change nothing.
    set_accounts_version(3)
    tap_scrolling("⟳ Check now", exact=True)
    check("Empty / unreadable list changes nothing", wait_log(r"nothing changed", 30) and find_from_top("Accounts (2)") is not None)
    # Only the expired account left: at the next start Nova stops on the Accounts check screen.
    set_accounts_version(4)
    tap_scrolling("⟳ Check now", exact=True)
    wait_log(r"ACCOUNTS LINK updated 0, added 0, removed 1", 30)
    adb(f"shell am force-stop {PKG}")
    adb(f"shell am start -n {PKG}/.ui.MainActivity")
    shown = find("Your accounts can't be used right now", 40) is not None
    shot("accounts-check")
    check("All accounts failing stops on the Accounts check screen", shown and wait_log(r"ACCOUNTS CHECK shown", 10))
    check("The reason is shown (expired)", find("Expired on", 10) is not None)
    tap("Continue anyway", exact=True)
    check("Continue anyway enters the app", find("Favorites", 30) is not None)
    tap("Settings", exact=True)
    open_section("Accounts")
    tap_scrolling("Disconnect", exact=True)
    tap("Yes, disconnect", exact=True)
    check("The accounts link can be disconnected", find("accounts link", 15) is not None)


def restore_by_link():
    tap("Settings", exact=True)
    open_section("Backup & restore")
    tap_scrolling("Restore a backup", exact=True)
    tap("From a link")
    fill("Link to your .bak file", SERVER + "/backup-protected.bak")
    hide_keyboard()
    tap("Download & check", exact=True)
    check("A protected backup asks for its password", find("This backup is password-protected", 30) is not None)
    fill("Password", "9999", secret=True)
    tap("OK", exact=True)
    check("A wrong password is refused", find("Wrong password", 20) is not None)
    fill("Password", "1234", secret=True)
    tap("OK", exact=True)
    check("Right password: restore asks Yes / No", find("Yes, restore", 30) is not None)
    shot("restore-by-link-confirm")
    tap("Yes, restore", exact=True)
    check("Restoring from a link brings back the accounts", wait_log(r"BACKUP restored", 30) and find("Favorites", 40) is not None)
    tap("Settings", exact=True)
    open_section("Backup & restore")
    tap_scrolling("Restore a backup", exact=True)
    tap("From a link")
    check("The last link is offered again", find("Use it again", 15) is not None)
    tap("Cancel", exact=True)


def set_video_decoder(label):
    tap("Settings", exact=True)
    open_section("Built-in player")
    nd = scroll_to(label, tries=30)
    if nd is None:
        raise AssertionError(f'"{label}" not found in Settings')
    adb(f"shell input tap {nd['x']} {nd['y']}")
    time.sleep(1)


def watchdog():
    # The emulator's hardware decoder fails every few seconds on paced live streams: use the software one here.
    set_video_decoder("Software decoder")
    tap("Live TV")
    time.sleep(2)
    tap("☰")
    tap("Test", 15, exact=True)
    frozen = count_log(r"READY Frozen Channel")
    tap("Frozen Channel", 15)
    check("Freezing channel starts", wait_count(r"READY Frozen Channel", frozen + 1, 45))
    # the fake provider stops sending after ~10 s but keeps the connection open (no error)
    acted = wait_log(r"WATCHDOG Frozen Channel: .* -> reconnected", 130)
    check("Watchdog notices the silent stop and reconnects by itself", acted)
    check("The channel plays again after the watchdog reconnect", wait_count(r"READY Frozen Channel", frozen + 2, 40))
    back_to_main("watchdog")
    tap("Settings", exact=True)
    open_section("Stream watchdog")
    tap_scrolling("Stream health log", tries=25)
    check("The stream health log lists the event", find("Frozen Channel", 10) is not None)
    tap("Close", exact=True)


def favorite_long_press():
    tap("Films")
    time.sleep(2)
    nd = find("Test Film", 20)
    if nd is None:
        raise AssertionError("Test Film poster not found")
    before = count_log(r"FAVORITE added: Test Film")
    adb(f"shell input swipe {nd['x']} {nd['y']} {nd['x']} {nd['y']} 1200")
    check("Long-press on a film poster adds it to Favorites", wait_count(r"FAVORITE added: Test Film", before + 1, 15))
    before = count_log(r"FAVORITE removed: Test Film")
    adb(f"shell input swipe {nd['x']} {nd['y']} {nd['x']} {nd['y']} 1200")
    check("A second long-press removes it again", wait_count(r"FAVORITE removed: Test Film", before + 1, 15))


def posters_with_remote():
    """TV layout (landscape): the remote stays inside the posters, holding OK = favourite, Back = groups."""
    adb("shell settings put system accelerometer_rotation 0")
    adb("shell settings put system user_rotation 1")
    time.sleep(4)
    try:
        tap("Films")
        time.sleep(3)
        shot("films-landscape")
        before_in = count_log(r"POSTERS focus in")
        tap("Action", 15, exact=True)
        adb("shell input keyevent 21")  # leave touch mode: the remote takes over
        check("OK / tap on a group puts the remote on its posters", wait_count(r"POSTERS focus in", before_in + 1, 12))
        out_before = count_log(r"POSTERS focus out")
        for k in (21, 21, 21, 19, 19, 21):
            key(k)
        check("◀ and ▲ at the edges keep the remote inside the posters", count_log(r"POSTERS focus out") == out_before)
        added = count_log(r"FAVORITE added")
        adb("shell input keyevent --longpress 23")
        check("Holding OK on a poster adds it to Favorites", wait_count(r"FAVORITE added", added + 1, 10))
        removed = count_log(r"FAVORITE removed")
        adb("shell input keyevent --longpress 23")
        check("Holding OK again removes it", wait_count(r"FAVORITE removed", removed + 1, 10))
        check("Holding OK did not open the film page", find("Download & watch", 3) is None)
        # short OK opens the film page; Back returns with the remote on the same poster
        back_on = count_log(r"POSTERS remote back on the opened poster")
        key(23)
        check("OK opens the film page", find("Download & watch", 10) is not None)
        key(4)
        check("Back from the film page puts the remote on the same poster",
              wait_count(r"POSTERS remote back on the opened poster", back_on + 1, 10))
        # Back, level by level, never leaving Films
        n = count_log(r"GROUPS focus in")
        key(4)
        check("Back moves the remote to the groups (focus really moves)", wait_count(r"GROUPS focus in", n + 1, 8))
        n = count_log(r"POSTERS back to tabs")
        key(4)
        check("Back again moves the remote to the top tabs", wait_count(r"POSTERS back to tabs", n + 1, 8))
        n = count_log(r"POSTERS back on tabs: stay")
        key(4)
        check("Back on the tabs stays in Films (no jump to Live TV)", wait_count(r"POSTERS back on tabs: stay", n + 1, 8))
        check("Still on the Films page after all the Backs", find("Action", 5) is not None and find("Working Channel", 2) is None)
        # Live TV groups with the remote: ▼ ▼ ▼ goes straight down, never back to the group that just opened
        tap("Live TV")
        time.sleep(3)
        tap("News", 10, exact=True)
        time.sleep(3)
        mark = len(test_log().splitlines())
        for _ in range(6):
            key(20)
            time.sleep(1.5)  # let each group open
            if any("GROUP focus Test" in ln for ln in test_log().splitlines()[mark:]):
                break
        order = ["★ Favorites", "News", "Sports", "Test"]
        seen = [ln.split("GROUP focus ", 1)[1].strip() for ln in test_log().splitlines()[mark:] if "GROUP focus " in ln]
        idx = [next((i for i, o in enumerate(order) if s.startswith(o)), -1) for s in seen]
        note("group focus sequence: " + " → ".join(seen))
        check("▼ through the groups never jumps back to the group that just opened",
              len(idx) >= 2 and all(b >= a for a, b in zip(idx, idx[1:])) and idx[-1] == 3, " → ".join(seen))
    finally:
        adb("shell settings put system user_rotation 0")
        time.sleep(4)


def live_fast_start():
    tap("Live TV")
    time.sleep(2)
    tap("☰")
    tap("Test", 15, exact=True)
    before = count_log(r"READY Paced Channel")
    tap("Paced Channel", 15)
    check("Real-time channel starts quickly (fast start)", wait_count(r"READY Paced Channel", before + 1, 20))
    check("The safety cushion is built while watching", wait_log(r"CUSHION building", 30))
    back_to_main("fast-start")



def close_opened_player(label):
    """The player was opened by another app: Back closes it (and returns to that app)."""
    for _ in range(6):
        if "PlayerActivity" not in resumed_activity():
            break
        adb("shell input keyevent 4")
        time.sleep(0.4)
    time.sleep(2)
    check(f"Back closes the opened player ({label})", "PlayerActivity" not in resumed_activity())
    adb(f"shell am start -n {PKG}/.ui.MainActivity")
    time.sleep(3)


def open_with():
    """Other apps can open videos and M3U playlists with Nova ("Open with")."""
    out = adb(f"shell cmd package query-activities -a android.intent.action.VIEW -d {SERVER}/x/film.mp4 -t video/mp4")
    check("Android lists Nova for videos (Open with)", PKG in out, out.strip()[-200:])
    # a video link
    n = count_log(r"OPEN WITH video")
    adb(f"shell am start -a android.intent.action.VIEW -d {SERVER}/movie/alice/secret/301.mp4 -t video/mp4 {PKG}")
    check("A video link opens in Nova", wait_count(r"OPEN WITH video", n + 1, 20))
    check("The opened video plays", wait_log(r"READY 301\.mp4", 45))
    close_opened_player("open-with-video")
    # a video file on the device
    adb(f"shell appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
    adb("push tests/media/sample.mp4 /sdcard/Download/nova-open.mp4", 60)
    n = count_log(r"READY nova-open\.mp4")
    adb(f"shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/nova-open.mp4 -t video/mp4 {PKG}")
    check("A video file from the device plays in Nova", wait_count(r"READY nova-open\.mp4", n + 1, 45))
    close_opened_player("open-with-file")
    # an M3U playlist: temporary channel list, not saved as an account
    n = count_log(r"OPEN WITH playlist")
    adb(f"shell am start -a android.intent.action.VIEW -d {SERVER}/playlist.m3u -t audio/x-mpegurl {PKG}")
    check("An M3U playlist opens as a channel list (3 entries, 3 groups)", wait_log(r"OPEN WITH playlist: 3 entries in 3 groups", 20))
    check("The playlist's first channel plays", wait_log(r"READY Playlist Channel A", 45))
    key(19)  # next channel in the playlist (▲ = next by default)
    check("▲ goes to the playlist's next channel", wait_log(r"READY Playlist Channel B", 45))
    close_opened_player("open-with-playlist")
    accounts = adb(f"shell run-as {PKG} cat shared_prefs/nova_iptv.xml")
    check("The playlist is not saved as an account", "playlist.m3u" not in accounts and "Playlist Channel" not in accounts)



def cold_start():
    """A channel whose server needs ~12 s to wake up starts by itself (no key press)."""
    tap("Live TV")
    time.sleep(2)
    check("The Edit / Reorder button is shown", find("Edit / Reorder", 10) is not None)
    tap("☰")
    tap("Test", 15, exact=True)
    n = count_log(r"READY Cold Channel")
    tap("Cold Channel", 15)
    check("A channel that doesn't start at once is retried by itself", wait_log(r"START RETRY Cold Channel", 30))
    check("The channel starts after the server wakes up, with no key press", wait_count(r"READY Cold Channel", n + 1, 60))
    check("It was not declared dead", "SKIP Cold Channel" not in test_log())
    back_to_main("cold-start")



def set_watchdog_mode(label):
    tap("Settings", exact=True)
    open_section("Stream watchdog")
    nd = scroll_to(label, tries=30)
    if nd is None:
        raise AssertionError(f'"{label}" not found in Settings')
    adb(f"shell input tap {nd['x']} {nd['y']}")
    time.sleep(1)


def open_test_channel(name):
    tap("Live TV")
    time.sleep(2)
    tap("☰")
    tap("Test", 15, exact=True)
    tap(name, 15)


def weak_signal():
    """Video arrives slower than it plays."""
    # Default watchdog (Live button only): no repairs at all, the player rides it out by itself.
    n = count_log(r"READY Weak Channel")
    open_test_channel("Weak Channel")
    check("Weak channel starts", wait_count(r"READY Weak Channel", n + 1, 30))
    check("It stops when the video runs out (weak signal)", wait_log(r"BUFFERING Weak Channel", 60))
    check("…and plays on by itself", wait_log(r"PLAYING again Weak Channel", 60))
    log = test_log()
    check("Live-button-only watchdog does nothing needless on a weak signal",
          not re.search(r"WATCHDOG Weak Channel: .*(reconnected|refill|gave up|safer)", log))
    back_to_main("weak-signal")
    # Full repair: pause & refill (keeping the disk buffer) once the stops repeat.
    set_watchdog_mode("Full repair")
    relays = count_log(r"RELAY new Weak Channel")
    open_test_channel("Weak Channel")
    check("Full repair: Nova pauses to refill when the stops repeat", wait_log(r"WEAK refill started Weak Channel", 150))
    check("Full repair: it plays on by itself once refilled", wait_log(r"WEAK refill done Weak Channel", 80))
    check("Full repair: the stored video was kept (no new disk buffer)", count_log(r"RELAY new Weak Channel") == relays + 1)
    back_to_main("weak-signal-full")
    set_watchdog_mode("Live button only")


def stuck_picture():
    """The picture stops while data keeps arriving: the watchdog does what the Live button does, once."""
    n = count_log(r"READY Stuck Channel")
    open_test_channel("Stuck Channel")
    check("Stuck channel starts", wait_count(r"READY Stuck Channel", n + 1, 30))
    check("Stuck picture: the watchdog jumps to live by itself", wait_log(r"WATCHDOG Stuck Channel: .* -> jumped to live", 70))
    time.sleep(15)
    log = test_log()
    check("…without reconnecting, pausing or switching", not re.search(r"WATCHDOG Stuck Channel: .*(reconnected|refill|gave up|safer|switched)", log))
    check("…and not again and again (at most once every 20 s)", len(re.findall(r"WATCHDOG Stuck Channel: .* -> jumped to live", log)) <= 2)
    back_to_main("stuck-picture")


def open_section(title):
    """Settings sections are folded: open the one named [title] (Settings must be on screen)."""
    for attempt in range(2):
        nd = scroll_to(title, tries=30)
        if nd is None:
            # scroll back to the top and try again
            for _ in range(8):
                adb("shell input swipe 540 700 540 1900 300")
            nd = scroll_to(title, tries=30)
        if nd is None:
            raise AssertionError(f'Settings section "{title}" not found')
        before_open = count_log(r"SETTINGS opened " + re.escape(title))
        adb(f"shell input tap {nd['x']} {nd['y']}")
        time.sleep(1.5)
        if count_log(r"SETTINGS opened " + re.escape(title)) > before_open:
            return
        # it was already open and the tap closed it: open it again
    raise AssertionError(f'Settings section "{title}" did not open')



def parental_pin():
    """With a PIN: Edit / Reorder and changes in Settings ask for it; Settings can still be read."""
    tap("Settings", exact=True)
    open_section("Hidden items & parental control")
    tap("Set PIN", exact=True)
    fill("PIN (4 to 8 digits)", "2468", secret=True)
    fill("Repeat PIN", "2468", secret=True)
    hide_keyboard()
    tap("Save", exact=True)
    time.sleep(1)
    tap("Live TV")
    time.sleep(2)
    n = count_log(r"PIN asked")
    tap("Edit / Reorder")
    check("PIN set: Edit / Reorder asks for the PIN", wait_count(r"PIN asked", n + 1, 8) and find("Parental PIN", 5) is not None)
    tap("Cancel", exact=True)
    check("Without the PIN, Edit mode does not open", find("✓ Done", 3) is None)
    tap("Settings", exact=True)
    open_section("Display")
    check("Settings can still be opened and read", find("Top right", 8) is not None)
    n = count_log(r"PIN asked")
    tap_scrolling("Top left", exact=True)
    check("Changing a setting asks for the PIN", wait_count(r"PIN asked", n + 1, 8))
    fill("PIN", "2468", secret=True)
    hide_keyboard()
    tap("OK", exact=True)
    check("The right PIN is accepted", wait_log(r"PIN accepted", 8))
    n = count_log(r"PIN asked")
    tap_scrolling("Bottom left", exact=True)  # put the clock back
    time.sleep(1)
    check("Once unlocked, changes no longer ask for the PIN", count_log(r"PIN asked") == n)
    # remove the PIN again (still unlocked)
    open_section("Hidden items & parental control")
    tap("Remove PIN", exact=True)
    check("PIN removed", find("Set PIN", 10) is not None)


def scroll_to(text, tries=25):
    """Scrolls down until [text] is on screen (without tapping it)."""
    for _ in range(tries):
        nd = find(text, 5)
        if nd is not None and 150 < nd["y"] < 2250:
            return nd
        adb("shell input swipe 540 1700 540 900 400")
        time.sleep(1)
    return None


def storage_settings():
    tap("Settings", exact=True)
    open_section("Storage")
    scroll_to("Any USB / SD drive")
    check("Storage shows drive choices that work on any box", find("Any USB / SD drive", 5) is not None)
    check("Main location resolves to Download/NovaIPTV (no USB drive on the emulator)", scroll_to("Download/NovaIPTV") is not None)
    files = adb("shell ls -R /sdcard/Download/NovaIPTV/Movies")
    check("Downloaded films are saved in the films location", "Test Film" in files, files.strip()[:160])


def count_log(pattern):
    return len(re.findall(pattern, test_log()))


def wait_count(pattern, n, timeout=40):
    end = time.time() + timeout
    while time.time() < end:
        if count_log(pattern) >= n:
            return True
        time.sleep(2)
    return False


def key(code):
    adb(f"shell input keyevent {code}")
    time.sleep(1)


def remote():
    # ---- live player: buttons act straight away, even with the menu showing
    tap("Live TV")
    time.sleep(2)
    tap("☰")
    tap("News")
    time.sleep(2)
    third = count_log(r"READY Third Channel")
    working = count_log(r"READY Working Channel")
    tap("Working Channel")
    check("Live channel opens for the remote test", wait_count(r"READY Working Channel", working + 1, 45))
    key(19)  # ▲ (TV style: next channel) while the menu is on screen
    check("▲ changes to the next channel even with the menu showing", wait_count(r"READY Third Channel", third + 1, 45))
    key(93)  # PG − (page down) = previous channel
    check("PG − goes to the previous channel", wait_count(r"READY Working Channel", working + 2, 45))
    key(10)  # number 3
    check("Typing 3 jumps to channel 3", wait_count(r"READY Third Channel", third + 2, 45))
    key(21)  # ◀ = groups & channels over the video
    check("◀ opens the groups & channels list over the video", wait_log(r"OVERLAY groups", 15))
    shot("live-group-overlay")
    time.sleep(2)
    key(20)  # ▼ to the next group (Sports): its channels appear on the right
    check("Moving onto another group shows its channels (video keeps playing)", wait_log(r"OVERLAY group Sports", 15))
    sports = count_log(r"READY Sports One")
    key(22)  # ▶ into the channel list
    key(23)  # OK on the channel
    check("Picking a channel from another group plays it", wait_count(r"READY Sports One", sports + 1, 45))
    # recently watched bar (DEL on the MX3 by default)
    key(67)
    bar = wait_log(r"RECENT bar: .*(Third|Working) Channel", 10)
    shot("live-recent-bar")
    check("The recent-channels bar shows the last watched channels", bar)
    readies = count_log(r"READY ")
    key(23)  # OK on the first one
    check("Picking from the recent bar switches channel", wait_count(r"READY ", readies + 1, 45))
    closed = count_log(r"channel list closed")
    key(21)  # open the list again
    key(4)   # Back closes the list
    ra = resumed_activity()
    check("Back closes the list, video keeps playing", "PlayerActivity" in ra and wait_count(r"channel list closed", closed + 1, 10), ra[-100:])
    back_to_main("remote")

    # ---- lists: colour buttons switch zones
    key(184)  # green = Films
    check("🟢 Green opens Films", find("Old Movie", 15) is not None or find("Test Film", 5) is not None)
    key(183)  # red = Live TV
    check("🔴 Red opens Live TV", find("Working Channel", 15) is not None or find("Bob Channel A", 5) is not None)

    # ---- Settings → Remote control
    tap("Settings", exact=True)
    open_section("Remote control")
    tap_scrolling("Live TV player", tries=25)
    time.sleep(1)
    for _ in range(6):  # bring the first action ("Next channel") into view
        nd = find("Next channel", 3)
        if nd is not None and nd["y"] < 1500:
            break
        adb("shell input swipe 540 1600 540 1100 400")
        time.sleep(1)
    check("Remote control settings list the live actions and their buttons",
          find("Next channel", 5) is not None and find("PG +", 5) is not None)
    tap("＋ Button")
    check("＋ Button waits for a remote button", find("Press the remote button", 10) is not None)
    key(131)  # F1
    shot("remote-assigned")
    check("The pressed button is assigned to the action", find("F1", 10) is not None)
    tap("Default")

    # ---- double Back on the main screen closes Nova (default on)
    tap("Live TV")
    time.sleep(2)
    for _ in range(3):  # leave any page / group list first
        if find("Back to the list", 2) is not None:
            tap("Back to the list")
    adb("shell input keyevent 4")
    time.sleep(1)
    still = "MainActivity" in resumed_activity()
    adb("shell input keyevent 4")
    time.sleep(3)
    gone = "MainActivity" not in resumed_activity()
    check("Back once on the main screen keeps Nova open, twice closes it", still and gone, resumed_activity()[-120:])
    adb(f"shell am start -n {PKG}/.ui.MainActivity")
    time.sleep(6)


CODEC_FILMS = ["MKV H264 AAC", "MKV H264 EAC3 51", "MKV H264 AC3 51", "MKV H264 DTS", "MKV HEVC AAC",
               "MKV HEVC10 EAC3", "MKV H264 ASS subs", "MKV H264 SRT subs", "MKV MPEG4 MP3", "MKV H264 TrueHD",
               "MKV H264 FLAC", "MKV AV1 Opus"]


def leave_player_and_page():
    for _ in range(5):
        ra = resumed_activity()
        if "MainActivity" in ra:
            break
        back()
    if find("Directed by", 3) is not None or find("Download to device", 2) is not None:
        back()


def codecs():
    tap("Films")
    time.sleep(2)
    tap("☰")
    tap("Codecs")
    time.sleep(3)
    report = []
    for name in CODEC_FILMS:
        try:
            tap_scrolling(name)
            tap("Play", exact=True)
            ready = wait_log(rf"READY {re.escape(name)}\b", 40)
            time.sleep(4)  # catch errors that come right after the start
            lines = [l.split("NovaTest:", 1)[-1].strip() for l in test_log().splitlines() if name in l]
            failed = any(l.startswith("FORMAT") for l in lines) and not any(l.startswith("RECOVER") for l in lines)
            ok = ready and not failed and "PlayerActivity" in resumed_activity()
            report.append(("PLAYS " if ok else "FAILS ") + name + "\n    " + "\n    ".join(lines[-6:]))
            check(f"Built-in player plays {name}", ok, "; ".join(l for l in lines if not l.startswith("READY"))[:300])
        except Exception as e:
            report.append(f"ERROR {name}: {e}")
            check(f"Built-in player plays {name}", False, str(e)[:200])
        leave_player_and_page()
    with open(os.path.join(OUT, "codecs.txt"), "w") as f:
        f.write("\n".join(report) + "\n")
    tap("☰")
    tap("Action")


steps = [
    ("Install and open", install),
    ("First login", login_first),
    ("Dead channel auto-skip", autoskip),
    ("Catch-up", catchup),
    ("Films", films),
    ("Sorting and Recently added", sorting),
    ("Disk read-ahead and Download & watch", disk_read_ahead),
    ("Series", series),
    ("Long-press = favourite", favorite_long_press),
    ("Poster list with the remote (TV layout)", posters_with_remote),
    ("Favorites", favorites),
    ("Second account merged", second_account),
    ("Edit: hide a channel", edit_mode),
    ("Clock, backup and hidden-items list", new_settings),
    ("Search all accounts", search_all),
    ("Remote control buttons", remote),
    ("Stream watchdog", watchdog),
    ("Live fast start", live_fast_start),
    ("Channel that needs time to start", cold_start),
    ("Weak signal", weak_signal),
    ("Stuck picture: watchdog jumps to live", stuck_picture),
    ("Back to the hardware decoder", lambda: set_video_decoder("Hardware decoder")),
    ("Storage locations", storage_settings),
    ("Parental PIN protects editing and settings", parental_pin),
    ("Open with Nova (videos and M3U)", open_with),
    ("Restore backup from the welcome screen", restore_from_login),
    ("Accounts link and start-up check", accounts_link),
    ("Restore from a link with a password", restore_by_link),
    ("Open link and Share", lambda: open_link()),
    ("Double OK hides a film", lambda: double_ok_hide()),
]

def double_ok_hide():
    """Double OK (or double tap) on a poster asks "Hide …?"; No keeps it, Yes hides it. No code asked."""
    # Portrait: in landscape the emulator letterboxes the app and its pop-up windows don't get the keys.
    try:
        tap("Films")
        time.sleep(3)
        # Phone layout: the "Action" posters are already shown; ▼ moves the remote down onto them.
        n = count_log(r"POSTERS focus in")
        for _ in range(4):
            key(20)
            if count_log(r"POSTERS focus in") > n:
                break
        time.sleep(1)
        asked = count_log(r"HIDE ask")
        hidden = count_log(r"HIDDEN ")
        adb("shell input keyevent 23 23")
        check("Double OK on a poster asks to hide it", wait_count(r"HIDE ask", asked + 1, 8))
        shot("hide-ask")
        check("The window offers No / Yes, hide", find("Yes, hide", 5, exact=True) is not None and find("No", 3, exact=True) is not None)
        check("Double OK does not open the film page", find("Download & watch", 2) is None)
        wait_log(r"HIDE ask: remote on No", 5)
        key(23)  # OK on "No" (where the remote starts)
        time.sleep(2)
        check("OK right away = No: the film is kept", count_log(r"HIDDEN ") == hidden and find("Yes, hide", 2, exact=True) is None)
        check("…and the remote is back on the same poster", wait_log(r"POSTERS remote back on the opened poster", 8))
        adb("shell input keyevent 23 23")
        wait_count(r"HIDE ask", asked + 2, 8)
        tap("Yes, hide", 5, exact=True)
        check("Yes hides it at once, without any code", wait_count(r"HIDDEN ", hidden + 1, 8) and find("Parental", 2) is None)
    finally:
        pass


def open_link():
    """The "🔗 Link" tab plays any video link; Share → Nova offers Play / Download & watch / Download."""
    tap("Link", 15)
    fill("Video link", SERVER + "/movie/alice/secret/301.mp4")
    hide_keyboard()
    n = count_log(r"READY ")
    tap("▶ Play", 10)
    check("Open link: a pasted link plays", wait_count(r"READY ", n + 1, 40))
    back_to_main("open-link")
    tap("Link", 15)
    check("Open link: the link is listed under Recent links", find("301.mp4", 10) is not None)
    adb(f"shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "
        f"Watch:{SERVER}/movie/alice/secret/302.mp4 -n {PKG}/.ui.OpenWithActivity")
    check("Share → Nova: the choice window opens", wait_log(r"SHARE received link", 15) and find("Play now", 10) is not None)
    shot("share-choice")
    tap("Download only", 10)
    check("Share → Download only adds a download", wait_log(r"LINK download 302.mp4", 10))


def restart_check():
    adb(f"shell am force-stop {PKG}")
    adb(f"shell am start -n {PKG}/.ui.MainActivity")
    check("Restart: the start-up check runs", wait_log(r"STARTUP 1 accounts, 0 with problems", 40))
    check("Restart: Live TV opens", find("Working Channel", 30) is not None)
    shot("after-restart")
    check("Restart: no crash", not crashed())


if os.environ.get("NOVA_MODE") == "stuck":
    steps = [
        ("Install and open", install),
        ("First login", login_first),
        ("Software decoder", lambda: set_video_decoder("Software decoder")),
        ("Stuck picture: watchdog jumps to live", stuck_picture),
    ]

def web_first_start():
    """Web tab before any server: the welcome tile's address box opens a real page; bars stay visible."""
    check("Welcome screen with the Web tile", find("Web browser", 30) is not None)
    shot("welcome-tiles")
    nd = find("Search or type a web address", 10)
    if nd:
        adb(f"shell input tap {nd['x']} {nd['y']}")
        time.sleep(1.5)
        type_text(SERVER + "/web/test.html")
        adb("shell input keyevent 66")
        time.sleep(1)
    found = find("Nova Web Test page", 30)
    shot("web-page")
    check("Web: the page is shown", found is not None)
    check("Web: the top row stays visible (Live TV)", find("Live TV", 5) is not None)
    check("Web: the button row stays visible (List)", find("List", 5) is not None)
    time.sleep(4)
    check("Web: the page's video is found", find("🎬 1", 8) is not None or find("🎬 2", 3) is not None)
    shot("web-video-found")
    adb("shell input keyevent 4")
    adb("shell input keyevent 4")
    time.sleep(2)
    check("Web: Back twice leaves the Web tab", find("Add your IPTV server", 10) is not None)
    shot("web-left")
    # ⬇ Downloads before any server (second row).
    check("No server: ⬇ Downloads is shown", find("Downloads", 10) is not None)
    tap("Downloads", 10)
    time.sleep(2)
    shot("downloads-no-server")
    check("No server: the Downloads screen opens", find("Live TV", 5) is not None and find("Add your IPTV server", 3) is None)
    adb("shell input keyevent 4")
    time.sleep(2)
    check("No server: Back returns to the welcome screen", find("Add your IPTV server", 10) is not None)


def web_with_server():
    tap("Web", 10)
    time.sleep(2)
    shot("web-tab")
    check("Web tab: address row and buttons visible", (find("Search or type a web address", 10) is not None or find("Paste", 5) is not None) and find("List", 5) is not None)
    adb(f"shell am start -a android.intent.action.VIEW -d {SERVER}/web/test.html -n {PKG}/.ui.OpenWithActivity")
    check("Open with: a web page opens in the Web tab", find("Nova Web Test page", 30) is not None)
    shot("web-open-with")
    # Film-site links: a "new tab" card, and a card behind a pop-under script.
    adb(f"shell am start -a android.intent.action.VIEW -d {SERVER}/web/cards.html -n {PKG}/.ui.OpenWithActivity")
    check("Web: film-site page opens", find("Nova Cards page", 30) is not None)
    time.sleep(2)
    tap("Film One card", 10)
    check("Web: a “new tab” link asks first", find("Open this link in a new tab?", 10) is not None)
    shot("web-ask-newtab")
    tap("Open in new tab", 10)
    check("Web: “Open in new tab” opens the film page", find("Film One page", 15) is not None)
    check("Web: the tabs button shows 2 tabs", find("▢ 2", 8) is not None)
    shot("web-card-newtab")
    adb(f"shell am start -a android.intent.action.VIEW -d {SERVER}/web/cards.html -n {PKG}/.ui.OpenWithActivity")
    find("Nova Cards page", 20)
    time.sleep(2)
    tap("Film Two card", 10)
    check("Web: a link behind a pop-under script opens the film page", find("Film Two page", 15) is not None)
    # Clean history: a redirect page is skipped by ←, ⟳ reloads the page you see.
    adb(f"shell am start -a android.intent.action.VIEW -d {SERVER}/web/test.html -n {PKG}/.ui.OpenWithActivity")
    find("Nova Web Test page", 30)
    time.sleep(3)
    adb(f"shell am start -a android.intent.action.VIEW -d {SERVER}/web/hop.html -n {PKG}/.ui.OpenWithActivity")
    check("Web: the redirect page leads to the film page", find("Film Two page", 30) is not None)
    time.sleep(3)
    tap("←", 10, exact=True)
    check("Web: ← skips the redirect page and goes back to the page before", find("Nova Web Test page", 20) is not None)
    shot("web-back-clean")
    tap("⟳", 10, exact=True)
    time.sleep(3)
    check("Web: ⟳ reloads the page you see", find("Nova Web Test page", 20) is not None)
    # A video host refused by the line: Nova's message in the player box, then the next server works without refreshing.
    adb(f"shell am start -a android.intent.action.VIEW -d {SERVER}/web/servers.html -n {PKG}/.ui.OpenWithActivity")
    check("Web: servers page opens", find("Nova Servers page", 30) is not None)
    time.sleep(2)
    tap("Server A", 10)
    check("Web: a refused host shows Nova's message", find("blocked by your internet connection", 25) is not None)
    shot("web-blocked-host")
    if find("Server B", 3) is None:
        adb("shell input swipe 540 700 540 1500 300")
        time.sleep(1.5)
    nb = find("Server B", 5)
    if nb:
        adb(f"shell input tap {nb['x']} {nb['y']}")
        check("Web: the next server works without refreshing", find("Film Two page", 20) is not None)
    else:
        note("Server B button not visible in the test's screen reader (page shown only as its player box)")
    shot("web-next-server")


if os.environ.get("NOVA_MODE") == "web":
    steps = [
        ("Install and open", install),
        ("Web before any server", web_first_start),
        ("First login", login_first),
        ("Web tab with a server", web_with_server),
    ]

if os.environ.get("NOVA_MODE") == "short":
    steps = [
        ("Install and open", install),
        ("First login", login_first),
        ("Restart with an account", restart_check),
        ("Open link and Share", open_link),
        ("Double OK hides a film", double_ok_hide),
    ]

for title, fn in steps:
    step(title, fn)

check("No crash in the whole test", not crashed())
with open(os.path.join(OUT, "logcat.txt"), "w") as f:
    f.write(adb("logcat -d -v time", 60))
with open(os.path.join(OUT, "events.txt"), "w") as f:
    f.write(adb("logcat -d -b events -v time", 60))
with open(os.path.join(OUT, "crash.txt"), "w") as f:
    f.write(adb("logcat -d -b crash", 30))

passed = sum(1 for c in checks if c[1])
lines = [f"{passed}/{len(checks)} checks passed", ""] + [
    ("PASS  " if ok else "FAIL  ") + name + (f"  ({d})" if d else "") for name, ok, d in checks
]
with open(os.path.join(OUT, "report.txt"), "w") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
sys.exit(0 if passed == len(checks) else 1)
