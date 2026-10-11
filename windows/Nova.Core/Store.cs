using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using Newtonsoft.Json;

namespace Nova.Core
{
    /// <summary>Settings of the Windows app (saved as settings.json).</summary>
    public class Settings
    {
        /// <summary>"ts" (MPEG-TS) or "m3u8" (HLS) for live channels.</summary>
        public string LiveFormat { get; set; } = "ts";
        /// <summary>Milliseconds VLC buffers before playing a network stream (the safety cushion).</summary>
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
        public bool LiveLogos { get; set; } = true;
        /// <summary>"small", "medium", "large" posters for films / series.</summary>
        public string PosterSize { get; set; } = "medium";
        public bool PosterTitles { get; set; } = true;
        /// <summary>Open a group as soon as it is highlighted with the arrows (no Enter needed).</summary>
        public bool OpenGroupOnFocus { get; set; } = true;
        /// <summary>Automatic reconnection attempts when a live stream drops.</summary>
        public int ReconnectAttempts { get; set; } = 5;
        /// <summary>Start the player in full screen.</summary>
        public bool PlayerFullScreen { get; set; } = true;
        // ---- disk buffer and watchdog
        /// <summary>Download every stream to disk first and play it from there.</summary>
        public bool UseDiskBuffer { get; set; } = true;
        /// <summary>Folder for the buffers; empty = Downloads\NovaIPTV\Buffer.</summary>
        public string BufferFolder { get; set; } = "";
        /// <summary>Folder for downloads and recordings; empty = Downloads\NovaIPTV.</summary>
        public string DownloadFolder { get; set; } = "";
        /// <summary>Disk space for a live channel's rolling buffer (pause / rewind live TV).</summary>
        public int LiveBufferMb { get; set; } = 600;
        /// <summary>Disk space for the films / episodes cache.</summary>
        public int FilmBufferMb { get; set; } = 2048;
        /// <summary>Seconds collected before a live channel starts playing (the safety cushion).</summary>
        public int CushionSeconds { get; set; } = 6;
        /// <summary>Watchdog: seconds without data before a stream counts as frozen and is reconnected.</summary>
        public int StallSeconds { get; set; } = 10;
        /// <summary>Jump to the next channel when a channel sends nothing at all.</summary>
        public bool AutoSkip { get; set; } = true;
        /// <summary>Seconds a channel may take to send its first video before it counts as dead.</summary>
        public int DeadSeconds { get; set; } = 15;

        public double WindowWidth { get; set; }
        public double WindowHeight { get; set; }
        public bool WindowMaximized { get; set; } = true;

        public Settings Copy()
        {
            return (Settings)MemberwiseClone();
        }
    }

    /// <summary>
    /// Accounts, settings and favorites, kept as JSON files.
    /// Normal install: %APPDATA%\NovaIPTV. Portable: a "data" folder next to Nova.exe when "portable.txt" is present.
    /// </summary>
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
    }

    public class Store
    {
        public const string DefaultUserAgent = "NovaIPTV/1.0 (Windows)";

        private readonly object _lock = new object();
        private readonly JsonSerializerSettings _json = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Include,
            MissingMemberHandling = MissingMemberHandling.Ignore,
            Formatting = Formatting.Indented,
        };

        public string DataFolder { get; private set; }
        public string CacheFolder { get; private set; }

        public List<Account> Accounts { get; private set; }
        public Settings Settings { get; private set; }
        public List<SavedItem> Favorites { get; private set; }

        public event Action FavoritesChanged;
        public event Action AccountsChanged;
        public event Action SettingsChanged;

        public Store(string dataFolder, string cacheFolder)
        {
            DataFolder = dataFolder;
            CacheFolder = cacheFolder;
            Directory.CreateDirectory(DataFolder);
            Directory.CreateDirectory(CacheFolder);
            Accounts = Load<List<Account>>("accounts.json") ?? new List<Account>();
            Accounts.RemoveAll(a => a == null || string.IsNullOrEmpty(a.Id));
            Settings = Load<Settings>("settings.json") ?? new Settings();
            if (string.IsNullOrWhiteSpace(Settings.UserAgent)) Settings.UserAgent = DefaultUserAgent;
            Favorites = Load<List<SavedItem>>("favorites.json") ?? new List<SavedItem>();
            Favorites.RemoveAll(f => f == null || f.Id == null);
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
            var h = AccountsChanged; if (h != null) h();
        }

        public void RemoveAccount(string id)
        {
            Accounts.RemoveAll(a => a.Id == id);
            Save("accounts.json", Accounts);
            Favorites.RemoveAll(f => f.AccountId == id);
            Save("favorites.json", Favorites);
            var h = AccountsChanged; if (h != null) h();
            var f2 = FavoritesChanged; if (f2 != null) f2();
        }

        public static string NewId()
        {
            return Guid.NewGuid().ToString("N").Substring(0, 12);
        }

        // ---------------------------------------------------------------- settings

        public void SaveSettings(Settings s)
        {
            Settings = s;
            Save("settings.json", s);
            var h = SettingsChanged; if (h != null) h();
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
            var h = FavoritesChanged; if (h != null) h();
            return now;
        }

        public List<SavedItem> FavoritesOf(string type)
        {
            return Favorites.Where(f => f.Type == type && Account(f.AccountId) != null).ToList();
        }

        // ---------------------------------------------------------------- files

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
