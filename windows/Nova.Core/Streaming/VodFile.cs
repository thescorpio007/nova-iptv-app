using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

namespace Nova.Core.Streaming
{
    public enum VodState { Probing, Downloading, Waiting, Reconnecting, Complete, Failed, Stopped }

    /// <summary>
    /// A film or episode stored on disk in 4 MB pieces.
    /// Watching (cache mode) uses ONE connection that downloads ahead of the player; when the player jumps
    /// (an MKV/MP4 index at the end of the file, or a seek) the download moves there, then comes back.
    /// Downloading (full mode) can use several connections, each fetching other pieces, in watching order,
    /// with an optional speed limit. Progress is saved, so a download resumes after a restart.
    /// A server without byte ranges can still be downloaded in full mode, from the start in one go.
    /// </summary>
    public sealed class VodFile : IDisposable
    {
        public const int ChunkSize = 4 * 1024 * 1024;

        private readonly object _lock = new object();
        private readonly string _url;
        private readonly string _folder;
        private readonly int _stallMs;
        private readonly long _readAhead;
        private bool _full;
        private long _length = -1;
        private int[] _filled;
        private int[] _claimed;
        private int _chunks;
        private int _focus;
        private bool _plain;
        private int _connections = 1;
        private readonly List<Thread> _workers = new List<Thread>();
        private readonly Dictionary<int, RawResponse> _open = new Dictionary<int, RawResponse>();
        private volatile bool _stopped;
        private bool _completeReported;
        private DateTime _lastMetaSave = DateTime.MinValue;
        private Thread _main;

        public string Key { get; private set; }
        /// <summary>Account the file comes from (connection limits), or null.</summary>
        public string AccountId { get; set; }
        public VodState State { get; private set; }
        public string Message { get; private set; }
        public int Attempt { get; private set; }
        public int Readers;
        /// <summary>Extra request headers for links from websites.</summary>
        public Dictionary<string, string> Headers { get; set; }
        /// <summary>Speed limit for downloads (not applied while someone watches).</summary>
        public Throttle Throttle { get; set; }

        public event Action<VodFile> Changed;
        /// <summary>Every byte is on disk (called once, on a background thread).</summary>
        public event Action<VodFile> Completed;

        public VodFile(string key, string url, Func<string> userAgent, string folder, bool full, long readAheadBytes, int stallSeconds)
        {
            Key = key;
            _url = url;
            _folder = folder;
            _full = full;
            _readAhead = Math.Max(ChunkSize * 2L, readAheadBytes);
            _stallMs = Math.Max(5, stallSeconds) * 1000;
            Directory.CreateDirectory(folder);
            LoadMeta();
        }

        public string Folder { get { return _folder; } }
        public string Url { get { return _url; } }
        public long Length { get { lock (_lock) return _length; } }

        /// <summary>Download the whole file (true) or only ahead of the player (false).</summary>
        public bool Full
        {
            get { lock (_lock) return _full; }
            set { lock (_lock) { _full = value; Monitor.PulseAll(_lock); } }
        }

        /// <summary>Connections used in full mode (1–16). Watching alone always uses one.</summary>
        public int Connections
        {
            get { lock (_lock) return _connections; }
            set { lock (_lock) { _connections = Math.Max(1, Math.Min(16, value)); Monitor.PulseAll(_lock); } }
        }

        private int ActiveWanted
        {
            get { return _full && !_plain ? _connections : 1; }
        }

        public long Downloaded
        {
            get
            {
                lock (_lock)
                {
                    if (_filled == null) return 0;
                    long sum = 0;
                    foreach (var f in _filled) sum += f;
                    return sum;
                }
            }
        }

        public bool IsComplete
        {
            get { lock (_lock) return CompleteLocked(); }
        }

        private bool CompleteLocked()
        {
            if (_filled == null || _length <= 0) return false;
            for (int i = 0; i < _chunks; i++) if (_filled[i] < ChunkLen(i)) return false;
            return true;
        }

        public void Begin()
        {
            if (_main != null) return;
            State = _length > 0 ? VodState.Downloading : VodState.Probing;
            _main = new Thread(Run) { IsBackground = true, Name = "Nova file " + Key };
            _main.Start();
        }

        /// <summary>Waits until the size is known (true) or the file can't be used this way (false).</summary>
        public bool WaitReady(int timeoutMs)
        {
            var until = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTime.UtcNow < until)
            {
                if (Length > 0 && !_plain) return true;
                if (State == VodState.Failed || _stopped || _plain) return false;
                Thread.Sleep(50);
            }
            return Length > 0 && !_plain;
        }

        private int ChunkLen(int i)
        {
            return (int)Math.Min(ChunkSize, _length - (long)i * ChunkSize);
        }

        private string ChunkPath(int i)
        {
            return Path.Combine(_folder, "c" + i.ToString("D6", CultureInfo.InvariantCulture) + ".bin");
        }

        private void SetState(VodState s, string message)
        {
            State = s;
            Message = message;
            var h = Changed;
            if (h != null) try { h(this); } catch { }
        }

        // ------------------------------------------------------------------ main loop

        private void Run()
        {
            var backoff = 1000;
            while (!_stopped && Length <= 0)
            {
                try
                {
                    SetState(VodState.Probing, null);
                    var probe = HttpPolicy.Open(_url, 0, 15000, _stallMs, Headers);
                    if (probe.Status >= 400) { var st = probe.Status; probe.Dispose(); throw new NovaException(HttpPolicy.StatusMessage(st)); }
                    var total = probe.TotalLength;
                    if (probe.Status != 206 || total <= 0)
                    {
                        if (_full) { RunPlain(probe); return; }
                        // No byte ranges: this file can't be cached in pieces (the player plays it directly).
                        probe.Dispose();
                        SetState(VodState.Failed, "The server doesn't allow reading this file in parts.");
                        return;
                    }
                    probe.Dispose();
                    lock (_lock)
                    {
                        _length = total;
                        _chunks = (int)((total + ChunkSize - 1) / ChunkSize);
                        _filled = new int[_chunks];
                        _claimed = Enumerable.Repeat(-1, _chunks).ToArray();
                    }
                    SaveMeta(true);
                }
                catch (Exception e)
                {
                    if (_stopped) return;
                    Attempt++;
                    var msg = e is NovaException ? e.Message : LiveRelay.Describe(e, false);
                    SetState(VodState.Reconnecting, msg);
                    if (e is NovaException && Attempt >= 3) { SetState(VodState.Failed, msg); return; }
                    var until = DateTime.UtcNow.AddMilliseconds(backoff);
                    while (!_stopped && DateTime.UtcNow < until) Thread.Sleep(100);
                    backoff = Math.Min(30000, backoff * 2);
                }
            }
            if (_stopped) return;
            lock (_lock) if (_claimed == null) _claimed = Enumerable.Repeat(-1, _chunks).ToArray();
            SetState(VodState.Downloading, null);
            // Start the workers (more are added if the number of connections goes up).
            while (!_stopped)
            {
                int wanted;
                lock (_lock)
                {
                    wanted = ActiveWanted;
                    while (_workers.Count < wanted)
                    {
                        var id = _workers.Count;
                        var t = new Thread(() => Worker(id)) { IsBackground = true, Name = "Nova file " + Key + " #" + id };
                        _workers.Add(t);
                        t.Start();
                    }
                }
                ReportCompleteIfDone();
                lock (_lock) { if (!_stopped) Monitor.Wait(_lock, 1000); }
            }
        }

        private void ReportCompleteIfDone()
        {
            if (_completeReported || !IsComplete) return;
            _completeReported = true;
            SaveMeta(true);
            SetState(VodState.Complete, null);
            var c = Completed;
            if (c != null) try { c(this); } catch { }
        }

        // ------------------------------------------------------------------ workers

        private void Worker(int id)
        {
            var backoff = 1000;
            while (!_stopped)
            {
                int target;
                lock (_lock)
                {
                    target = id < ActiveWanted ? NextTarget(id) : -1;
                    if (target < 0)
                    {
                        if (!_stopped) Monitor.Wait(_lock, 700);
                        continue;
                    }
                    _claimed[target] = id;
                }
                try
                {
                    long startAt;
                    lock (_lock) startAt = (long)target * ChunkSize + _filled[target];
                    var resp = HttpPolicy.Open(_url, startAt, 15000, _stallMs, Headers);
                    lock (_lock) _open[id] = resp;
                    using (resp)
                    {
                        if (resp.Status == 403 || resp.Status == 429 || resp.Status == 503)
                        {
                            // A website that refuses several connections: go back to one by itself.
                            if (Connections > 1) { Connections = 1; Message = "The website allows only one connection: continuing with one."; }
                            throw new NovaException(HttpPolicy.StatusMessage(resp.Status));
                        }
                        if (resp.Status >= 400) throw new NovaException(HttpPolicy.StatusMessage(resp.Status));
                        if (resp.Status != 206 || resp.RangeStart != startAt)
                        {
                            if (!(startAt == 0 && (resp.Status == 206 || resp.Status == 200)))
                                throw new IOException("The server answered from the wrong place.");
                        }
                        if (State != VodState.Downloading && State != VodState.Complete) { Attempt = 0; SetState(VodState.Downloading, null); }
                        if (Pump(id, resp, target)) backoff = 1000;
                    }
                }
                catch (Exception e)
                {
                    if (_stopped) break;
                    Attempt++;
                    SetState(VodState.Reconnecting, e is NovaException ? e.Message : LiveRelay.Describe(e, true));
                    var until = DateTime.UtcNow.AddMilliseconds(backoff);
                    while (!_stopped && DateTime.UtcNow < until) Thread.Sleep(100);
                    backoff = Math.Min(30000, backoff * 2);
                }
                finally
                {
                    lock (_lock)
                    {
                        _open.Remove(id);
                        for (int i = 0; i < _chunks; i++) if (_claimed[i] == id) _claimed[i] = -1;
                        Monitor.PulseAll(_lock);
                    }
                    SaveMeta(false);
                }
            }
        }

        /// <summary>Copies the answer into the pieces, piece after piece, while that is still the right work for this worker.</summary>
        private bool Pump(int id, RawResponse resp, int chunk)
        {
            var buf = new byte[64 * 1024];
            var any = false;
            while (!_stopped)
            {
                int already, len;
                lock (_lock) { already = _filled[chunk]; len = ChunkLen(chunk); }
                using (var fs = new FileStream(ChunkPath(chunk), FileMode.OpenOrCreate, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete, 64 * 1024))
                {
                    fs.Position = already;
                    while (already < len && !_stopped)
                    {
                        var n = resp.Body.Read(buf, 0, Math.Min(buf.Length, len - already));
                        if (n <= 0) { fs.Flush(); throw new IOException("The connection ended early."); }
                        fs.Write(buf, 0, n);
                        fs.Flush();
                        any = true;
                        already += n;
                        var throttle = Throttle;
                        if (throttle != null && Readers == 0) throttle.Take(n);
                        lock (_lock)
                        {
                            _filled[chunk] = already;
                            Monitor.PulseAll(_lock);
                            if (already < len && ShouldMove(id, chunk)) { _claimed[chunk] = -1; return any; }
                        }
                        if ((DateTime.UtcNow - _lastMetaSave).TotalSeconds > 5) SaveMeta(false);
                    }
                }
                if (_stopped) return any;
                var h = Changed; if (h != null) try { h(this); } catch { }
                lock (_lock)
                {
                    _claimed[chunk] = -1;
                    var next = chunk + 1;
                    // Keep reading the same answer only if the next piece is empty, free, and wanted next.
                    if (next >= _chunks || _filled[next] != 0 || _claimed[next] != -1 || NextTarget(id) != next) return any;
                    _claimed[next] = id;
                    chunk = next;
                }
            }
            return any;
        }

        /// <summary>
        /// The piece this worker should download next, or -1. Pieces in watching order from where the player
        /// reads; a piece another worker holds is skipped. Call under the lock.
        /// </summary>
        private int NextTarget(int id)
        {
            if (_filled == null) return -1;
            var f = Math.Max(0, Math.Min(_focus, _chunks - 1));
            long ahead = 0;
            for (int i = f; i < _chunks; i++)
            {
                if (_filled[i] < ChunkLen(i))
                {
                    if (_claimed[i] != -1 && _claimed[i] != id) { ahead += ChunkLen(i); continue; }
                    if (_full || ahead < _readAhead) return i;
                    break;
                }
                ahead += ChunkLen(i);
            }
            if (_full)
                for (int i = 0; i < f; i++)
                    if (_filled[i] < ChunkLen(i) && (_claimed[i] == -1 || _claimed[i] == id)) return i;
            return -1;
        }

        /// <summary>
        /// True when this worker should leave its piece for a more urgent one (the player jumped).
        /// Only the worker whose piece is furthest from the player moves, and only if no worker is free.
        /// </summary>
        private bool ShouldMove(int id, int chunk)
        {
            var want = NextTarget(id);
            if (want == chunk || want < 0) return false;
            if (_claimed[want] != -1) return false;
            var busy = new List<int>();
            for (int i = 0; i < _chunks; i++) if (_claimed[i] >= 0) busy.Add(_claimed[i]);
            if (busy.Distinct().Count() < Math.Min(ActiveWanted, _workers.Count)) return false; // a free worker takes it
            // Distance ahead of the player (wrapping in full mode).
            Func<int, int> dist = c => c >= _focus ? c - _focus : c + _chunks;
            var mine = dist(chunk);
            for (int i = 0; i < _chunks; i++)
                if (_claimed[i] >= 0 && _claimed[i] != id && dist(i) > mine) return false;
            return dist(want) < mine;
        }

        // ------------------------------------------------------------------ servers without byte ranges

        /// <summary>Downloads from the start in one go (no resume inside the file; a drop starts it again).</summary>
        private void RunPlain(RawResponse first)
        {
            lock (_lock) _plain = true;
            var resp = first;
            var backoff = 1000;
            while (!_stopped)
            {
                try
                {
                    if (resp == null) resp = HttpPolicy.Open(_url, -1, 15000, _stallMs, Headers);
                    lock (_lock) _open[0] = resp;
                    if (resp.Status >= 400) throw new NovaException(HttpPolicy.StatusMessage(resp.Status));
                    var total = resp.ContentLength;
                    var chunks = new List<int>();
                    var buf = new byte[64 * 1024];
                    long done = 0;
                    var index = 0;
                    FileStream fs = null;
                    lock (_lock)
                    {
                        _length = total;
                        _chunks = total > 0 ? (int)((total + ChunkSize - 1) / ChunkSize) : 0;
                        _filled = new int[Math.Max(1, _chunks)];
                        _claimed = Enumerable.Repeat(-1, _filled.Length).ToArray();
                    }
                    SetState(VodState.Downloading, null);
                    try
                    {
                        while (!_stopped)
                        {
                            var n = resp.Body.Read(buf, 0, buf.Length);
                            if (n <= 0) break;
                            var off = 0;
                            while (off < n)
                            {
                                if (fs == null) { fs = new FileStream(ChunkPath(index), FileMode.Create, FileAccess.Write, FileShare.ReadWrite); chunks.Add(0); }
                                var take = (int)Math.Min(n - off, ChunkSize - chunks[index]);
                                fs.Write(buf, off, take);
                                chunks[index] += take;
                                off += take;
                                done += take;
                                if (chunks[index] >= ChunkSize) { fs.Dispose(); fs = null; index++; }
                            }
                            var throttle = Throttle;
                            if (throttle != null) throttle.Take(n);
                            lock (_lock)
                            {
                                if (_filled.Length < chunks.Count) { Array.Resize(ref _filled, chunks.Count); Array.Resize(ref _claimed, chunks.Count); }
                                for (int i = 0; i < chunks.Count; i++) _filled[i] = chunks[i];
                            }
                        }
                    }
                    finally
                    {
                        if (fs != null) fs.Dispose();
                    }
                    if (_stopped) return;
                    if (total > 0 && done < total) throw new IOException("The connection ended early.");
                    lock (_lock)
                    {
                        _length = done;
                        _chunks = chunks.Count;
                        _filled = chunks.ToArray();
                        _claimed = Enumerable.Repeat(-1, _chunks).ToArray();
                    }
                    resp.Dispose();
                    ReportCompleteIfDone();
                    return;
                }
                catch (Exception e)
                {
                    if (resp != null) { resp.Dispose(); resp = null; }
                    if (_stopped) return;
                    Attempt++;
                    SetState(VodState.Reconnecting, (e is NovaException ? e.Message : LiveRelay.Describe(e, true)) + " Starting again from the beginning…");
                    var until = DateTime.UtcNow.AddMilliseconds(backoff);
                    while (!_stopped && DateTime.UtcNow < until) Thread.Sleep(100);
                    backoff = Math.Min(30000, backoff * 2);
                }
            }
        }

        // ------------------------------------------------------------------ reading

        /// <summary>
        /// Reads at pos (waiting for the download if needed). Returns 0 at the end of the file,
        /// when stopped, or when the reader gave up.
        /// </summary>
        public int Read(long pos, byte[] buf, int offset, int count, Func<bool> cancelled)
        {
            while (!_stopped && !cancelled())
            {
                int chunk, within, avail;
                lock (_lock)
                {
                    if (_length <= 0 || _plain)
                    {
                        if (State == VodState.Failed || _plain) return 0;
                        Monitor.Wait(_lock, 200);
                        continue;
                    }
                    if (pos >= _length) return 0;
                    chunk = (int)(pos / ChunkSize);
                    within = (int)(pos % ChunkSize);
                    if (_focus != chunk) { _focus = chunk; Monitor.PulseAll(_lock); }
                    avail = _filled[chunk] - within;
                    if (avail <= 0)
                    {
                        Monitor.Wait(_lock, 300);
                        continue;
                    }
                }
                var want = Math.Min(count, avail);
                try
                {
                    using (var fs = new FileStream(ChunkPath(chunk), FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                    {
                        fs.Position = within;
                        var total = 0;
                        while (total < want)
                        {
                            var n = fs.Read(buf, offset + total, want - total);
                            if (n <= 0) break;
                            total += n;
                        }
                        if (total > 0) return total;
                    }
                }
                catch (IOException) { }
                Thread.Sleep(30);
            }
            return 0;
        }

        // ------------------------------------------------------------------ finishing

        /// <summary>Joins the pieces into one normal file and removes the pieces.</summary>
        public void Assemble(string finalPath)
        {
            if (!IsComplete) throw new InvalidOperationException("The download is not complete.");
            Dispose();
            Directory.CreateDirectory(Path.GetDirectoryName(finalPath));
            var tmp = finalPath + ".joining";
            using (var output = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None, 1024 * 1024))
            {
                var buf = new byte[1024 * 1024];
                for (int i = 0; i < _chunks; i++)
                {
                    using (var input = new FileStream(ChunkPath(i), FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 1024))
                    {
                        int n;
                        while ((n = input.Read(buf, 0, buf.Length)) > 0) output.Write(buf, 0, n);
                    }
                }
            }
            if (File.Exists(finalPath)) File.Delete(finalPath);
            File.Move(tmp, finalPath);
            try { Directory.Delete(_folder, true); } catch { }
        }

        public void Dispose()
        {
            if (_stopped) return;
            _stopped = true;
            List<RawResponse> open;
            lock (_lock) { open = _open.Values.ToList(); Monitor.PulseAll(_lock); }
            foreach (var r in open) try { r.Dispose(); } catch { }
            if (_main != null && _main != Thread.CurrentThread) _main.Join(3000);
            List<Thread> workers;
            lock (_lock) workers = _workers.ToList();
            foreach (var w in workers) if (w != Thread.CurrentThread) w.Join(3000);
            SaveMeta(true);
        }

        public bool IsStopped { get { return _stopped; } }

        /// <summary>Deletes everything on disk for this file.</summary>
        public void DeleteFiles()
        {
            Dispose();
            try { Directory.Delete(_folder, true); } catch { }
        }

        // ------------------------------------------------------------------ progress file

        private string MetaPath { get { return Path.Combine(_folder, "progress.txt"); } }

        private void SaveMeta(bool force)
        {
            try
            {
                string text;
                lock (_lock)
                {
                    if (_filled == null || _plain) return;
                    if (!force && (DateTime.UtcNow - _lastMetaSave).TotalSeconds < 1) return;
                    _lastMetaSave = DateTime.UtcNow;
                    text = _url + "\n" + _length.ToString(CultureInfo.InvariantCulture) + "\n" +
                           string.Join(",", _filled.Select(x => x.ToString(CultureInfo.InvariantCulture)));
                }
                var tmp = MetaPath + ".tmp";
                File.WriteAllText(tmp, text, Encoding.UTF8);
                if (File.Exists(MetaPath)) File.Delete(MetaPath);
                File.Move(tmp, MetaPath);
            }
            catch { }
        }

        private void LoadMeta()
        {
            try
            {
                if (!File.Exists(MetaPath)) return;
                var lines = File.ReadAllText(MetaPath, Encoding.UTF8).Split('\n');
                if (lines.Length < 3 || lines[0] != _url) return;
                var length = long.Parse(lines[1], CultureInfo.InvariantCulture);
                var filled = lines[2].Split(',').Select(x => int.Parse(x, CultureInfo.InvariantCulture)).ToArray();
                var chunks = (int)((length + ChunkSize - 1) / ChunkSize);
                if (length <= 0 || filled.Length != chunks) return;
                // Trust a piece only as far as its file really goes.
                for (int i = 0; i < chunks; i++)
                {
                    var fi = new FileInfo(ChunkPath(i));
                    var onDisk = fi.Exists ? (int)Math.Min(int.MaxValue, fi.Length) : 0;
                    filled[i] = Math.Max(0, Math.Min(filled[i], onDisk));
                }
                _length = length;
                _chunks = chunks;
                _filled = filled;
                _claimed = Enumerable.Repeat(-1, chunks).ToArray();
            }
            catch { }
        }
    }
}
