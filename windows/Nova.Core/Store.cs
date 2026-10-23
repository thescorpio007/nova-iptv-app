using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Nova.Core
{
    /// <summary>Settings of the Windows app (saved as settings.json). Names follow the Android app where both have them.</summary>
    public class Settings
    {
        // ---- playback
        /// <summary>"ts" (MPEG-TS) or "m3u8" (HLS) for live channels.</summary>
        public string LiveFormat { get; set; } = "ts";
        /// <summary>Milliseconds VLC buffers before playing a network stream (disk buffer off).</summary>
        public int NetworkCachingMs { get; set; } = 3000;
        /// <summary>Use the graphics card to decode video. Off = processor only (safer on very old PCs).</summary>
        public bool HardwareDecoding { get; set; } = true;
        public string UserAgent { get; set; } = Store.DefaultUserAgent;
        /// <summary>Live TV: Up = next channel, Down = previous (TV style). False = list style.</summary>
        public bool TvArrows { get; set; } = true;
        /// <summary>Back (Esc / Backspace) twice on Live TV closes Nova.</summary>
        public bool DoubleBackExit { get; set; } = true;
        /// <summary>Plays the highlighted channel in the small preview box on Live TV.</summary>
        public bool LivePreview { get; set; } = true;
        /// <summary>Automatic reconnection attempts when a live stream drops (disk buffer off).</summary>
        public int ReconnectAttempts { get; set; } = 5;
        /// <summary>Start the player in full screen.</summary>
        public bool PlayerFullScreen { get; set; } = true;
        /// <summary>Typing a channel number in the live player jumps to that channel.</summary>
        public bool NumberKeys { get; set; } = true;

        // ---- disk buffer and watchdog
        /// <summary>Download every stream to disk first and play it from there.</summary>
        public bool UseDiskBuffer { get; set; } = true;
        /// <summary>Folder for the buffers; empty = Downloads\NovaIPTV\Buffer.</summary>
        public string BufferFolder { get; set; } = "";
        /// <summary>Folder for downloads and recordings; empty = Downloads\NovaIPTV.</summary>
        public string DownloadFolder { get; set; } = "";
        /// <summary>Live TV disk buffer length in minutes (5, 10, 15): what you can pause / rewind.</summary>
        public int LiveDiskMinutes { get; set; } = 15;
        /// <summary>Safety limit of a live channel's disk buffer in MB (the length above decides first).</summary>
        public int LiveBufferMb { get; set; } = 3000;
        /// <summary>Films &amp; series read-ahead on disk (the films / episodes cache), in MB.</summary>
        public int FilmBufferMb { get; set; } = 1024;
        /// <summary>Seconds of safety cushion kept behind live (built while you watch with the fast start).</summary>
        public int CushionSeconds { get; set; } = 15;
        /// <summary>"fast" (start near live, build the cushion while watching) or "safe" (wait for the whole cushion first).</summary>
        public string LiveStart { get; set; } = "fast";
        /// <summary>While watching, play 4 % slower until you are this many seconds behind live (0 = off). 120 = 2 min.</summary>
        public int LiveGrowTarget { get; set; } = 120;
        /// <summary>When the line stays too slow for a channel: "similar" (propose similar channels), "switch" (other server) or "stay".</summary>
        public string LiveSlowAction { get; set; } = "similar";
        /// <summary>Status dots (green / red / orange / grey) on channels, films and episodes.</summary>
        public bool StatusDots { get; set; } = true;
        /// <summary>A channel / video that can't be played here: "ask" (offer the external player, 10 s), "none", or a player program.</summary>
        public string FailApp { get; set; } = "ask";
        /// <summary>When to offer it: "format" (Nova's player can't decode it) or "any" (any failure).</summary>
        public string FailAppWhen { get; set; } = "format";
        /// <summary>Films &amp; series: press Back twice to stop the video.</summary>
        public bool DoubleBackVod { get; set; } = true;
        /// <summary>Watchdog: seconds without data before a stream counts as frozen and is reconnected.</summary>
        public int StallSeconds { get; set; } = 10;
        /// <summary>Jump to the next channel when a channel sends nothing at all.</summary>
        public bool AutoSkip { get; set; } = true;
        /// <summary>Seconds a channel may take to send its first video before it counts as dead.</summary>
        public int DeadSeconds { get; set; } = 8;
        /// <summary>"live" = a stuck picture jumps back to live; "full" = also safer settings, then the other account.</summary>
        public string WatchdogMode { get; set; } = "live";
        /// <summary>Full repair: try the same channel on your other account when it keeps failing.</summary>
        public bool WatchdogOtherAccount { get; set; } = true;

        // ---- downloads
        /// <summary>Connections for downloads from your IPTV servers (never more than the server allows, one kept free).</summary>
        public int DlConnServers { get; set; } = 1;
        /// <summary>Connections for downloads from other links.</summary>
        public int DlConnLinks { get; set; } = 8;
        /// <summary>Speed limit in MB/s (0 = no limit).</summary>
        public int DlSpeedServers { get; set; }
        public int DlSpeedLinks { get; set; }

        // ---- players
        /// <summary>Player per kind of content: "builtin" or the full path of a player program.</summary>
        public string PlayerLive { get; set; } = "builtin";
        public string PlayerFilms { get; set; } = "builtin";
        public string PlayerSeries { get; set; } = "builtin";
        public string PlayerCatchup { get; set; } = "builtin";
        public string PlayerFiles { get; set; } = "builtin";
        /// <summary>When the built-in player can't play something: open the external player (true) or show a message.</summary>
        public bool ExternalOnError { get; set; } = true;
        /// <summary>The external player used for "open in external player" and as fallback ("" = first one found).</summary>
        public string ExternalPlayer { get; set; } = "";
        /// <summary>↗ "Play with…" list: your order of player programs (full paths, top first).</summary>
        public List<string> ExtOrder { get; set; } = new List<string>();
        /// <summary>Players switched off in the "Play with…" list.</summary>
        public List<string> ExtHidden { get; set; } = new List<string>();
        /// <summary>↗ External player: "ask" (Nova's "Play with…" list) or "first" (always the first one of your list).</summary>
        public string ExtMode { get; set; } = "ask";
        /// <summary>🔊 Volume boost for everything, in % (100 = off, up to 200).</summary>
        public int BoostAll { get; set; } = 100;

        // ---- subtitles
        /// <summary>Subtitle size (Android scale, 10–48; 20 = normal).</summary>
        public int SubtitleSize { get; set; } = 20;
        /// <summary>"white", "yellow", "green", "cyan".</summary>
        public string SubtitleColor { get; set; } = "white";
        public bool SubtitleBackground { get; set; }

        // ---- display
        public bool LiveLogos { get; set; } = true;
        /// <summary>"small", "medium", "large" posters for films / series.</summary>
        public string PosterSize { get; set; } = "medium";
        public bool PosterTitles { get; set; } = true;
        /// <summary>Poster columns, 0 = automatic.</summary>
        public int PosterColumns { get; set; }
        /// <summary>Open a group as soon as it is highlighted with the arrows (no Enter needed).</summary>
        public bool OpenGroupOnFocus { get; set; } = true;
        /// <summary>Clock in the player: "off", "tl", "tr", "bl", "br".</summary>
        public string ClockPosition { get; set; } = "bl";
        /// <summary>Live TV rows: "compact", "normal", "large".</summary>
        public string LiveRowSize { get; set; } = "normal";
        /// <summary>Show the programme on air under each channel.</summary>
        public bool LiveNowLine { get; set; }
        /// <summary>How see-through the lists over the video are, in %.</summary>
        public int OverlayTransparency { get; set; } = 25;

        // ---- sorting: "custom", "added", "number", "name", "year", "rating"
        public string SortLive { get; set; } = "custom";
        public string SortMovie { get; set; } = "rating";
        public string SortSeries { get; set; } = "custom";

        // ---- start-up and VPN
        /// <summary>Start the last channel or video you watched when Nova opens.</summary>
        public bool AutoPlayLast { get; set; }
        /// <summary>Only play when a VPN is connected.</summary>
        public bool RequireVpn { get; set; }

        // ---- window
        public double WindowWidth { get; set; }
        public double WindowHeight { get; set; }
        public bool WindowMaximized { get; set; } = true;

        /// <summary>2 = the Android defaults of October 2026 were applied once.</summary>
        public int SettingsVersion { get; set; } = 2;

        public Settings Copy()
        {
            var c = (Settings)MemberwiseClone();
            c.ExtOrder = (ExtOrder ?? new List<string>()).ToList();
            c.ExtHidden = (ExtHidden ?? new List<string>()).ToList();
            return c;
        }

        /// <summary>Settings saved by an earlier version get the new defaults once (same as the box).</summary>
        public Settings Upgrade()
        {
            if (ExtOrder == null) ExtOrder = new List<string>();
            if (ExtHidden == null) ExtHidden = new List<string>();
            if (SettingsVersion >= 2) return this;
            CushionSeconds = 15;
            LiveStart = "fast";
            LiveGrowTarget = 120;
            LiveDiskMinutes = 15;
            LiveBufferMb = 3000;
            DeadSeconds = 8;
            AutoSkip = true;
            WatchdogMode = "live";
            FilmBufferMb = 1024;
            DlConnLinks = 8;
            LiveSlowAction = "similar";
            FailApp = "ask";
            FailAppWhen = "format";
            SettingsVersion = 2;
            return this;
        }

        public string SortFor(string zone)
        {
            return zone == "live" ? SortLive : zone == "movie" ? SortMovie : SortSeries;
        }

        public void SetSort(string zone, string value)
        {
            if (zone == "live") SortLive = value; else if (zone == "movie") SortMovie = value; else SortSeries = value;
        }
    }

    /// <summary>
    /// Personal organisation of the lists (same JSON as the Android app):
    /// hiddenGroups / hiddenItems: "&lt;zone&gt;|&lt;key&gt;"; groupOrder: zone → group keys; itemOrder: "&lt;zone&gt;|&lt;group key&gt;" → item keys;
    /// closedSections: "&lt;zone&gt;|&lt;account id&gt;"; names: display names of hidden things.
    /// </summary>
    public class OrgData
    {
        [JsonProperty("hiddenGroups")] public HashSet<string> HiddenGroups { get; set; } = new HashSet<string>();
        [JsonProperty("hiddenItems")] public HashSet<string> HiddenItems { get; set; } = new HashSet<string>();
        [JsonProperty("groupOrder")] public Dictionary<string, List<string>> GroupOrder { get; set; } = new Dictionary<string, List<string>>();
        [JsonProperty("itemOrder")] public Dictionary<string, List<string>> ItemOrder { get; set; } = new Dictionary<string, List<string>>();
        [JsonProperty("closedSections")] public HashSet<string> ClosedSections { get; set; } = new HashSet<string>();
        [JsonProperty("names")] public Dictionary<string, string> Names { get; set; } = new Dictionary<string, string>();

        public OrgData Fix()
        {
            if (HiddenGroups == null) HiddenGroups = new HashSet<string>();
            if (HiddenItems == null) HiddenItems = new HashSet<string>();
            if (GroupOrder == null) GroupOrder = new Dictionary<string, List<string>>();
            if (ItemOrder == null) ItemOrder = new Dictionary<string, List<string>>();
            if (ClosedSections == null) ClosedSections = new HashSet<string>();
            if (Names == null) Names = new Dictionary<string, string>();
            return this;
        }
    }

    /// <summary>Order helpers (port of the Android Org object).</summary>
    public static class Org
    {
        /// <summary>Sorts items by a saved order; items not in it keep their place after the ordered ones.</summary>
        public static List<T> ApplyOrder<T>(IList<T> items, List<string> order, Func<T, string> key)
        {
            if (order == null || order.Count == 0) return items.ToList();
            var pos = new Dictionary<string, int>();
            for (int i = 0; i < order.Count; i++) if (!pos.ContainsKey(order[i])) pos[order[i]] = i;
            return items.Select((x, i) => new { x, i })
                .OrderBy(p => { int v; return pos.TryGetValue(key(p.x), out v) ? v : int.MaxValue; })
                .ThenBy(p => p.i)
                .Select(p => p.x).ToList();
        }

        /// <summary>Moves key by delta places (-1 up, +1 down) in keys.</summary>
        public static List<string> Move(List<string> keys, string key, int delta)
        {
            var list = keys.ToList();
            var i = list.IndexOf(key);
            var j = i + delta;
            if (i < 0 || j < 0 || j >= list.Count) return list;
            list.RemoveAt(i);
            list.Insert(j, key);
            return list;
        }

        /// <summary>Sends key to the top or the bottom.</summary>
        public static List<string> MoveTo(List<string> keys, string key, bool top)
        {
            var list = keys.Where(k => k != key).ToList();
            if (top) list.Insert(0, key); else list.Add(key);
            return list;
        }

        public static void Toggle(HashSet<string> set, string value)
        {
            if (!set.Remove(value)) set.Add(value);
        }
    }

    /// <summary>
    /// Parental age filter (USA ratings) and word filter, with the box's setting names so backups go both ways.
    /// Level: 0 OFF, 1 G, 2 PG, 3 PG-13, 4 R, 5 NC-17 = the highest rating allowed.
    /// </summary>
    public class FilterSettings
    {
        public int Level { get; set; } = 5;
        /// <summary>"rules" (genre &amp; name) or "tmdb" (film database).</summary>
        public string Source { get; set; } = "rules";
        public bool HideUnknown { get; set; }
        /// <summary>Live channels: "name" (hidden by name) or "allow" (only the groups you allow).</summary>
        public string LiveMode { get; set; } = "name";
        public List<string> AllowedGroups { get; set; } = new List<string>();
        public string TmdbKey { get; set; } = "";
        /// <summary>Allowed anyway (exceptions): "movie|acc|id", "series|…", "live|…".</summary>
        public List<string> Allowed { get; set; } = new List<string>();
        public bool WordsOn { get; set; } = true;
        /// <summary>Word groups switched on (null = the defaults).</summary>
        public List<string> WordGroupsOn { get; set; }
        /// <summary>Your words per group (missing = the built-in list).</summary>
        public Dictionary<string, List<string>> Words { get; set; } = new Dictionary<string, List<string>>();
        /// <summary>False until the filter was ever set on this PC (new install: taken back from the Filter folder).</summary>
        public bool Set { get; set; }

        public FilterSettings Fix()
        {
            if (AllowedGroups == null) AllowedGroups = new List<string>();
            if (Allowed == null) Allowed = new List<string>();
            if (Words == null) Words = new Dictionary<string, List<string>>();
            if (TmdbKey == null) TmdbKey = "";
            if (string.IsNullOrEmpty(Source)) Source = "rules";
            if (string.IsNullOrEmpty(LiveMode)) LiveMode = "name";
            return this;
        }

        public FilterSettings Copy()
        {
            var json = JsonConvert.SerializeObject(this);
            return JsonConvert.DeserializeObject<FilterSettings>(json).Fix();
        }
    }

    /// <summary>Everything else Nova remembers (saved as state.json).</summary>
    public class UserState
    {
        public List<SavedItem> Recent { get; set; } = new List<SavedItem>();
        public OrgData Org { get; set; } = new OrgData();
        /// <summary>Resume positions in ms, by "type_accountId_id" (same keys as Android, without "pos_").</summary>
        public Dictionary<string, long> Positions { get; set; } = new Dictionary<string, long>();
        public string PinHash { get; set; }
        public string PinSalt { get; set; }
        /// <summary>Extra request headers per website (Referer, User-Agent…), by host.</summary>
        public Dictionary<string, Dictionary<string, string>> LinkHeaders { get; set; } = new Dictionary<string, Dictionary<string, string>>();
        /// <summary>Recent links as "milliseconds|url", newest first (same as Android).</summary>
        public List<string> RecentLinks { get; set; } = new List<string>();
        public string AccountsLink { get; set; }
        public string AccountsLinkPassword { get; set; }
        public long AccountsLinkChecked { get; set; }
        public string AccountsLinkNote { get; set; }
        public string LastBackupLink { get; set; }
        public long LastBackupLinkAt { get; set; }
        /// <summary>Remote / keyboard buttons per action (WPF key names). Missing actions use the defaults.</summary>
        public Dictionary<string, List<string>> KeyMap { get; set; } = new Dictionary<string, List<string>>();
        /// <summary>Search: "all", "live", "movie", "series"; account id or null for all servers.</summary>
        public string SearchKind { get; set; } = "all";
        public string SearchAccount { get; set; }
        /// <summary>Android settings Windows doesn't use, kept so a backup goes back to the box unchanged.</summary>
        public Dictionary<string, JObject> AndroidExtras { get; set; } = new Dictionary<string, JObject>();
        /// <summary>The box's web browser part of a backup (bookmarks…), kept for the way back. Windows has no web tab.</summary>
        public JObject AndroidWeb { get; set; }
        /// <summary>Parental age filter and word filter (same keys as the box: age_*, tmdb_key).</summary>
        public FilterSettings Filter { get; set; } = new FilterSettings();
        /// <summary>How long channels of each server usually take to start, in ms (learned).</summary>
        public Dictionary<string, long> StartTimes { get; set; } = new Dictionary<string, long>();
        /// <summary>What the last automatic index export held (servers + times), and when it was saved.</summary>
        public string IndexExportStamp { get; set; }
        public long IndexExportedAt { get; set; }
        /// <summary>Save index-latest.novaindex in Downloads\NovaIPTV\Index after every update.</summary>
        public bool AutoExportIndex { get; set; } = true;
        /// <summary>Last watchdog events, newest first ("time|channel|problem|action"), as on the box.</summary>
        public List<string> HealthLog { get; set; } = new List<string>();
        /// <summary>🔊 Volume boost kept for one channel / film, in % (by item key).</summary>
        public Dictionary<string, int> BoostFor { get; set; } = new Dictionary<string, int>();

        public UserState Fix()
        {
            Filter = (Filter ?? new FilterSettings()).Fix();
            if (StartTimes == null) StartTimes = new Dictionary<string, long>();
            if (BoostFor == null) BoostFor = new Dictionary<string, int>();
            if (HealthLog == null) HealthLog = new List<string>();
            if (Recent == null) Recent = new List<SavedItem>();
            Org = (Org ?? new OrgData()).Fix();
            if (Positions == null) Positions = new Dictionary<string, long>();
            if (LinkHeaders == null) LinkHeaders = new Dictionary<string, Dictionary<string, string>>();
            if (RecentLinks == null) RecentLinks = new List<string>();
            if (KeyMap == null) KeyMap = new Dictionary<string, List<string>>();
            if (string.IsNullOrEmpty(SearchKind)) SearchKind = "all";
            if (AndroidExtras == null) AndroidExtras = new Dictionary<string, JObject>();
            return this;
        }
    }

    /// <summary>Windows folders.</summary>
    public static class KnownFolders
    {
        [System.Runtime.InteropServices.DllImport("shell32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
        private static extern int SHGetKnownFolderPath([System.Runtime.InteropServices.MarshalAs(System.Runtime.InteropServices.UnmanagedType.LPStruct)] Guid id, uint flags, IntPtr token, out IntPtr path);

        private static readonly Guid DownloadsId = new Guid("374DE290-123F-4565-9164-39C4925E467B");

        /// <summary>The user's real Downloads folder (even when it was moved to another drive).</summary>
        public static string Downloads()
        {
            try
            {
                IntPtr p;
                if (SHGetKnownFolderPath(DownloadsId, 0, IntPtr.Zero, out p) == 0)
                {
                    var path = System.Runtime.InteropServices.Marshal.PtrToStringUni(p);
                    System.Runtime.InteropServices.Marshal.FreeCoTaskMem(p);
                    if (!string.IsNullOrEmpty(path)) return path;
                }
            }
            catch { }
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        }

        /// <summary>Downloads\NovaIPTV, or the folder chosen in Settings.</summary>
        public static string NovaDownloads(Settings s)
        {
            return string.IsNullOrWhiteSpace(s.DownloadFolder) ? Path.Combine(Downloads(), "NovaIPTV") : s.DownloadFolder;
        }

        /// <summary>Downloads\NovaIPTV\Buffer, or the folder chosen in Settings.</summary>
        public static string Buffer(Settings s)
        {
            return string.IsNullOrWhiteSpace(s.BufferFolder) ? Path.Combine(Downloads(), "NovaIPTV", "Buffer") : s.BufferFolder;
        }

        public static string Backups(Settings s) { return Path.Combine(NovaDownloads(s), "Backups"); }
        public static string Index(Settings s) { return Path.Combine(NovaDownloads(s), "Index"); }
    }

    /// <summary>
    /// Accounts, settings, favorites and everything else, kept as JSON files.
    /// Normal install: %APPDATA%\NovaIPTV. Portable: a "data" folder next to Nova.exe when "portable.txt" is present.
    /// </summary>
    public class Store
    {
        public const string DefaultUserAgent = "NovaIPTV/1.0 (Windows)";
        public const int MaxRecent = 80;

        private readonly object _lock = new object();
        private readonly JsonSerializerSettings _json = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Include,
            MissingMemberHandling = MissingMemberHandling.Ignore,
            ObjectCreationHandling = ObjectCreationHandling.Replace,
            Formatting = Formatting.Indented,
        };

        public string DataFolder { get; private set; }
        public string CacheFolder { get; private set; }

        public List<Account> Accounts { get; private set; }
        public Settings Settings { get; private set; }
        public List<SavedItem> Favorites { get; private set; }
        public UserState State { get; private set; }

        public event Action FavoritesChanged;
        public event Action AccountsChanged;
        public event Action SettingsChanged;
        public event Action OrgChanged;
        public event Action RecentChanged;

        public Store(string dataFolder, string cacheFolder)
        {
            DataFolder = dataFolder;
            CacheFolder = cacheFolder;
            Directory.CreateDirectory(DataFolder);
            Directory.CreateDirectory(CacheFolder);
            LoadAll();
        }

        private void LoadAll()
        {
            Accounts = Load<List<Account>>("accounts.json") ?? new List<Account>();
            Accounts.RemoveAll(a => a == null || string.IsNullOrEmpty(a.Id));
            Settings = Load<Settings>("settings.json") ?? new Settings();
            if (string.IsNullOrWhiteSpace(Settings.UserAgent)) Settings.UserAgent = DefaultUserAgent;
            if (!SavedWithVersion("settings.json")) { Settings.SettingsVersion = 1; Settings.Upgrade(); Save("settings.json", Settings); }
            else Settings.Upgrade();
            Favorites = Load<List<SavedItem>>("favorites.json") ?? new List<SavedItem>();
            Favorites.RemoveAll(f => f == null || f.Id == null);
            State = (Load<UserState>("state.json") ?? new UserState()).Fix();
            State.Recent.RemoveAll(f => f == null || f.Id == null);
        }

        /// <summary>Picks the data folders for this PC (portable or per-user).</summary>
        public static Store Open(string appFolder)
        {
            string data, cache;
            if (File.Exists(Path.Combine(appFolder, "portable.txt")))
            {
                data = Path.Combine(appFolder, "data");
                cache = Path.Combine(data, "cache");
            }
            else
            {
                data = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "NovaIPTV");
                cache = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "NovaIPTV", "cache");
            }
            return new Store(data, cache);
        }

        private static void Raise(Action h) { if (h != null) h(); }

        // ---------------------------------------------------------------- accounts

        public Account Account(string id)
        {
            return Accounts.FirstOrDefault(a => a.Id == id);
        }

        public void SaveAccount(Account account)
        {
            if (string.IsNullOrEmpty(account.Id)) account.Id = NewId();
            var i = Accounts.FindIndex(a => a.Id == account.Id);
            if (i >= 0) Accounts[i] = account; else Accounts.Add(account);
            Save("accounts.json", Accounts);
            Raise(AccountsChanged);
        }

        /// <summary>Replaces the whole accounts list (accounts link), keeping each account's data.</summary>
        public void SetAccounts(List<Account> accounts)
        {
            var removed = Accounts.Where(a => accounts.All(n => n.Id != a.Id)).Select(a => a.Id).ToList();
            Accounts = accounts;
            Save("accounts.json", Accounts);
            if (removed.Count > 0)
            {
                Favorites.RemoveAll(f => removed.Contains(f.AccountId));
                State.Recent.RemoveAll(f => removed.Contains(f.AccountId));
                Save("favorites.json", Favorites);
                SaveState();
                Raise(FavoritesChanged);
            }
            Raise(AccountsChanged);
        }

        public void RemoveAccount(string id)
        {
            Accounts.RemoveAll(a => a.Id == id);
            Save("accounts.json", Accounts);
            Favorites.RemoveAll(f => f.AccountId == id);
            State.Recent.RemoveAll(f => f.AccountId == id);
            Save("favorites.json", Favorites);
            SaveState();
            Raise(AccountsChanged);
            Raise(FavoritesChanged);
            Raise(RecentChanged);
        }

        public static string NewId()
        {
            return Guid.NewGuid().ToString();
        }

        // ---------------------------------------------------------------- settings

        public void SaveSettings(Settings s)
        {
            Settings = s;
            Save("settings.json", s);
            Raise(SettingsChanged);
        }

        /// <summary>Saves the window size without telling the screens (nothing visible changed).</summary>
        public void SaveWindow(double w, double h, bool maximized)
        {
            Settings.WindowWidth = w; Settings.WindowHeight = h; Settings.WindowMaximized = maximized;
            Save("settings.json", Settings);
        }

        // ---------------------------------------------------------------- favorites

        public bool IsFavorite(SavedItem item)
        {
            return Favorites.Any(f => f.SameAs(item));
        }

        /// <summary>Adds or removes; returns true when it is now a favorite.</summary>
        public bool ToggleFavorite(SavedItem item)
        {
            bool now;
            var existing = Favorites.FirstOrDefault(f => f.SameAs(item));
            if (existing != null) { Favorites.Remove(existing); now = false; }
            else
            {
                item.SavedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                Favorites.Add(item);
                now = true;
            }
            Save("favorites.json", Favorites);
            Raise(FavoritesChanged);
            return now;
        }

        public List<SavedItem> FavoritesOf(string type)
        {
            return Favorites.Where(f => f.Type == type && Account(f.AccountId) != null).ToList();
        }

        /// <summary>Saves a new order of one zone's favorites (others keep their place).</summary>
        public void ReorderFavorites(string type, List<SavedItem> ordered)
        {
            var queue = new Queue<SavedItem>(ordered);
            Favorites = Favorites.Select(f => f.Type == type && queue.Count > 0 ? queue.Dequeue() : f).ToList();
            Save("favorites.json", Favorites);
            Raise(FavoritesChanged);
        }

        // ---------------------------------------------------------------- recently watched and resume

        public List<SavedItem> Recent
        {
            get { return State.Recent.Where(r => Account(r.AccountId) != null || r.AccountId == "").ToList(); }
        }

        public void AddRecent(SavedItem item)
        {
            item.SavedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            State.Recent.RemoveAll(r => r.SameAs(item));
            State.Recent.Insert(0, item);
            if (State.Recent.Count > MaxRecent) State.Recent.RemoveRange(MaxRecent, State.Recent.Count - MaxRecent);
            SaveState();
            Raise(RecentChanged);
        }

        public void ClearRecent()
        {
            State.Recent.Clear();
            SaveState();
            Raise(RecentChanged);
        }

        public static string ResumeKey(string type, string accountId, string id)
        {
            return type + "_" + accountId + "_" + id;
        }

        public long GetPosition(string key)
        {
            long v;
            return State.Positions.TryGetValue(key, out v) ? v : 0;
        }

        public void SetPosition(string key, long ms)
        {
            if (ms <= 0) State.Positions.Remove(key); else State.Positions[key] = ms;
            SaveStateSoon();
        }

        // ---------------------------------------------------------------- organisation

        public OrgData Org { get { return State.Org; } }

        public void UpdateOrg(Action<OrgData> change)
        {
            change(State.Org);
            SaveState();
            Raise(OrgChanged);
        }

        // ---------------------------------------------------------------- parental PIN

        public bool HasPin { get { return !string.IsNullOrEmpty(State.PinHash); } }

        public void SetPin(string pin)
        {
            if (string.IsNullOrEmpty(pin)) { State.PinHash = null; State.PinSalt = null; }
            else
            {
                var salt = Guid.NewGuid().ToString("N");
                State.PinSalt = salt;
                State.PinHash = Sha256Hex(salt + pin);
            }
            SaveState();
            Raise(OrgChanged);
        }

        public bool CheckPin(string pin)
        {
            if (!HasPin || State.PinSalt == null) return false;
            return State.PinHash == Sha256Hex(State.PinSalt + pin);
        }

        /// <summary>Same as the Android app: SHA-256 of salt + PIN, lower-case hex.</summary>
        public static string Sha256Hex(string text)
        {
            using (var sha = SHA256.Create())
            {
                var b = sha.ComputeHash(Encoding.UTF8.GetBytes(text));
                var sb = new StringBuilder();
                foreach (var x in b) sb.Append(x.ToString("x2"));
                return sb.ToString();
            }
        }

        // ---------------------------------------------------------------- links

        public Dictionary<string, string> LinkHeaders(string host)
        {
            Dictionary<string, string> h;
            return host != null && State.LinkHeaders.TryGetValue(host.ToLowerInvariant(), out h) ? h : new Dictionary<string, string>();
        }

        public void SetLinkHeaders(string host, Dictionary<string, string> headers)
        {
            if (string.IsNullOrEmpty(host)) return;
            var clean = (headers ?? new Dictionary<string, string>())
                .Where(kv => !string.IsNullOrWhiteSpace(kv.Key) && !string.IsNullOrWhiteSpace(kv.Value))
                .ToDictionary(kv => kv.Key.Trim(), kv => kv.Value.Trim());
            if (clean.Count == 0) State.LinkHeaders.Remove(host.ToLowerInvariant());
            else State.LinkHeaders[host.ToLowerInvariant()] = clean;
            SaveState();
        }

        /// <summary>True for the hosts of your IPTV accounts (never given website headers).</summary>
        public bool IsAccountHost(string host)
        {
            if (string.IsNullOrEmpty(host)) return false;
            foreach (var a in Accounts)
            {
                Uri u;
                if (Uri.TryCreate(XtreamApi.NormalizeServer(a.Server), UriKind.Absolute, out u) &&
                    string.Equals(u.Host, host, StringComparison.OrdinalIgnoreCase)) return true;
            }
            return false;
        }

        public void RememberLink(string url)
        {
            State.RecentLinks.RemoveAll(e => LinkOf(e) == url);
            State.RecentLinks.Insert(0, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + "|" + url);
            if (State.RecentLinks.Count > 15) State.RecentLinks.RemoveRange(15, State.RecentLinks.Count - 15);
            SaveState();
        }

        public void ForgetLink(string entry)
        {
            if (entry == null) State.RecentLinks.Clear(); else State.RecentLinks.Remove(entry);
            SaveState();
        }

        public static string LinkOf(string entry)
        {
            var bar = entry.IndexOf('|');
            long ms;
            return bar > 0 && long.TryParse(entry.Substring(0, bar), out ms) ? entry.Substring(bar + 1) : entry;
        }

        public static DateTime? LinkTime(string entry)
        {
            var bar = entry.IndexOf('|');
            long ms;
            if (bar > 0 && long.TryParse(entry.Substring(0, bar), out ms)) return DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime;
            return null;
        }

        // ---------------------------------------------------------------- state file

        public void SaveState()
        {
            Save("state.json", State);
        }

        private DateTime _lastStateSave = DateTime.MinValue;

        /// <summary>Saves at most every few seconds (resume positions change often).</summary>
        private void SaveStateSoon()
        {
            if ((DateTime.UtcNow - _lastStateSave).TotalSeconds < 5) return;
            _lastStateSave = DateTime.UtcNow;
            SaveState();
        }

        /// <summary>Replaces everything with restored data (backup) and tells every screen.</summary>
        public void ReplaceAll(List<Account> accounts, Settings settings, List<SavedItem> favorites, UserState state)
        {
            Save("accounts.json", accounts);
            Save("settings.json", settings);
            Save("favorites.json", favorites);
            Save("state.json", state);
            LoadAll();
            Raise(SettingsChanged);
            Raise(AccountsChanged);
            Raise(FavoritesChanged);
            Raise(OrgChanged);
            Raise(RecentChanged);
        }

        // ---------------------------------------------------------------- files

        /// <summary>True when the saved settings already know the settings version (made by this version or later).</summary>
        private bool SavedWithVersion(string name)
        {
            try
            {
                var path = Path.Combine(DataFolder, name);
                if (!File.Exists(path)) return true; // new install: the new defaults already
                return File.ReadAllText(path, Encoding.UTF8).Contains("\"SettingsVersion\"");
            }
            catch { return true; }
        }

        private T Load<T>(string name) where T : class
        {
            var path = Path.Combine(DataFolder, name);
            foreach (var p in new[] { path, path + ".bak" })
            {
                try
                {
                    if (!File.Exists(p)) continue;
                    var obj = JsonConvert.DeserializeObject<T>(File.ReadAllText(p, Encoding.UTF8), _json);
                    if (obj != null) return obj;
                }
                catch (Exception)
                {
                    // damaged file: try the previous copy
                }
            }
            return null;
        }

        /// <summary>Writes safely: new file first, previous copy kept as .bak (power cuts on old PCs).</summary>
        private void Save(string name, object value)
        {
            lock (_lock)
            {
                var path = Path.Combine(DataFolder, name);
                var tmp = path + ".tmp";
                File.WriteAllText(tmp, JsonConvert.SerializeObject(value, _json), new UTF8Encoding(false));
                if (File.Exists(path))
                {
                    // Copy + move instead of File.Replace, which fails on FAT32 USB sticks (portable mode).
                    File.Copy(path, path + ".bak", true);
                    File.Delete(path);
                    File.Move(tmp, path);
                }
                else
                {
                    File.Move(tmp, path);
                }
            }
        }
    }
}
