using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Reflection;
using System.Text;
using System.Windows;
using System.Windows.Input;
using Microsoft.Win32;
using Nova.Core;

namespace Nova.App
{
    /// <summary>Name, version (with build number) and signature.</summary>
    public static class Brand
    {
        public const string Signature = "Designed by A.Hamrouni";

        /// <summary>"2.0 (build 12)".</summary>
        public static string Version
        {
            get
            {
                var a = Assembly.GetExecutingAssembly();
                var info = a.GetCustomAttributes(typeof(AssemblyInformationalVersionAttribute), false)
                    .OfType<AssemblyInformationalVersionAttribute>().FirstOrDefault();
                if (info != null && info.InformationalVersion.Contains("build")) return info.InformationalVersion;
                var v = a.GetName().Version;
                return v.Major + "." + v.Minor + " (build " + v.Build + ")";
            }
        }
    }

    // ===================================================================== keys

    public enum KeyArea { Live, Vod, Lists }

    /// <summary>One thing a remote / keyboard button can do (TiviMate style, like the Android app).</summary>
    public class KeyAction
    {
        public string Id;
        public KeyArea Area;
        public string Label;
        public string[] Defaults;
    }

    /// <summary>
    /// Buttons → actions, changeable in Settings → Remote control. Keys are WPF key names with optional
    /// "Ctrl+" / "Shift+" / "Alt+" in front. A button is only used by one action per area.
    /// </summary>
    public static class KeyMap
    {
        public static readonly List<KeyAction> Actions = new List<KeyAction>
        {
            // ---- live TV
            A("live_next", KeyArea.Live, "Next channel", "Up", "PageUp", "MediaNextTrack"),
            A("live_prev", KeyArea.Live, "Previous channel", "Down", "PageDown", "MediaPreviousTrack"),
            A("live_list", KeyArea.Live, "Groups & channels list (over the video)", "Left"),
            A("live_recent", KeyArea.Live, "Recently watched channels bar", "B"),
            A("live_info", KeyArea.Live, "Show info & buttons", "Enter", "Right", "I", "Apps"),
            A("live_recall", KeyArea.Live, "Last watched channel (recall)", "Q"),
            A("live_pause", KeyArea.Live, "Pause / play", "Space", "MediaPlayPause", "Play", "Pause"),
            A("live_rewind", KeyArea.Live, "Rewind 30 s (disk buffer)", "Ctrl+Left", "J"),
            A("live_forward", KeyArea.Live, "Forward 30 s (disk buffer)", "Ctrl+Right", "K"),
            A("live_golive", KeyArea.Live, "Jump to live (fixes a stuck picture)", "L", "End"),
            A("live_record", KeyArea.Live, "Record", "R"),
            A("live_favorite", KeyArea.Live, "Add / remove favorite", "Ctrl+D", "BrowserFavorites"),
            A("live_aspect", KeyArea.Live, "Aspect ratio", "Z"),
            A("live_tracks", KeyArea.Live, "Audio, subtitles & stream info", "T"),
            A("live_audio", KeyArea.Live, "Next audio track", "A"),
            A("live_subs", KeyArea.Live, "Next subtitle", "S"),
            A("live_external", KeyArea.Live, "Open in external player", "E"),
            A("live_fullscreen", KeyArea.Live, "Full screen on / off", "F", "F11"),
            A("live_mute", KeyArea.Live, "Sound on / off", "M", "VolumeMute"),
            A("live_volup", KeyArea.Live, "Volume up", "OemPlus", "Add", "VolumeUp"),
            A("live_voldown", KeyArea.Live, "Volume down", "OemMinus", "Subtract", "VolumeDown"),
            A("live_close", KeyArea.Live, "Close the player", "Escape", "Back", "BrowserBack"),
            // ---- films & series
            A("vod_play_pause", KeyArea.Vod, "Play / pause", "Space", "Enter", "MediaPlayPause", "Play", "Pause"),
            A("vod_back", KeyArea.Vod, "Back 10 s", "Left"),
            A("vod_forward", KeyArea.Vod, "Forward 10 s", "Right"),
            A("vod_back_long", KeyArea.Vod, "Back 1 minute", "Ctrl+Left", "J"),
            A("vod_forward_long", KeyArea.Vod, "Forward 1 minute", "Ctrl+Right", "K"),
            A("vod_controls", KeyArea.Vod, "Show controls", "Up", "Down", "I", "Apps"),
            A("vod_next", KeyArea.Vod, "Next episode", "PageUp", "MediaNextTrack", "N"),
            A("vod_prev", KeyArea.Vod, "Previous episode", "PageDown", "MediaPreviousTrack", "P"),
            A("vod_start", KeyArea.Vod, "Start from the beginning", "Home"),
            A("vod_download", KeyArea.Vod, "Download", "D"),
            A("vod_favorite", KeyArea.Vod, "Add / remove favorite", "Ctrl+D", "BrowserFavorites"),
            A("vod_aspect", KeyArea.Vod, "Aspect ratio", "Z"),
            A("vod_tracks", KeyArea.Vod, "Audio, subtitles & stream info", "T"),
            A("vod_audio", KeyArea.Vod, "Next audio track", "A"),
            A("vod_subs", KeyArea.Vod, "Next subtitle", "S"),
            A("vod_external", KeyArea.Vod, "Open in external player", "E"),
            A("vod_fullscreen", KeyArea.Vod, "Full screen on / off", "F", "F11"),
            A("vod_mute", KeyArea.Vod, "Sound on / off", "M", "VolumeMute"),
            A("vod_volup", KeyArea.Vod, "Volume up", "OemPlus", "Add", "VolumeUp"),
            A("vod_voldown", KeyArea.Vod, "Volume down", "OemMinus", "Subtract", "VolumeDown"),
            A("vod_close", KeyArea.Vod, "Close the player", "Escape", "Back", "BrowserBack"),
            // ---- lists & menus
            A("list_live", KeyArea.Lists, "Go to Live TV", "Ctrl+D1", "F1"),
            A("list_films", KeyArea.Lists, "Go to Films", "Ctrl+D2", "F2"),
            A("list_series", KeyArea.Lists, "Go to Series", "Ctrl+D3", "F3"),
            A("list_search", KeyArea.Lists, "Search", "Ctrl+F", "Ctrl+D4", "BrowserSearch", "F4"),
            A("list_link", KeyArea.Lists, "Open link", "Ctrl+D5", "Ctrl+O"),
            A("list_recent", KeyArea.Lists, "Recently watched", "Ctrl+D6", "F6"),
            A("list_downloads", KeyArea.Lists, "Downloads", "Ctrl+D7", "F7"),
            A("list_settings", KeyArea.Lists, "Settings", "Ctrl+D8", "F8"),
            A("list_last", KeyArea.Lists, "Play last watched channel", "Ctrl+L", "F9"),
            A("list_reload", KeyArea.Lists, "Download the lists again", "F5"),
            A("list_edit", KeyArea.Lists, "Edit (hide / reorder) on or off", "Ctrl+E"),
            A("list_sort", KeyArea.Lists, "Sort this list", "Ctrl+S"),
        };

        private static KeyAction A(string id, KeyArea area, string label, params string[] defaults)
        {
            return new KeyAction { Id = id, Area = area, Label = label, Defaults = defaults };
        }

        public static KeyAction Get(string id) { return Actions.FirstOrDefault(a => a.Id == id); }

        /// <summary>The built-in buttons; with list-style arrows ▲ ▼ are the other way round for channels.</summary>
        public static List<string> DefaultsOf(KeyAction a)
        {
            var list = a.Defaults.ToList();
            if (!App.Store.Settings.TvArrows && (a.Id == "live_next" || a.Id == "live_prev"))
                list = list.Select(k => k == "Up" ? "Down" : k == "Down" ? "Up" : k).ToList();
            return list;
        }

        public static List<string> KeysFor(string id)
        {
            List<string> custom;
            if (App.Store.State.KeyMap.TryGetValue(id, out custom) && custom != null) return custom;
            var a = Get(id);
            return a == null ? new List<string>() : DefaultsOf(a);
        }

        /// <summary>"Ctrl+Left", "F", "Space"… for a key press.</summary>
        public static string NameOf(KeyEventArgs e)
        {
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            if (key == Key.ImeProcessed) key = e.ImeProcessedKey;
            if (key == Key.LeftCtrl || key == Key.RightCtrl || key == Key.LeftShift || key == Key.RightShift ||
                key == Key.LeftAlt || key == Key.RightAlt || key == Key.LWin || key == Key.RWin) return null;
            if (key == Key.Return) key = Key.Enter;
            var sb = new StringBuilder();
            var mods = Keyboard.Modifiers;
            if ((mods & ModifierKeys.Control) != 0) sb.Append("Ctrl+");
            if ((mods & ModifierKeys.Shift) != 0) sb.Append("Shift+");
            if ((mods & ModifierKeys.Alt) != 0) sb.Append("Alt+");
            sb.Append(key.ToString());
            return sb.ToString();
        }

        /// <summary>The action of this key press in an area, or null.</summary>
        public static string ActionFor(KeyArea area, KeyEventArgs e)
        {
            var name = NameOf(e);
            if (name == null) return null;
            foreach (var a in Actions)
                if (a.Area == area && KeysFor(a.Id).Contains(name)) return a.Id;
            return null;
        }

        /// <summary>Adds a key to an action (taken away from other actions of the same area). Returns where it was taken from.</summary>
        public static List<string> Assign(string id, string key)
        {
            var a = Get(id);
            var taken = new List<string>();
            if (a == null || string.IsNullOrEmpty(key)) return taken;
            var map = App.Store.State.KeyMap;
            foreach (var other in Actions.Where(o => o.Area == a.Area && o.Id != id))
            {
                var keys = KeysFor(other.Id);
                if (keys.Contains(key)) { map[other.Id] = keys.Where(k => k != key).ToList(); taken.Add(other.Label); }
            }
            var mine = KeysFor(id);
            if (!mine.Contains(key)) mine = mine.Concat(new[] { key }).ToList();
            map[id] = mine;
            App.Store.SaveState();
            return taken;
        }

        public static void ClearKeys(string id)
        {
            App.Store.State.KeyMap[id] = new List<string>();
            App.Store.SaveState();
        }

        public static void ResetAll()
        {
            App.Store.State.KeyMap.Clear();
            App.Store.SaveState();
        }

        /// <summary>"Ctrl+Left" → "Ctrl + ←" for the screen.</summary>
        public static string Pretty(string key)
        {
            return key.Replace("+", " + ").Replace("Left", "←").Replace("Right", "→").Replace("Up", "↑").Replace("Down", "↓")
                .Replace("Page↑", "Page ↑").Replace("Page↓", "Page ↓")
                .Replace("OemPlus", "+").Replace("OemMinus", "−").Replace("Add", "Num +").Replace("Subtract", "Num −")
                .Replace("D1", "1").Replace("D2", "2").Replace("D3", "3").Replace("D4", "4").Replace("D5", "5")
                .Replace("D6", "6").Replace("D7", "7").Replace("D8", "8").Replace("Escape", "Esc").Replace("Back", "Backspace")
                .Replace("BrowserBackspace", "Browser Back");
        }
    }

    // ===================================================================== external players

    public class PlayerApp
    {
        public string Name;
        public string Path;
        public override string ToString() { return Name; }
    }

    /// <summary>Video players installed on this PC (VLC, MPC-HC, MPC-BE, PotPlayer, Kodi, SMPlayer, mpv…).</summary>
    public static class ExternalPlayers
    {
        public const string Builtin = "builtin";

        public static List<PlayerApp> Installed()
        {
            var list = new List<PlayerApp>();
            Action<string, string> add = (name, path) =>
            {
                if (!string.IsNullOrEmpty(path) && File.Exists(path) && list.All(p => !string.Equals(p.Path, path, StringComparison.OrdinalIgnoreCase)))
                    list.Add(new PlayerApp { Name = name, Path = path });
            };
            var pf = Environment.GetEnvironmentVariable("ProgramW6432") ?? Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            var pf86 = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86);
            var roots = new[] { pf, pf86 }.Where(r => !string.IsNullOrEmpty(r)).Distinct().ToList();

            add("VLC", Reg(RegistryHive.LocalMachine, @"SOFTWARE\VideoLAN\VLC", "", RegistryView.Registry64));
            add("VLC", Reg(RegistryHive.LocalMachine, @"SOFTWARE\VideoLAN\VLC", "", RegistryView.Registry32));
            foreach (var r in roots) add("VLC", Path.Combine(r, "VideoLAN", "VLC", "vlc.exe"));
            add("MPC-HC", Reg(RegistryHive.CurrentUser, @"Software\MPC-HC\MPC-HC", "ExePath", RegistryView.Default));
            foreach (var r in roots)
            {
                add("MPC-HC", Path.Combine(r, "MPC-HC", "mpc-hc64.exe"));
                add("MPC-HC", Path.Combine(r, "MPC-HC", "mpc-hc.exe"));
                add("MPC-HC", Path.Combine(r, "K-Lite Codec Pack", "MPC-HC64", "mpc-hc64.exe"));
                add("MPC-BE", Path.Combine(r, "MPC-BE x64", "mpc-be64.exe"));
                add("MPC-BE", Path.Combine(r, "MPC-BE", "mpc-be.exe"));
                add("PotPlayer", Path.Combine(r, "DAUM", "PotPlayer", "PotPlayerMini64.exe"));
                add("PotPlayer", Path.Combine(r, "DAUM", "PotPlayer", "PotPlayerMini.exe"));
                add("PotPlayer", Path.Combine(r, "PotPlayer", "PotPlayerMini64.exe"));
                add("Kodi", Path.Combine(r, "Kodi", "kodi.exe"));
                add("SMPlayer", Path.Combine(r, "SMPlayer", "smplayer.exe"));
                add("mpv", Path.Combine(r, "mpv", "mpv.exe"));
            }
            var custom = App.Store.Settings.ExternalPlayer;
            if (!string.IsNullOrEmpty(custom) && custom != Builtin) add(Path.GetFileNameWithoutExtension(custom), custom);
            foreach (var p in new[] { App.Store.Settings.PlayerLive, App.Store.Settings.PlayerFilms, App.Store.Settings.PlayerSeries,
                                      App.Store.Settings.PlayerCatchup, App.Store.Settings.PlayerFiles })
                if (!string.IsNullOrEmpty(p) && p != Builtin) add(Path.GetFileNameWithoutExtension(p), p);
            return list;
        }

        private static string Reg(RegistryHive hive, string key, string value, RegistryView view)
        {
            try
            {
                using (var b = RegistryKey.OpenBaseKey(hive, view))
                using (var k = b.OpenSubKey(key))
                {
                    var v = k == null ? null : k.GetValue(value) as string;
                    if (string.IsNullOrEmpty(v)) return null;
                    if (value == "" && Directory.Exists(v)) return Path.Combine(v, "vlc.exe");
                    return v;
                }
            }
            catch { return null; }
        }

        /// <summary>The player chosen for a kind of content ("live", "movie", "series", "catchup", "file"), or null for the built-in one.</summary>
        public static string ChosenFor(string kind)
        {
            var s = App.Store.Settings;
            string p;
            switch (kind)
            {
                case "live": p = s.PlayerLive; break;
                case "movie": p = s.PlayerFilms; break;
                case "series": p = s.PlayerSeries; break;
                case "catchup": p = s.PlayerCatchup; break;
                default: p = s.PlayerFiles; break;
            }
            return string.IsNullOrEmpty(p) || p == Builtin || !File.Exists(p) ? null : p;
        }

        /// <summary>The player for "open in external player" and as the fallback: the chosen one, else the first installed.</summary>
        public static string Fallback()
        {
            var s = App.Store.Settings.ExternalPlayer;
            if (!string.IsNullOrEmpty(s) && s != Builtin && File.Exists(s)) return s;
            var first = Installed().FirstOrDefault();
            return first == null ? null : first.Path;
        }

        public static bool Launch(string player, string url, string title, IDictionary<string, string> headers)
        {
            if (string.IsNullOrEmpty(player) || string.IsNullOrEmpty(url)) return false;
            var exe = Path.GetFileName(player).ToLowerInvariant();
            var args = new StringBuilder("\"" + url + "\"");
            string ua = null, referer = null;
            if (headers != null) { headers.TryGetValue("User-Agent", out ua); headers.TryGetValue("Referer", out referer); }
            if (exe == "vlc.exe")
            {
                if (!string.IsNullOrEmpty(title)) args.Append(" --meta-title=\"" + title.Replace("\"", "'") + "\"");
                args.Append(" --http-user-agent=\"" + (ua ?? App.Store.Settings.UserAgent) + "\"");
                if (!string.IsNullOrEmpty(referer)) args.Append(" --http-referrer=\"" + referer + "\"");
            }
            else if (exe == "mpv.exe")
            {
                args.Append(" --user-agent=\"" + (ua ?? App.Store.Settings.UserAgent) + "\"");
                if (!string.IsNullOrEmpty(referer)) args.Append(" --referrer=\"" + referer + "\"");
            }
            try
            {
                Process.Start(new ProcessStartInfo(player, args.ToString()) { UseShellExecute = false });
                return true;
            }
            catch (Exception e)
            {
                App.Log(e);
                return false;
            }
        }
    }

    // ===================================================================== VPN

    public static class Vpn
    {
        private static readonly string[] Words = { "vpn", "tap-", "tap ", "tun", "wireguard", "wintun", "openvpn", "nordlynx", "fortinet", "anyconnect",
                                                   "proton", "expressvpn", "surfshark", "mullvad", "cisco", "globalprotect", "pangp", "zerotier", "hamachi", "softether", "windscribe", "pptp", "l2tp", "ikev2" };

        /// <summary>Name of the VPN connection that is on, or null.</summary>
        public static string Active()
        {
            try
            {
                foreach (var n in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (n.OperationalStatus != OperationalStatus.Up) continue;
                    var text = (n.Name + " " + n.Description).ToLowerInvariant();
                    if (n.NetworkInterfaceType == NetworkInterfaceType.Ppp || Words.Any(w => text.Contains(w)))
                    {
                        if (n.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                        return n.Name + (string.IsNullOrEmpty(n.Description) ? "" : " (" + n.Description + ")");
                    }
                }
            }
            catch { }
            return null;
        }

        /// <summary>True when it may play (VPN not required, or connected); otherwise explains why not.</summary>
        public static bool Allow(Window owner)
        {
            if (!App.Store.Settings.RequireVpn || Active() != null) return true;
            NovaDialog.Info(owner, "VPN is off", "Nova is set to play only when a VPN is connected. Turn your VPN on, then try again.\n\n(Settings › VPN to change this.)");
            return false;
        }
    }

    // ===================================================================== parental PIN

    /// <summary>The parental PIN lock: after the PIN, changes are allowed for 5 minutes (or until "Lock now").</summary>
    public static class PinGate
    {
        private static DateTime _until = DateTime.MinValue;

        public static bool IsUnlocked { get { return !App.Store.HasPin || DateTime.UtcNow < _until; } }

        public static void Lock() { _until = DateTime.MinValue; var h = Changed; if (h != null) h(); }

        public static event Action Changed;

        /// <summary>Asks for the PIN when needed. True when changes are allowed.</summary>
        public static bool Unlock(Window owner, string why)
        {
            if (IsUnlocked) return true;
            var pin = NovaDialog.AskText(owner, "Enter your PIN", why, null, true, "Unlock",
                p => App.Store.CheckPin(p) ? null : "Wrong PIN");
            if (pin == null) return false;
            _until = DateTime.UtcNow.AddMinutes(5);
            var h = Changed; if (h != null) h();
            return true;
        }
    }

    // ===================================================================== "Open with Nova"

    /// <summary>Adds "Open with Nova" to the right-click menu of video files (for this Windows user; the installer does it for everyone).</summary>
    public static class ShellMenu
    {
        public static readonly string[] Extensions = { ".mp4", ".mkv", ".avi", ".ts", ".m2ts", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".mpg", ".mpeg", ".m3u", ".m3u8", ".3gp" };

        public static void Add(string exe)
        {
            foreach (var ext in Extensions)
            {
                using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\SystemFileAssociations\" + ext + @"\shell\NovaIPTV"))
                {
                    k.SetValue("", "Open with Nova");
                    k.SetValue("Icon", "\"" + exe + "\",0");
                    using (var c = k.CreateSubKey("command")) c.SetValue("", "\"" + exe + "\" \"%1\"");
                }
            }
        }

        public static void Remove()
        {
            foreach (var ext in Extensions)
            {
                try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\SystemFileAssociations\" + ext + @"\shell\NovaIPTV", false); } catch { }
            }
        }

        public static bool IsAdded()
        {
            using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Classes\SystemFileAssociations\.mp4\shell\NovaIPTV"))
                if (k != null) return true;
            using (var k = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Classes\SystemFileAssociations\.mp4\shell\NovaIPTV"))
                return k != null;
        }
    }
}
