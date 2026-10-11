using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using Newtonsoft.Json;

namespace Nova.Core.Streaming
{
    public static class DownloadStatus
    {
        public const string Queued = "queued";
        public const string Downloading = "downloading";
        public const string Waiting = "waiting";   // paused by Nova: the account's only connection is in use
        public const string Paused = "paused";     // paused by you
        public const string Finishing = "finishing";
        public const string Done = "done";
        public const string Failed = "failed";
        public const string Recording = "recording";
    }

    /// <summary>A downloaded film / episode or a live recording (saved in downloads.json).</summary>
    public class DownloadItem
    {
        public string Id { get; set; }
        public string Title { get; set; }
        /// <summary>"film", "episode" or "recording".</summary>
        public string Kind { get; set; }
        public string Url { get; set; }
        public string Ext { get; set; }
        public string AccountId { get; set; }
        public string Icon { get; set; }
        public string FinalPath { get; set; }
        public string PartsFolder { get; set; }
        public string Status { get; set; }
        public long TotalBytes { get; set; } = -1;
        public long DoneBytes { get; set; }
        public string Error { get; set; }
        public long CreatedAt { get; set; }

        [JsonIgnore] public bool IsDone { get { return Status == DownloadStatus.Done; } }

        public DownloadItem Copy() { return (DownloadItem)MemberwiseClone(); }
    }

    /// <summary>
    /// Downloads films and episodes one at a time (one connection), resumes after drops and restarts,
    /// and joins the pieces into a normal video file when done. Also lists live recordings.
    /// On accounts that allow only one connection, a download waits while that account is used to watch.
    /// </summary>
    public sealed class DownloadManager : IDisposable
    {
        private readonly object _lock = new object();
        private readonly StreamHub _hub;
        private readonly Func<Settings> _settings;
        private readonly Func<string, int> _maxConnections;
        private readonly string _listPath;
        private readonly List<DownloadItem> _items;
        private readonly Timer _timer;
        private DownloadItem _active;
        private VodLease _lease;
        private DateTime _lastSave = DateTime.MinValue;
        private bool _dirty;
        private volatile bool _disposed;

        /// <summary>The list or a progress changed (called on a background thread).</summary>
        public event Action Changed;

        public DownloadManager(StreamHub hub, Func<Settings> settings, Func<string, int> maxConnections, string dataFolder)
        {
            _hub = hub;
            _settings = settings;
            _maxConnections = maxConnections;
            _listPath = Path.Combine(dataFolder, "downloads.json");
            _items = Load();
            foreach (var i in _items)
            {
                if (i.Status == DownloadStatus.Downloading || i.Status == DownloadStatus.Waiting || i.Status == DownloadStatus.Finishing) i.Status = DownloadStatus.Queued;
                if (i.Status == DownloadStatus.Recording)
                {
                    i.Status = File.Exists(i.FinalPath) ? DownloadStatus.Done : DownloadStatus.Failed;
                    if (File.Exists(i.FinalPath)) i.DoneBytes = i.TotalBytes = new FileInfo(i.FinalPath).Length;
                }
            }
            _timer = new Timer(_ => Tick(), null, 1000, 1500);
        }

        public List<DownloadItem> Items
        {
            get { lock (_lock) return _items.Select(i => i.Copy()).ToList(); }
        }

        public DownloadItem Find(string url)
        {
            lock (_lock) { var i = _items.FirstOrDefault(x => x.Url == url && x.Kind != "recording"); return i == null ? null : i.Copy(); }
        }

        /// <summary>Path of the finished file for this address, if it was downloaded and still exists.</summary>
        public string DoneFile(string url)
        {
            lock (_lock)
            {
                var i = _items.FirstOrDefault(x => x.Url == url && x.Status == DownloadStatus.Done);
                return i != null && File.Exists(i.FinalPath) ? i.FinalPath : null;
            }
        }

        // ------------------------------------------------------------------ adding

        public DownloadItem AddFilm(Movie m, string url)
        {
            var name = SafeName(m.Name) + "." + Ext(m.Extension);
            var path = Path.Combine(KnownFolders.NovaDownloads(_settings()), "Films", name);
            return Add(m.Name, "film", url, Ext(m.Extension), m.AccountId, m.Icon, path);
        }

        public DownloadItem AddEpisode(Series s, Episode e, string url)
        {
            var show = SafeName(s.Name);
            var num = "S" + e.Season.ToString("00") + "E" + Math.Max(1, e.EpisodeNum).ToString("00");
            var title = string.IsNullOrWhiteSpace(e.Title) || e.Title.IndexOf(s.Name, StringComparison.OrdinalIgnoreCase) >= 0 ? "" : " - " + SafeName(e.Title);
            var file = show + " " + num + title + "." + Ext(e.Extension);
            var path = Path.Combine(KnownFolders.NovaDownloads(_settings()), "Series", show, "Season " + e.Season, file);
            return Add(s.Name + "  ·  " + num, "episode", url, Ext(e.Extension), s.AccountId, e.Image ?? s.Cover, path);
        }

        private DownloadItem Add(string title, string kind, string url, string ext, string accountId, string icon, string finalPath)
        {
            DownloadItem item;
            lock (_lock)
            {
                item = _items.FirstOrDefault(x => x.Url == url && x.Kind != "recording");
                if (item != null)
                {
                    if (item.Status == DownloadStatus.Failed || (item.IsDone && !File.Exists(item.FinalPath)))
                    {
                        item.Status = DownloadStatus.Queued;
                        item.Error = null;
                        item.DoneBytes = 0;
                    }
                    else if (item.Status == DownloadStatus.Paused) item.Status = DownloadStatus.Queued;
                    _dirty = true;
                    return item.Copy();
                }
                finalPath = Unique(finalPath);
                item = new DownloadItem
                {
                    Id = Guid.NewGuid().ToString("N").Substring(0, 12),
                    Title = title,
                    Kind = kind,
                    Url = url,
                    Ext = ext,
                    AccountId = accountId,
                    Icon = icon,
                    FinalPath = finalPath,
                    PartsFolder = Path.Combine(Path.GetDirectoryName(finalPath), "." + Path.GetFileName(finalPath) + ".parts"),
                    Status = DownloadStatus.Queued,
                    CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                };
                _items.Insert(0, item);
                _dirty = true;
            }
            Notify();
            ThreadPool.QueueUserWorkItem(_ => Tick());
            return item.Copy();
        }

        /// <summary>Download &amp; Watch: this item goes first (others wait), so one connection serves both.</summary>
        public void Prioritize(string id)
        {
            lock (_lock)
            {
                var item = _items.FirstOrDefault(x => x.Id == id);
                if (item == null || item.IsDone) return;
                if (_active != null && _active.Id != id)
                {
                    StopActiveLocked(DownloadStatus.Queued);
                }
                item.Status = DownloadStatus.Queued;
                _items.Remove(item);
                _items.Insert(0, item);
                _dirty = true;
            }
            ThreadPool.QueueUserWorkItem(_ => Tick());
        }

        // ------------------------------------------------------------------ recordings

        public DownloadItem AddRecording(string channel, string path, string accountId, string icon)
        {
            var item = new DownloadItem
            {
                Id = Guid.NewGuid().ToString("N").Substring(0, 12),
                Title = channel + "  ·  " + DateTime.Now.ToString("d MMM HH:mm"),
                Kind = "recording",
                AccountId = accountId,
                Icon = icon,
                FinalPath = path,
                Status = DownloadStatus.Recording,
                CreatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            };
            lock (_lock) { _items.Insert(0, item); _dirty = true; }
            Notify();
            return item.Copy();
        }

        public void UpdateRecording(string id, long bytes, bool finished)
        {
            lock (_lock)
            {
                var item = _items.FirstOrDefault(x => x.Id == id);
                if (item == null) return;
                item.DoneBytes = bytes;
                if (finished) { item.Status = DownloadStatus.Done; item.TotalBytes = bytes; }
                _dirty = true;
            }
            Notify();
        }

        /// <summary>
        /// Something is about to play on this account: a download on a 1-connection account steps aside
        /// at once (it continues by itself when the connection is free).
        /// </summary>
        public void Yield(string accountId)
        {
            if (accountId == null || _maxConnections(accountId) != 1) return;
            lock (_lock)
            {
                if (_active != null && _active.AccountId == accountId) StopActiveLocked(DownloadStatus.Waiting);
            }
            Notify();
        }

        // ------------------------------------------------------------------ controls

        public void Pause(string id)
        {
            lock (_lock)
            {
                var item = _items.FirstOrDefault(x => x.Id == id);
                if (item == null || item.IsDone || item.Kind == "recording") return;
                if (_active != null && _active.Id == id) StopActiveLocked(DownloadStatus.Paused);
                else item.Status = DownloadStatus.Paused;
                _dirty = true;
            }
            Notify();
        }

        public void Resume(string id)
        {
            lock (_lock)
            {
                var item = _items.FirstOrDefault(x => x.Id == id);
                if (item == null || item.IsDone || item.Kind == "recording") return;
                item.Status = DownloadStatus.Queued;
                item.Error = null;
                _dirty = true;
            }
            Notify();
            ThreadPool.QueueUserWorkItem(_ => Tick());
        }

        /// <summary>Removes from the list; deleteFile also deletes the video file / pieces.</summary>
        public void Remove(string id, bool deleteFile)
        {
            DownloadItem item;
            lock (_lock)
            {
                item = _items.FirstOrDefault(x => x.Id == id);
                if (item == null || item.Status == DownloadStatus.Recording) return;
                if (_active != null && _active.Id == id) StopActiveLocked(DownloadStatus.Paused);
                _items.Remove(item);
                _dirty = true;
            }
            if (deleteFile)
            {
                try { if (item.PartsFolder != null && Directory.Exists(item.PartsFolder)) Directory.Delete(item.PartsFolder, true); } catch { }
                try { if (item.IsDone && File.Exists(item.FinalPath)) File.Delete(item.FinalPath); } catch { }
            }
            Notify();
        }

        private void StopActiveLocked(string newStatus)
        {
            if (_active == null) return;
            _active.Status = newStatus;
            if (_lease != null) { _lease.File.Changed -= OnFileChanged; _lease.Dispose(); }
            _lease = null;
            _active = null;
            _dirty = true;
        }

        // ------------------------------------------------------------------ the work

        private int _ticking;

        private void Tick()
        {
            if (_disposed || Interlocked.Exchange(ref _ticking, 1) == 1) return;
            try
            {
                DownloadItem finish = null;
                VodLease finishLease = null;
                lock (_lock)
                {
                    if (_active != null && _lease != null)
                    {
                        var f = _lease.File;
                        _active.DoneBytes = f.Downloaded;
                        _active.TotalBytes = f.Length;
                        if (f.State == VodState.Failed)
                        {
                            _active.Error = f.Message ?? "This file can't be downloaded.";
                            StopActiveLocked(DownloadStatus.Failed);
                        }
                        else if (f.IsComplete)
                        {
                            // Join the pieces once nobody watches it any more (Download & Watch).
                            if (_hub.Users(f) <= 1)
                            {
                                finish = _active;
                                finishLease = _lease;
                                _active.Status = DownloadStatus.Finishing;
                                _lease.File.Changed -= OnFileChanged;
                                _lease = null;
                                _active = null;
                            }
                        }
                        else if (Waits(_active, f))
                        {
                            StopActiveLocked(DownloadStatus.Waiting);
                        }
                        else
                        {
                            _active.Status = f.State == VodState.Reconnecting ? DownloadStatus.Downloading : DownloadStatus.Downloading;
                            _active.Error = f.State == VodState.Reconnecting ? f.Message : null;
                        }
                        _dirty = true;
                    }
                    if (_active == null && finish == null)
                    {
                        var next = _items.FirstOrDefault(i => (i.Status == DownloadStatus.Queued || i.Status == DownloadStatus.Waiting) && !Waits(i, _hub.FindVod(i.Url)));
                        if (next != null)
                        {
                            _active = next;
                            next.Status = DownloadStatus.Downloading;
                            next.Error = null;
                            _lease = _hub.OpenVod(next.Url, next.Ext, next.PartsFolder, true, next.AccountId);
                            _lease.File.Changed += OnFileChanged;
                            _dirty = true;
                        }
                    }
                }
                if (finish != null)
                {
                    try
                    {
                        var file = finishLease.File;
                        finishLease.Dispose();
                        file.Assemble(finish.FinalPath);
                        lock (_lock)
                        {
                            finish.Status = DownloadStatus.Done;
                            finish.DoneBytes = finish.TotalBytes = new FileInfo(finish.FinalPath).Length;
                            _dirty = true;
                        }
                    }
                    catch (Exception e)
                    {
                        lock (_lock) { finish.Status = DownloadStatus.Failed; finish.Error = "Couldn't save the file: " + e.Message; _dirty = true; }
                    }
                }
                SaveIfNeeded();
                Notify();
            }
            catch { }
            finally
            {
                Interlocked.Exchange(ref _ticking, 0);
            }
        }

        /// <summary>True when this download must wait: its account allows one connection and it's in use.</summary>
        private bool Waits(DownloadItem item, VodFile own)
        {
            if (item.AccountId == null) return false;
            var max = _maxConnections(item.AccountId);
            if (max != 1) return false;
            return _hub.BusyAccounts(own).Contains(item.AccountId);
        }

        private void OnFileChanged(VodFile f)
        {
            ThreadPool.QueueUserWorkItem(_ => Tick());
        }

        private void Notify()
        {
            var h = Changed;
            if (h != null) try { h(); } catch { }
        }

        // ------------------------------------------------------------------ list file

        private List<DownloadItem> Load()
        {
            foreach (var p in new[] { _listPath, _listPath + ".bak" })
            {
                try
                {
                    if (File.Exists(p))
                    {
                        var list = JsonConvert.DeserializeObject<List<DownloadItem>>(File.ReadAllText(p, Encoding.UTF8));
                        if (list != null) return list.Where(i => i != null && i.Id != null).ToList();
                    }
                }
                catch { }
            }
            return new List<DownloadItem>();
        }

        private void SaveIfNeeded()
        {
            string json;
            lock (_lock)
            {
                if (!_dirty) return;
                if ((DateTime.UtcNow - _lastSave).TotalSeconds < 3 && _active != null) return;
                _dirty = false;
                _lastSave = DateTime.UtcNow;
                json = JsonConvert.SerializeObject(_items, Formatting.Indented);
            }
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_listPath));
                var tmp = _listPath + ".tmp";
                File.WriteAllText(tmp, json, new UTF8Encoding(false));
                if (File.Exists(_listPath)) File.Copy(_listPath, _listPath + ".bak", true);
                if (File.Exists(_listPath)) File.Delete(_listPath);
                File.Move(tmp, _listPath);
            }
            catch { }
        }

        public void Dispose()
        {
            _disposed = true;
            _timer.Dispose();
            lock (_lock)
            {
                if (_active != null) StopActiveLocked(DownloadStatus.Queued);
                _dirty = true;
            }
            _lastSave = DateTime.MinValue;
            SaveIfNeeded();
        }

        // ------------------------------------------------------------------ names

        public static string SafeName(string name)
        {
            var bad = Path.GetInvalidFileNameChars();
            var sb = new StringBuilder();
            foreach (var c in (name ?? "video").Trim())
                sb.Append(bad.Contains(c) || c < 32 ? '_' : c);
            var s = sb.ToString().Trim().TrimEnd('.', ' ');
            if (s.Length > 120) s = s.Substring(0, 120).Trim();
            return s.Length == 0 ? "video" : s;
        }

        private static string Ext(string ext)
        {
            var e = new string((ext ?? "").Where(char.IsLetterOrDigit).ToArray());
            return e.Length == 0 ? "mp4" : e.ToLowerInvariant();
        }

        private static string Unique(string path)
        {
            if (!File.Exists(path)) return path;
            var dir = Path.GetDirectoryName(path);
            var name = Path.GetFileNameWithoutExtension(path);
            var ext = Path.GetExtension(path);
            for (int i = 2; i < 1000; i++)
            {
                var p = Path.Combine(dir, name + " (" + i.ToString(CultureInfo.InvariantCulture) + ")" + ext);
                if (!File.Exists(p)) return p;
            }
            return path;
        }
    }

    /// <summary>Live recordings: same connection as watching, saved as .ts in Downloads\NovaIPTV\Recordings.</summary>
    public sealed class RecordingManager : IDisposable
    {
        private sealed class Rec
        {
            public LiveLease Lease;
            public string Name;
            public string ItemId;
        }

        private readonly object _lock = new object();
        private readonly Dictionary<string, Rec> _recs = new Dictionary<string, Rec>();
        private readonly StreamHub _hub;
        private readonly DownloadManager _downloads;
        private readonly Func<Settings> _settings;
        private readonly Timer _timer;

        public event Action Changed;

        public RecordingManager(StreamHub hub, DownloadManager downloads, Func<Settings> settings)
        {
            _hub = hub;
            _downloads = downloads;
            _settings = settings;
            _timer = new Timer(_ => Update(), null, 2000, 2000);
        }

        public bool IsRecording(string channelKey)
        {
            lock (_lock) return _recs.ContainsKey(channelKey);
        }

        public List<string> RecordingKeys
        {
            get { lock (_lock) return _recs.Keys.ToList(); }
        }

        /// <summary>Accounts that have a recording running.</summary>
        public HashSet<string> Accounts
        {
            get
            {
                lock (_lock)
                    return new HashSet<string>(_recs.Keys.Select(k => k.IndexOf('|') > 0 ? k.Substring(0, k.IndexOf('|')) : k));
            }
        }

        public string NameOf(string channelKey)
        {
            lock (_lock) { Rec r; return _recs.TryGetValue(channelKey, out r) ? r.Name : null; }
        }

        /// <summary>Starts recording a channel (joins the stream if it is already playing). Returns the file path.</summary>
        public string Start(LiveChannel ch, string url)
        {
            lock (_lock)
            {
                Rec existing;
                if (_recs.TryGetValue(ch.Key, out existing)) return existing.Lease.Relay.RecordingPath;
            }
            var name = DownloadManager.SafeName(ch.Name);
            var folder = Path.Combine(KnownFolders.NovaDownloads(_settings()), "Recordings", name);
            var path = Path.Combine(folder, name + " " + DateTime.Now.ToString("yyyy-MM-dd HH-mm", CultureInfo.InvariantCulture) + ".ts");
            var n = 2;
            while (File.Exists(path)) path = Path.Combine(folder, name + " " + DateTime.Now.ToString("yyyy-MM-dd HH-mm", CultureInfo.InvariantCulture) + " (" + n++ + ").ts");
            var lease = _hub.OpenLive(ch.Key, url);
            lease.Relay.StartRecording(path);
            var item = _downloads.AddRecording(ch.Name, path, ch.AccountId, ch.Icon);
            lock (_lock) _recs[ch.Key] = new Rec { Lease = lease, Name = ch.Name, ItemId = item.Id };
            Raise();
            return path;
        }

        public void Stop(string channelKey)
        {
            Rec r;
            lock (_lock)
            {
                if (!_recs.TryGetValue(channelKey, out r)) return;
                _recs.Remove(channelKey);
            }
            var bytes = r.Lease.Relay.RecordedBytes;
            r.Lease.Relay.StopRecording();
            r.Lease.Dispose();
            _downloads.UpdateRecording(r.ItemId, bytes, true);
            Raise();
        }

        private void Update()
        {
            List<Rec> recs;
            lock (_lock) recs = _recs.Values.ToList();
            foreach (var r in recs) _downloads.UpdateRecording(r.ItemId, r.Lease.Relay.RecordedBytes, false);
        }

        private void Raise()
        {
            var h = Changed;
            if (h != null) try { h(); } catch { }
        }

        public void Dispose()
        {
            _timer.Dispose();
            foreach (var k in RecordingKeys) Stop(k);
        }
    }
}
