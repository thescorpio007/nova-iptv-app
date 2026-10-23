using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using Nova.Core.Streaming;

namespace Nova.Core
{
    public enum DotState { Ok, Bad, Device, Checking, Waiting, Unknown }

    /// <summary>
    /// Status dots, same rules as the box. Quietly checks, one at a time, whether the films / episodes on screen answer:
    ///  green = the stream answers (or played fine here); red = no signal / not found / server error;
    ///  orange = the stream works but this PC couldn't play its format (learned from real playback);
    ///  grey = not checked yet (or waiting for a free connection).
    /// Live channels get their dot from real playback only. Results are kept 6 hours (format problems 30 days).
    /// The server's connection limit is respected, and nothing is checked while something plays.
    /// </summary>
    public sealed class StreamCheck
    {
        public sealed class Target
        {
            public string Key;
            public string Url;
            public string AccountId;
        }

        private sealed class Net { public bool Ok; public long At; public string Note; }

        private const long FreshMs = 6 * 3600000L;
        private const long DeviceMs = 30L * 24 * 3600000L;

        private readonly ConcurrentDictionary<string, Net> _net = new ConcurrentDictionary<string, Net>();
        private readonly ConcurrentDictionary<string, Tuple<long, string>> _device = new ConcurrentDictionary<string, Tuple<long, string>>();
        private readonly ConcurrentDictionary<string, string> _waiting = new ConcurrentDictionary<string, string>();
        private readonly ConcurrentDictionary<string, long> _backOff = new ConcurrentDictionary<string, long>();
        private readonly ConcurrentDictionary<string, bool> _forced = new ConcurrentDictionary<string, bool>();
        private readonly string _file;
        private readonly Func<bool> _enabled;
        private readonly Func<string, int> _connectionsUsed;
        private readonly Func<string, int> _connectionsAllowed;
        private volatile List<Target> _queue = new List<Target>();
        private volatile string _checking;
        private volatile RawResponse _call;
        private volatile bool _dirty;
        private volatile bool _stopped;

        /// <summary>The account the player is using right now (null = nothing playing).</summary>
        public string PlayingAccount { get; private set; }

        /// <summary>A dot changed (background thread): lists redraw.</summary>
        public event Action Changed;

        public StreamCheck(string dataFolder, Func<bool> enabled, Func<string, int> connectionsUsed, Func<string, int> connectionsAllowed)
        {
            _file = Path.Combine(dataFolder, "stream-status.json");
            _enabled = enabled;
            _connectionsUsed = connectionsUsed;
            _connectionsAllowed = connectionsAllowed;
            try { Load(); } catch { }
            new Thread(Worker) { IsBackground = true, Name = "Nova status dots", Priority = ThreadPriority.BelowNormal }.Start();
        }

        public static string KeyOf(string type, string key) { return type + "|" + key; }

        private static long Now { get { return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); } }

        private void Bump()
        {
            _dirty = true;
            var h = Changed;
            if (h != null) try { h(); } catch { }
        }

        /// <summary>The dot of one item, with its note ("not found", "waits: 1 connection"…).</summary>
        public DotState Status(string key, out string note)
        {
            note = null;
            if (key == null) return DotState.Unknown;
            if (key == _checking) return DotState.Checking;
            var now = Now;
            Net n;
            if (_net.TryGetValue(key, out n) && now - n.At >= FreshMs) n = null;
            if (n != null && !n.Ok) { note = n.Note; return DotState.Bad; }
            Tuple<long, string> d;
            if (_device.TryGetValue(key, out d) && now - d.Item1 < DeviceMs) { note = d.Item2; return DotState.Device; }
            if (n != null) return DotState.Ok;
            string w;
            if (_waiting.TryGetValue(key, out w)) { note = w; return DotState.Waiting; }
            return DotState.Unknown;
        }

        public DotState Status(string key) { string n; return Status(key, out n); }

        /// <summary>What is on screen now: only these are checked (the previous list is dropped).</summary>
        public void Want(IEnumerable<Target> targets)
        {
            if (!_enabled()) return;
            var seen = new HashSet<string>();
            var list = targets.Where(t => t.Key != null && t.Url != null && seen.Add(t.Key)).Take(300).ToList();
            _queue = list;
            foreach (var k in _waiting.Keys.ToList()) if (!seen.Contains(k)) { string x; _waiting.TryRemove(k, out x); }
        }

        /// <summary>"⟳ Test again": check these again even when recently checked.</summary>
        public void Retest(IEnumerable<Target> targets)
        {
            var list = targets.ToList();
            foreach (var t in list) _forced[t.Key] = true;
            Bump();
            Want(list);
        }

        /// <summary>"Checked 9 / 18": (checked, total).</summary>
        public Tuple<int, int> Progress(IList<string> keys)
        {
            var now = Now;
            var done = keys.Count(k => { Net n; return (_net.TryGetValue(k, out n) && now - n.At < FreshMs) || _device.ContainsKey(k); });
            return Tuple.Create(done, keys.Count);
        }

        private bool Needs(Target t, long now)
        {
            if (_forced.ContainsKey(t.Key)) return true;
            Net n;
            if (_net.TryGetValue(t.Key, out n)) return now - n.At >= FreshMs;
            return !_device.ContainsKey(t.Key);
        }

        // ------------------------------------------------------------ results from real playback

        /// <summary>Playback starts on this account (null = stopped): a check running there is stopped at once.</summary>
        public void Playing(string accountId)
        {
            PlayingAccount = string.IsNullOrEmpty(accountId) ? null : accountId;
            if (accountId != null) { var c = _call; if (c != null) try { c.Dispose(); } catch { } }
        }

        /// <summary>It played (picture or sound came): green. A good decode clears a format problem.</summary>
        public void Played(string key, bool videoDecoded)
        {
            if (string.IsNullOrEmpty(key)) return;
            _net[key] = new Net { Ok = true, At = Now };
            if (videoDecoded) { Tuple<long, string> x; _device.TryRemove(key, out x); }
            Bump();
        }

        /// <summary>It didn't play because of the connection / server: red.</summary>
        public void Failed(string key, string note)
        {
            if (string.IsNullOrEmpty(key)) return;
            _net[key] = new Net { Ok = false, At = Now, Note = note };
            Bump();
        }

        /// <summary>The stream came but this PC couldn't decode it: orange.</summary>
        public void DeviceFailed(string key, string note)
        {
            if (string.IsNullOrEmpty(key)) return;
            _device[key] = Tuple.Create(Now, note);
            _net[key] = new Net { Ok = true, At = Now };
            Bump();
        }

        // ------------------------------------------------------------ the background checker

        private void Worker()
        {
            var lastSave = DateTime.UtcNow;
            while (!_stopped)
            {
                try
                {
                    if (_dirty && (DateTime.UtcNow - lastSave).TotalSeconds > 5) { _dirty = false; lastSave = DateTime.UtcNow; Save(); }
                    var now = Now;
                    // Never while the player is open: videos play undisturbed.
                    var q = _enabled() && PlayingAccount == null ? _queue : new List<Target>();
                    Target picked = null;
                    var waitChanged = false;
                    foreach (var t in q)
                    {
                        if (!Needs(t, now)) continue;
                        long until;
                        if (_backOff.TryGetValue(t.AccountId ?? "", out until) && until > now)
                        {
                            if (_waiting.TryAdd(t.Key, "server busy — later")) waitChanged = true;
                            continue;
                        }
                        var allowed = Math.Max(1, _connectionsAllowed(t.AccountId));
                        if (_connectionsUsed(t.AccountId) + 1 > allowed)
                        {
                            if (_waiting.TryAdd(t.Key, allowed == 1 ? "waits: 1 connection" : "waits: connections in use")) waitChanged = true;
                            continue;
                        }
                        string x;
                        if (_waiting.TryRemove(t.Key, out x)) waitChanged = true;
                        picked = t;
                        break;
                    }
                    if (waitChanged) Bump();
                    if (picked == null) { Thread.Sleep(1500); continue; }
                    _checking = picked.Key;
                    Bump();
                    string result;
                    try { result = Probe(picked.Url); }
                    catch (Exception e) { result = "bad:" + Note(e); }
                    _checking = null;
                    bool y;
                    _forced.TryRemove(picked.Key, out y);
                    if (result == "ok") _net[picked.Key] = new Net { Ok = true, At = Now };
                    else if (result == "busy")
                    {
                        _backOff[picked.AccountId ?? ""] = Now + 60000;
                        _waiting[picked.Key] = "server busy — later";
                    }
                    else if (result.StartsWith("bad:")) _net[picked.Key] = new Net { Ok = false, At = Now, Note = result.Substring(4) };
                    // "stopped": playback took the connection, checked again later
                    Bump();
                    Thread.Sleep(250); // never hammer the server
                }
                catch { Thread.Sleep(2000); }
            }
        }

        private static string Note(Exception e)
        {
            for (var x = e; x != null; x = x.InnerException)
            {
                var se = x as System.Net.Sockets.SocketException;
                if (se == null) continue;
                if (se.SocketErrorCode == System.Net.Sockets.SocketError.HostNotFound) return "server not found";
                if (se.SocketErrorCode == System.Net.Sockets.SocketError.ConnectionRefused) return "server not answering";
                if (se.SocketErrorCode == System.Net.Sockets.SocketError.TimedOut) return "no signal";
            }
            return "no answer";
        }

        private string Probe(string url)
        {
            var isHls = url.Split('?')[0].EndsWith(".m3u8", StringComparison.OrdinalIgnoreCase);
            RawResponse r;
            try { r = HttpPolicy.Open(url, isHls ? -1 : 0, 5000, 5000); }
            catch (Exception) { if (PlayingAccount != null) return "stopped"; throw; }
            _call = r;
            try
            {
                switch (r.Status)
                {
                    case 429: case 456: case 458: case 509: return "busy";
                    case 401: case 403: return "bad:access refused";
                    case 404: case 410: return "bad:not found";
                }
                if (r.Status >= 500) return "bad:server error";
                if (r.Status >= 400) return "bad:server answered " + r.Status;
                string type;
                r.Headers.TryGetValue("Content-Type", out type);
                if (isHls)
                {
                    var buf = new byte[65536];
                    var got = 0;
                    int n;
                    while (got < buf.Length && (n = r.Body.Read(buf, got, buf.Length - got)) > 0) got += n;
                    var text = Encoding.UTF8.GetString(buf, 0, got);
                    if (!text.Contains("#EXTM3U")) return "bad:server error";
                    var line = text.Split('\n').Select(l => l.Trim()).FirstOrDefault(l => l.Length > 0 && !l.StartsWith("#"));
                    if (line == null) return "bad:no signal";
                    Uri next;
                    if (!Uri.TryCreate(new Uri(url), line, out next)) return "bad:no signal";
                    r.Dispose();
                    return Probe(next.AbsoluteUri);
                }
                if ((type ?? "").StartsWith("text/html", StringComparison.OrdinalIgnoreCase)) return "bad:server error";
                // A film must have data: read the first kilobyte.
                var b = new byte[16 * 1024];
                long total = 0;
                var until = DateTime.UtcNow.AddSeconds(6);
                while (total < 1024 && DateTime.UtcNow < until)
                {
                    var n2 = r.Body.Read(b, 0, b.Length);
                    if (n2 <= 0) break;
                    total += n2;
                }
                return total >= 1024 ? "ok" : "bad:no signal";
            }
            catch (Exception)
            {
                if (PlayingAccount != null) return "stopped";
                throw;
            }
            finally
            {
                _call = null;
                r.Dispose();
            }
        }

        // ------------------------------------------------------------ saved on the PC

        private void Load()
        {
            if (!File.Exists(_file)) return;
            var o = JObject.Parse(File.ReadAllText(_file, Encoding.UTF8));
            var now = Now;
            var net = o["net"] as JObject;
            if (net != null)
                foreach (var p in net.Properties())
                {
                    var a = p.Value as JArray;
                    if (a == null || a.Count < 2) continue;
                    var at = (long)a[1];
                    if (now - at < FreshMs) _net[p.Name] = new Net { Ok = (bool)a[0], At = at, Note = a.Count > 2 ? (string)a[2] : null };
                }
            var dev = o["device"] as JObject;
            if (dev != null)
                foreach (var p in dev.Properties())
                {
                    var a = p.Value as JArray;
                    if (a == null || a.Count < 2) continue;
                    var at = (long)a[0];
                    if (now - at < DeviceMs) _device[p.Name] = Tuple.Create(at, (string)a[1]);
                }
        }

        private void Save()
        {
            try
            {
                var now = Now;
                var net = new JObject();
                foreach (var kv in _net) if (now - kv.Value.At < FreshMs) net[kv.Key] = new JArray(kv.Value.Ok, kv.Value.At, kv.Value.Note ?? "");
                var dev = new JObject();
                foreach (var kv in _device) dev[kv.Key] = new JArray(kv.Value.Item1, kv.Value.Item2);
                var tmp = _file + ".tmp";
                File.WriteAllText(tmp, new JObject { ["net"] = net, ["device"] = dev }.ToString(Formatting.None), new UTF8Encoding(false));
                if (File.Exists(_file)) File.Delete(_file);
                File.Move(tmp, _file);
            }
            catch { }
        }

        public void Stop() { _stopped = true; Save(); }
    }
}
