using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace Nova.Core.Streaming
{
    /// <summary>A live channel in use. Dispose when done (the connection closes when nobody uses it).</summary>
    public sealed class LiveLease : IDisposable
    {
        private readonly StreamHub _hub;
        private bool _done;
        public LiveRelay Relay { get; private set; }
        /// <summary>Address VLC plays (the disk buffer, served on this PC).</summary>
        public string Url { get; private set; }

        internal LiveLease(StreamHub hub, LiveRelay relay, string url) { _hub = hub; Relay = relay; Url = url; }

        /// <summary>Address that starts at a given stream offset (rewind / back to live).</summary>
        public string UrlAt(long offset) { return Url + "?at=" + offset.ToString(CultureInfo.InvariantCulture); }

        public void Dispose()
        {
            if (_done) return;
            _done = true;
            _hub.Release(Relay);
        }
    }

    /// <summary>A film / episode in use through the disk cache.</summary>
    public sealed class VodLease : IDisposable
    {
        private readonly StreamHub _hub;
        private bool _done;
        public VodFile File { get; private set; }
        public string Url { get; private set; }

        internal VodLease(StreamHub hub, VodFile file, string url) { _hub = hub; File = file; Url = url; }

        public void Dispose()
        {
            if (_done) return;
            _done = true;
            _hub.Release(File);
        }
    }

    /// <summary>
    /// Runs the small video server on this PC (127.0.0.1 only, nothing is reachable from outside)
    /// and keeps one download per channel / file, shared by everyone who uses it.
    /// </summary>
    public sealed class StreamHub : IDisposable
    {
        private readonly object _lock = new object();
        private readonly Dictionary<string, LiveRelay> _live = new Dictionary<string, LiveRelay>();
        private readonly Dictionary<LiveRelay, int> _liveRefs = new Dictionary<LiveRelay, int>();
        private readonly Dictionary<string, VodFile> _vod = new Dictionary<string, VodFile>();
        private readonly Dictionary<VodFile, int> _vodRefs = new Dictionary<VodFile, int>();
        private readonly Dictionary<string, object> _routes = new Dictionary<string, object>();
        private readonly TcpListener _listener;
        private readonly Thread _accept;
        private volatile bool _stopped;
        private readonly Func<Settings> _settings;
        private readonly Func<string> _bufferRoot;

        public int Port { get; private set; }

        /// <summary>A live relay was created or stopped (for the recordings list).</summary>
        public event Action RelaysChanged;

        public StreamHub(Func<Settings> settings, Func<string> bufferRoot)
        {
            _settings = settings;
            _bufferRoot = bufferRoot;
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start(16);
            Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
            _accept = new Thread(AcceptLoop) { IsBackground = true, Name = "Nova video server" };
            _accept.Start();
            CleanLiveLeftovers();
        }

        private string Base { get { return "http://127.0.0.1:" + Port; } }

        public string LiveFolder { get { return Path.Combine(_bufferRoot(), "Live"); } }
        public string FilmsFolder { get { return Path.Combine(_bufferRoot(), "Films"); } }

        private void CleanLiveLeftovers()
        {
            try
            {
                if (Directory.Exists(LiveFolder))
                    foreach (var d in Directory.GetDirectories(LiveFolder)) { try { Directory.Delete(d, true); } catch { } }
            }
            catch { }
        }

        // ------------------------------------------------------------------ live

        /// <summary>Starts (or joins) the download of a live channel.</summary>
        public LiveLease OpenLive(string key, string url)
        {
            var s = _settings();
            LiveRelay relay;
            lock (_lock)
            {
                if (!_live.TryGetValue(key, out relay) || relay.IsStopped)
                {
                    var folder = Path.Combine(LiveFolder, Guid.NewGuid().ToString("N").Substring(0, 10));
                    relay = new LiveRelay(key, url, () => _settings().UserAgent, folder,
                        (long)Math.Max(100, s.LiveBufferMb) * 1024 * 1024, s.StallSeconds, s.DeadSeconds);
                    _live[key] = relay;
                    _liveRefs[relay] = 0;
                    _routes["live/" + Id(key)] = relay;
                    relay.Begin();
                }
                _liveRefs[relay] = _liveRefs[relay] + 1;
            }
            Raise();
            return new LiveLease(this, relay, Base + "/live/" + Id(key) + ".ts");
        }

        /// <summary>The relay of a channel if it is running (watching or recording), or null.</summary>
        public LiveRelay FindLive(string key)
        {
            lock (_lock)
            {
                LiveRelay r;
                return _live.TryGetValue(key, out r) && !r.IsStopped ? r : null;
            }
        }

        public List<LiveRelay> RunningRelays()
        {
            lock (_lock) return _live.Values.Where(r => !r.IsStopped).ToList();
        }

        /// <summary>Keeps a relay alive without a viewer (recording). Give it back with Release.</summary>
        public LiveLease HoldLive(string key, string url) { return OpenLive(key, url); }

        internal void Release(LiveRelay relay)
        {
            var stop = false;
            lock (_lock)
            {
                int n;
                if (!_liveRefs.TryGetValue(relay, out n)) return;
                n--;
                if (n <= 0)
                {
                    _liveRefs.Remove(relay);
                    LiveRelay cur;
                    if (_live.TryGetValue(relay.Key, out cur) && cur == relay) _live.Remove(relay.Key);
                    _routes.Remove("live/" + Id(relay.Key));
                    stop = true;
                }
                else _liveRefs[relay] = n;
            }
            if (stop) ThreadPool.QueueUserWorkItem(_ => { relay.Dispose(); Raise(); });
        }

        private void Raise()
        {
            var h = RelaysChanged;
            if (h != null) try { h(); } catch { }
        }

        // ------------------------------------------------------------------ films / episodes

        /// <summary>
        /// Plays a film or episode through the disk cache (folder = null) or through a download
        /// folder (Download &amp; Watch). ext names the file type for VLC (mkv, mp4…).
        /// </summary>
        public VodLease OpenVod(string url, string ext, string downloadFolder, bool full, string accountId = null)
        {
            var key = Id(url);
            var s = _settings();
            VodFile file;
            lock (_lock)
            {
                if (!_vod.TryGetValue(key, out file) || file.IsStopped)
                {
                    var folder = downloadFolder ?? Path.Combine(FilmsFolder, key);
                    var cacheCap = (long)Math.Max(256, s.FilmBufferMb) * 1024 * 1024;
                    file = new VodFile(key, url, () => _settings().UserAgent, folder, full,
                        downloadFolder != null ? long.MaxValue / 4 : cacheCap - 64L * 1024 * 1024, s.StallSeconds);
                    file.AccountId = accountId;
                    _vod[key] = file;
                    _vodRefs[file] = 0;
                    _routes["vod/" + key] = file;
                    if (downloadFolder == null) TrimFilmCache(cacheCap, folder);
                    file.Begin();
                }
                else if (full) file.Full = true;
                _vodRefs[file] = _vodRefs[file] + 1;
            }
            var safeExt = string.IsNullOrEmpty(ext) ? "mp4" : new string(ext.Where(char.IsLetterOrDigit).ToArray());
            return new VodLease(this, file, Base + "/vod/" + key + "/video." + safeExt);
        }

        /// <summary>How many users hold this film / episode (player, download…).</summary>
        public int Users(VodFile file)
        {
            lock (_lock) { int n; return _vodRefs.TryGetValue(file, out n) ? n : 0; }
        }

        /// <summary>The film / episode file of this address if it is in use, or null.</summary>
        public VodFile FindVod(string url)
        {
            lock (_lock) { VodFile f; return _vod.TryGetValue(Id(url), out f) && !f.IsStopped ? f : null; }
        }

        /// <summary>Accounts with a stream in use right now (live channels and films), except the given file.</summary>
        public HashSet<string> BusyAccounts(VodFile except)
        {
            var set = new HashSet<string>();
            lock (_lock)
            {
                foreach (var r in _live.Values)
                    if (!r.IsStopped) { var bar = r.Key.IndexOf('|'); if (bar > 0) set.Add(r.Key.Substring(0, bar)); }
                foreach (var f in _vod.Values)
                    if (!f.IsStopped && f != except && f.AccountId != null) set.Add(f.AccountId);
            }
            return set;
        }

        internal void Release(VodFile file)
        {
            var stop = false;
            lock (_lock)
            {
                int n;
                if (!_vodRefs.TryGetValue(file, out n)) return;
                n--;
                if (n <= 0)
                {
                    _vodRefs.Remove(file);
                    VodFile cur;
                    if (_vod.TryGetValue(file.Key, out cur) && cur == file) _vod.Remove(file.Key);
                    _routes.Remove("vod/" + file.Key);
                    stop = true;
                }
                else _vodRefs[file] = n;
            }
            if (stop) ThreadPool.QueueUserWorkItem(_ => file.Dispose());
        }

        /// <summary>Keeps the film cache under its size limit: films watched longest ago go first.</summary>
        private void TrimFilmCache(long cap, string keep)
        {
            try
            {
                if (!Directory.Exists(FilmsFolder)) return;
                var active = new HashSet<string>(_vod.Values.Select(v => Path.GetFullPath(v.Folder)), StringComparer.OrdinalIgnoreCase);
                active.Add(Path.GetFullPath(keep));
                var dirs = new DirectoryInfo(FilmsFolder).GetDirectories()
                    .Select(d => new { Dir = d, Size = d.GetFiles().Sum(f => f.Length), Used = d.LastWriteTimeUtc })
                    .OrderBy(x => x.Used).ToList();
                var total = dirs.Sum(x => x.Size);
                foreach (var d in dirs)
                {
                    if (total <= cap) break;
                    if (active.Contains(d.Dir.FullName)) continue;
                    try { d.Dir.Delete(true); total -= d.Size; } catch { }
                }
            }
            catch { }
        }

        public static string Id(string text)
        {
            using (var sha = SHA1.Create())
            {
                var b = sha.ComputeHash(Encoding.UTF8.GetBytes(text));
                var sb = new StringBuilder();
                for (int i = 0; i < 10; i++) sb.Append(b[i].ToString("x2"));
                return sb.ToString();
            }
        }

        // ------------------------------------------------------------------ the server

        private void AcceptLoop()
        {
            while (!_stopped)
            {
                TcpClient c;
                try { c = _listener.AcceptTcpClient(); }
                catch { if (_stopped) return; Thread.Sleep(50); continue; }
                var t = new Thread(() => Serve(c)) { IsBackground = true, Name = "Nova video client" };
                t.Start();
            }
        }

        private void Serve(TcpClient client)
        {
            using (client)
            {
                try
                {
                    client.NoDelay = true;
                    client.SendTimeout = 60000;
                    var s = client.GetStream();
                    var head = ReadHead(s);
                    if (head == null) return;
                    var first = head[0].Split(' ');
                    if (first.Length < 2) return;
                    var method = first[0].ToUpperInvariant();
                    var target = first[1];
                    var query = "";
                    var q = target.IndexOf('?');
                    if (q >= 0) { query = target.Substring(q + 1); target = target.Substring(0, q); }
                    string range = null;
                    foreach (var h in head.Skip(1))
                        if (h.StartsWith("Range:", StringComparison.OrdinalIgnoreCase)) range = h.Substring(6).Trim();

                    var parts = target.Trim('/').Split('/');
                    object route = null;
                    if (parts.Length >= 2)
                    {
                        var id = parts[1];
                        var dot = id.IndexOf('.');
                        if (dot >= 0) id = id.Substring(0, dot);
                        lock (_lock) _routes.TryGetValue(parts[0] + "/" + id, out route);
                    }
                    Func<bool> gone = () => _stopped || ClientGone(client);
                    var relay = route as LiveRelay;
                    if (relay != null) { ServeLive(s, method, query, relay, gone); return; }
                    var file = route as VodFile;
                    if (file != null) { ServeVod(s, method, range, file, gone); return; }
                    Send(s, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                }
                catch (IOException) { }
                catch (SocketException) { }
                catch (ObjectDisposedException) { }
            }
        }

        private static bool ClientGone(TcpClient c)
        {
            try
            {
                var sock = c.Client;
                return sock == null || (sock.Poll(0, SelectMode.SelectRead) && sock.Available == 0);
            }
            catch { return true; }
        }

        private static List<string> ReadHead(NetworkStream s)
        {
            var lines = new List<string>();
            var sb = new StringBuilder();
            int b;
            while ((b = s.ReadByte()) >= 0)
            {
                if (b == '\n')
                {
                    var line = sb.ToString().TrimEnd('\r');
                    sb.Clear();
                    if (line.Length == 0) return lines.Count > 0 ? lines : null;
                    lines.Add(line);
                    if (lines.Count > 100) return null;
                }
                else sb.Append((char)b);
            }
            return null;
        }

        private static void Send(Stream s, string text)
        {
            var b = Encoding.ASCII.GetBytes(text);
            s.Write(b, 0, b.Length);
            s.Flush();
        }

        private void ServeLive(NetworkStream s, string method, string query, LiveRelay relay, Func<bool> gone)
        {
            Send(s, "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");
            if (method == "HEAD") return;
            var cushion = Math.Max(0, _settings().CushionSeconds);
            long pos;
            long at;
            var atText = ParseQuery(query, "at");
            if (atText != null && long.TryParse(atText, NumberStyles.Integer, CultureInfo.InvariantCulture, out at))
            {
                pos = relay.AlignTs(Math.Max(relay.Start, Math.Min(at, relay.End)));
            }
            else
            {
                // Collect the safety cushion first, then start that far behind live.
                relay.WaitForCushion(cushion, (cushion + 30) * 1000, gone);
                pos = relay.LiveStartOffset(cushion);
            }
            var buf = new byte[64 * 1024];
            while (!gone())
            {
                var n = relay.Read(ref pos, buf, 0, buf.Length, gone);
                if (n <= 0) break;
                s.Write(buf, 0, n);
            }
        }

        private static void ServeVod(NetworkStream s, string method, string range, VodFile file, Func<bool> gone)
        {
            if (!file.WaitReady(45000))
            {
                Send(s, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            var length = file.Length;
            long from = 0, to = length - 1;
            var partial = false;
            if (range != null && range.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
            {
                var spec = range.Substring(6).Split(',')[0].Trim();
                var dash = spec.IndexOf('-');
                if (dash >= 0)
                {
                    var a = spec.Substring(0, dash).Trim();
                    var b = spec.Substring(dash + 1).Trim();
                    long x;
                    if (a.Length == 0 && long.TryParse(b, out x)) { from = Math.Max(0, length - x); }
                    else
                    {
                        if (long.TryParse(a, out x)) from = x;
                        if (b.Length > 0 && long.TryParse(b, out x)) to = Math.Min(length - 1, x);
                    }
                    partial = true;
                }
            }
            if (from >= length)
            {
                Send(s, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + length + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            var count = to - from + 1;
            var head = new StringBuilder();
            head.Append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.Append("Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n");
            head.Append("Content-Length: ").Append(count.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
            if (partial) head.Append("Content-Range: bytes ").Append(from).Append('-').Append(to).Append('/').Append(length).Append("\r\n");
            head.Append("Connection: close\r\n\r\n");
            Send(s, head.ToString());
            if (method == "HEAD") return;
            System.Threading.Interlocked.Increment(ref file.Readers);
            try
            {
                var buf = new byte[64 * 1024];
                var pos = from;
                while (pos <= to && !gone())
                {
                    var n = file.Read(pos, buf, 0, (int)Math.Min(buf.Length, to - pos + 1), gone);
                    if (n <= 0) break;
                    s.Write(buf, 0, n);
                    pos += n;
                }
            }
            finally
            {
                System.Threading.Interlocked.Decrement(ref file.Readers);
            }
        }

        private static string ParseQuery(string query, string name)
        {
            foreach (var p in (query ?? "").Split('&'))
            {
                var eq = p.IndexOf('=');
                if (eq > 0 && p.Substring(0, eq) == name) return Uri.UnescapeDataString(p.Substring(eq + 1));
            }
            return null;
        }

        public void Dispose()
        {
            _stopped = true;
            try { _listener.Stop(); } catch { }
            List<LiveRelay> relays;
            List<VodFile> files;
            lock (_lock)
            {
                relays = _live.Values.ToList();
                files = _vod.Values.ToList();
                _live.Clear(); _vod.Clear(); _liveRefs.Clear(); _vodRefs.Clear(); _routes.Clear();
            }
            foreach (var r in relays) try { r.Dispose(); } catch { }
            foreach (var f in files) try { f.Dispose(); } catch { }
        }
    }
}
