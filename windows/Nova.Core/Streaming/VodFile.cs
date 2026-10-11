using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

namespace Nova.Core.Streaming
{
    public enum VodState { Probing, Downloading, Waiting, Reconnecting, Complete, Failed, Stopped }

    /// <summary>
    /// A film or episode stored on disk in 4 MB pieces, downloaded over ONE connection.
    /// The player reads from the pieces; when it jumps (an MKV/MP4 index at the end of the file,
    /// or a seek), the single download moves to that spot, then comes back — so files whose
    /// index is at the end start normally, without a second connection.
    /// Cache mode downloads ahead of the player up to a limit; full mode downloads the whole file
    /// (Download / Download &amp; Watch). Progress is saved, so a download resumes after a restart.
    /// </summary>
    public sealed class VodFile : IDisposable
    {
        public const int ChunkSize = 4 * 1024 * 1024;

        private readonly object _lock = new object();
        private readonly string _url;
        private readonly Func<string> _userAgent;
        private readonly string _folder;
        private readonly int _stallMs;
        private long _readAhead;
        private bool _full;
        private long _length = -1;
        private int[] _filled;
        private int _chunks;
        private int _focus;
        private int _writingChunk = -1;
        private Thread _thread;
        private volatile bool _stopped;
        private RawResponse _current;
        private bool _completeReported;
        private DateTime _lastMetaSave = DateTime.MinValue;

        public string Key { get; private set; }
        /// <summary>Account the file comes from (connection limits), or null.</summary>
        public string AccountId { get; set; }
        public VodState State { get; private set; }
        public string Message { get; private set; }
        public int Attempt { get; private set; }
        public int Readers;

        public event Action<VodFile> Changed;
        /// <summary>Every byte is on disk (called once, on a background thread).</summary>
        public event Action<VodFile> Completed;

        public VodFile(string key, string url, Func<string> userAgent, string folder, bool full, long readAheadBytes, int stallSeconds)
        {
            Key = key;
            _url = url;
            _userAgent = userAgent;
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
            get
            {
                lock (_lock)
                {
                    if (_filled == null) return false;
                    for (int i = 0; i < _chunks; i++) if (_filled[i] < ChunkLen(i)) return false;
                    return true;
                }
            }
        }

        public void Begin()
        {
            if (_thread != null) return;
            State = _length > 0 ? VodState.Downloading : VodState.Probing;
            _thread = new Thread(Run) { IsBackground = true, Name = "Nova file " + Key };
            _thread.Start();
        }

        /// <summary>Waits until the size is known (true) or the file can't be used this way (false).</summary>
        public bool WaitReady(int timeoutMs)
        {
            var until = DateTime.UtcNow.AddMilliseconds(timeoutMs);
            while (DateTime.UtcNow < until)
            {
                if (Length > 0) return true;
                if (State == VodState.Failed || _stopped) return false;
                Thread.Sleep(50);
            }
            return Length > 0;
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

        // ------------------------------------------------------------------ download loop

        private void Run()
        {
            var backoff = 1000;
            RawResponse probe = null;
            while (!_stopped)
            {
                try
                {
                    if (Length <= 0)
                    {
                        SetState(VodState.Probing, null);
                        probe = RawHttp.Open(_url, _userAgent(), 0, 15000, _stallMs);
                        lock (_lock) _current = probe;
                        if (probe.Status >= 400) { var st = probe.Status; probe.Dispose(); probe = null; throw new IOException("The server refused the file (HTTP " + st + ")"); }
                        var total = probe.TotalLength;
                        if (probe.Status != 206 || total <= 0)
                        {
                            // No byte ranges or unknown size: this file can't be cached in pieces.
                            probe.Dispose();
                            SetState(VodState.Failed, "The server doesn't allow reading this file in parts.");
                            return;
                        }
                        lock (_lock)
                        {
                            _length = total;
                            _chunks = (int)((total + ChunkSize - 1) / ChunkSize);
                            _filled = new int[_chunks];
                        }
                        SaveMeta(true);
                    }

                    int target;
                    lock (_lock)
                    {
                        target = NextTarget();
                        if (target < 0)
                        {
                            if (probe != null) { probe.Dispose(); probe = null; }
                            _current = null;
                        }
                    }
                    if (target < 0)
                    {
                        if (IsComplete)
                        {
                            if (!_completeReported)
                            {
                                _completeReported = true;
                                SaveMeta(true);
                                SetState(VodState.Complete, null);
                                var c = Completed;
                                if (c != null) try { c(this); } catch { }
                            }
                        }
                        else if (State != VodState.Waiting) SetState(VodState.Waiting, null);
                        lock (_lock) { if (!_stopped) Monitor.Wait(_lock, 1000); }
                        continue;
                    }

                    long startAt;
                    lock (_lock) startAt = (long)target * ChunkSize + _filled[target];
                    RawResponse resp;
                    if (probe != null && startAt == 0) { resp = probe; probe = null; }
                    else
                    {
                        if (probe != null) { probe.Dispose(); probe = null; }
                        resp = RawHttp.Open(_url, _userAgent(), startAt, 15000, _stallMs);
                    }
                    lock (_lock) _current = resp;
                    using (resp)
                    {
                        if (resp.Status >= 400) throw new IOException("The server refused the file (HTTP " + resp.Status + ")");
                        if (resp.Status != 206 || resp.RangeStart != startAt)
                        {
                            if (!(startAt == 0 && (resp.Status == 206 || resp.Status == 200)))
                                throw new IOException("The server answered from the wrong place.");
                        }
                        if (State != VodState.Downloading || Attempt > 0) { Attempt = 0; SetState(VodState.Downloading, null); }
                        var got = Pump(resp, target);
                        if (got) backoff = 1000;
                    }
                }
                catch (Exception e)
                {
                    if (_stopped) break;
                    if (probe != null) { probe.Dispose(); probe = null; }
                    Attempt++;
                    SetState(VodState.Reconnecting, e is IOException && e.Message.StartsWith("The server") ? e.Message : LiveRelay.Describe(e, true));
                    var until = DateTime.UtcNow.AddMilliseconds(backoff);
                    while (!_stopped && DateTime.UtcNow < until) Thread.Sleep(100);
                    backoff = Math.Min(30000, backoff * 2);
                }
                finally
                {
                    lock (_lock) { _current = null; _writingChunk = -1; }
                }
            }
            SaveMeta(true);
            if (State != VodState.Complete && State != VodState.Failed) SetState(VodState.Stopped, null);
        }

        /// <summary>Copies the answer into the pieces, chunk after chunk, while that is still what's wanted.</summary>
        private bool Pump(RawResponse resp, int chunk)
        {
            var buf = new byte[64 * 1024];
            var any = false;
            while (!_stopped)
            {
                int already, len;
                lock (_lock) { already = _filled[chunk]; len = ChunkLen(chunk); _writingChunk = chunk; }
                using (var fs = new FileStream(ChunkPath(chunk), FileMode.OpenOrCreate, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete, 64 * 1024))
                {
                    fs.Position = already;
                    while (already < len && !_stopped)
                    {
                        var n = resp.Body.Read(buf, 0, Math.Min(buf.Length, len - already));
                        if (n <= 0)
                        {
                            fs.Flush();
                            throw new IOException("The connection ended early.");
                        }
                        fs.Write(buf, 0, n);
                        fs.Flush();
                        any = true;
                        already += n;
                        lock (_lock)
                        {
                            _filled[chunk] = already;
                            Monitor.PulseAll(_lock);
                            // The player jumped somewhere else: stop here and reconnect there.
                            if (already < len && NextTarget() != chunk) { SaveMeta(false); return any; }
                        }
                        if ((DateTime.UtcNow - _lastMetaSave).TotalSeconds > 5) SaveMeta(false);
                    }
                }
                if (_stopped) return any;
                SaveMeta(false);
                var h = Changed; if (h != null) try { h(this); } catch { }
                lock (_lock)
                {
                    var next = chunk + 1;
                    // Keep reading the same answer only if the next piece is empty and wanted next.
                    if (next >= _chunks || _filled[next] != 0 || NextTarget() != next) return any;
                    chunk = next;
                }
            }
            return any;
        }

        /// <summary>The piece to download next, or -1 (nothing wanted right now). Call under the lock.</summary>
        private int NextTarget()
        {
            if (_filled == null) return -1;
            var f = Math.Max(0, Math.Min(_focus, _chunks - 1));
            long ahead = 0;
            for (int i = f; i < _chunks; i++)
            {
                if (_filled[i] < ChunkLen(i))
                {
                    if (_full || ahead < _readAhead) return i;
                    break;
                }
                ahead += ChunkLen(i);
            }
            if (_full)
                for (int i = 0; i < _chunks; i++)
                    if (_filled[i] < ChunkLen(i)) return i;
            return -1;
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
                    if (_length <= 0)
                    {
                        if (State == VodState.Failed) return 0;
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

        /// <summary>Joins the pieces into one normal file (deleting each piece as it goes) and removes the folder.</summary>
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
            RawResponse cur;
            lock (_lock) { cur = _current; Monitor.PulseAll(_lock); }
            if (cur != null) cur.Dispose();
            if (_thread != null && _thread != Thread.CurrentThread) _thread.Join(3000);
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
                    if (_filled == null) return;
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
            }
            catch { }
        }
    }
}
