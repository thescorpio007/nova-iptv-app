using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;

namespace Nova.Core.Streaming
{
    public enum RelayState { Connecting, Playing, Reconnecting, Stopped }

    /// <summary>
    /// Downloads one live channel into rolling files on disk, and lets the player read it from there.
    /// The watchdog reconnects whenever the stream stops, freezes or is refused, for as long as
    /// someone is watching or recording. Oldest files are deleted at the size limit.
    /// A channel that never sends anything is reported once through <see cref="Dead"/> (for auto-skip).
    /// </summary>
    public sealed class LiveRelay : IDisposable
    {
        private const long SegmentSize = 8L * 1024 * 1024;

        private sealed class Segment
        {
            public long Offset;
            public long Length;
            public string Path;
        }

        private readonly object _lock = new object();
        private readonly List<Segment> _segments = new List<Segment>();
        private readonly string _url;
        private readonly Func<string> _userAgent;
        private readonly string _folder;
        private readonly long _maxBytes;
        private readonly int _stallMs;
        private readonly int _deadMs;
        private Thread _thread;
        private volatile bool _stopped;
        private RawResponse _current;
        private FileStream _writer;
        private FileStream _recorder;
        private long _start, _end;
        private DateTime _firstData = DateTime.MinValue;
        private DateTime _began;
        private bool _deadReported;
        private double _bps;
        private long _lastReaderPos = -1;

        public string Key { get; private set; }
        public string Url { get { return _url; } }
        public RelayState State { get; private set; }
        /// <summary>Why the stream isn't playing (shown on screen), or null.</summary>
        public string Message { get; private set; }
        /// <summary>Reconnect attempts since the last good data.</summary>
        public int Attempt { get; private set; }
        public bool EverReceived { get { return _firstData != DateTime.MinValue; } }
        public string RecordingPath { get; private set; }
        public DateTime RecordingSince { get; private set; }
        public long RecordedBytes { get; private set; }

        /// <summary>State, message or attempt changed (called on a background thread).</summary>
        public event Action<LiveRelay> Changed;
        /// <summary>The channel never sent any video (called once, on a background thread).</summary>
        public event Action<LiveRelay> Dead;

        public LiveRelay(string key, string url, Func<string> userAgent, string folder, long maxBytes, int stallSeconds, int deadSeconds)
        {
            Key = key;
            _url = url;
            _userAgent = userAgent;
            _folder = folder;
            _maxBytes = Math.Max(SegmentSize * 3, maxBytes);
            _stallMs = Math.Max(3, stallSeconds) * 1000;
            _deadMs = Math.Max(5, deadSeconds) * 1000;
        }

        public void Begin()
        {
            Directory.CreateDirectory(_folder);
            _began = DateTime.UtcNow;
            SetState(RelayState.Connecting, null);
            _thread = new Thread(Run) { IsBackground = true, Name = "Nova live " + Key };
            _thread.Start();
        }

        /// <summary>Oldest and newest byte kept on disk (stream offsets).</summary>
        public long Start { get { lock (_lock) return _start; } }
        public long End { get { lock (_lock) return _end; } }

        /// <summary>Average bytes per second of the channel (0 until known).</summary>
        public double BytesPerSecond { get { lock (_lock) return _bps; } }

        /// <summary>Seconds of video since the first byte arrived.</summary>
        public double SecondsReceived
        {
            get { return EverReceived ? (DateTime.UtcNow - _firstData).TotalSeconds : 0; }
        }

        /// <summary>Where the player is reading (for rewind / back to live), or -1.</summary>
        public long ReaderPosition { get { lock (_lock) return _lastReaderPos; } }

        // ------------------------------------------------------------------ download loop

        private void Run()
        {
            var backoff = 1000;
            while (!_stopped)
            {
                var gotData = false;
                try
                {
                    var resp = RawHttp.Open(_url, _userAgent(), -1, 12000, _stallMs);
                    lock (_lock) _current = resp;
                    if (_stopped) { resp.Dispose(); break; }
                    if (resp.Status >= 400)
                    {
                        resp.Dispose();
                        throw new RefusedException(resp.Status);
                    }
                    var buf = new byte[64 * 1024];
                    var rateStart = DateTime.UtcNow;
                    long rateBytes = 0;
                    while (!_stopped)
                    {
                        var n = resp.Body.Read(buf, 0, buf.Length);
                        if (n <= 0) break; // the server ended the stream
                        if (!gotData)
                        {
                            gotData = true;
                            backoff = 1000;
                            if (_firstData == DateTime.MinValue) _firstData = DateTime.UtcNow;
                            Attempt = 0;
                            SetState(RelayState.Playing, null);
                        }
                        Append(buf, n);
                        rateBytes += n;
                        var secs = (DateTime.UtcNow - rateStart).TotalSeconds;
                        if (secs >= 4)
                        {
                            var rate = rateBytes / secs;
                            lock (_lock) _bps = _bps <= 0 ? rate : _bps * 0.7 + rate * 0.3;
                            rateStart = DateTime.UtcNow;
                            rateBytes = 0;
                        }
                    }
                    resp.Dispose();
                    if (!_stopped) Retry(gotData ? "The stream stopped." : "The channel sent nothing.", ref backoff);
                }
                catch (RefusedException e)
                {
                    if (_stopped) break;
                    Retry("The server refused the channel (HTTP " + e.Status + ")" + (e.Status == 403 || e.Status == 401 || e.Status == 458 ? " — too many connections or VPN off?" : "."), ref backoff);
                }
                catch (Exception e)
                {
                    if (_stopped) break;
                    Retry(Describe(e, gotData), ref backoff);
                }
                finally
                {
                    lock (_lock) _current = null;
                }
            }
            SetState(RelayState.Stopped, null);
        }

        /// <summary>A short reason for the screen.</summary>
        internal static string Describe(Exception e, bool hadData)
        {
            for (var x = e; x != null; x = x.InnerException)
            {
                var se = x as System.Net.Sockets.SocketException;
                if (se == null) continue;
                if (se.SocketErrorCode == System.Net.Sockets.SocketError.TimedOut)
                    return hadData ? "The stream froze." : "The server did not answer.";
                if (se.SocketErrorCode == System.Net.Sockets.SocketError.HostNotFound ||
                    se.SocketErrorCode == System.Net.Sockets.SocketError.NetworkUnreachable ||
                    se.SocketErrorCode == System.Net.Sockets.SocketError.ConnectionRefused)
                    return "No connection to the server — VPN or internet off?";
                return "Connection lost.";
            }
            if (e.Message.IndexOf("timed out", StringComparison.OrdinalIgnoreCase) >= 0) return "The server did not answer.";
            return "Connection lost.";
        }

        private sealed class RefusedException : Exception
        {
            public readonly int Status;
            public RefusedException(int status) : base("HTTP " + status) { Status = status; }
        }

        private void Retry(string why, ref int backoff)
        {
            Attempt++;
            SetState(RelayState.Reconnecting, why);
            if (!EverReceived && !_deadReported && (DateTime.UtcNow - _began).TotalMilliseconds >= _deadMs)
            {
                _deadReported = true;
                var d = Dead;
                if (d != null) try { d(this); } catch { }
            }
            // Wait 1 s, 2 s, 4 s … up to 30 s between attempts (stops at once when closed).
            var until = DateTime.UtcNow.AddMilliseconds(backoff);
            while (!_stopped && DateTime.UtcNow < until)
            {
                Thread.Sleep(100);
                if (!EverReceived && !_deadReported && (DateTime.UtcNow - _began).TotalMilliseconds >= _deadMs)
                {
                    _deadReported = true;
                    var d = Dead;
                    if (d != null) try { d(this); } catch { }
                }
            }
            backoff = Math.Min(30000, backoff * 2);
            if (!_stopped) SetState(RelayState.Reconnecting, why);
        }

        private void SetState(RelayState s, string message)
        {
            State = s;
            Message = message;
            var h = Changed;
            if (h != null) try { h(this); } catch { }
        }

        private void Append(byte[] buf, int n)
        {
            lock (_lock)
            {
                var off = 0;
                while (off < n)
                {
                    var seg = _segments.Count > 0 ? _segments[_segments.Count - 1] : null;
                    if (seg == null || seg.Length >= SegmentSize)
                    {
                        if (_writer != null) { _writer.Flush(); _writer.Dispose(); }
                        seg = new Segment { Offset = _end, Path = Path.Combine(_folder, "seg" + _segments.Count.ToString("D6") + "_" + _end + ".ts") };
                        _writer = new FileStream(seg.Path, FileMode.Create, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete, 64 * 1024);
                        _segments.Add(seg);
                    }
                    var take = (int)Math.Min(n - off, SegmentSize - seg.Length);
                    _writer.Write(buf, off, take);
                    seg.Length += take;
                    _end += take;
                    off += take;
                }
                _writer.Flush();
                if (_recorder != null)
                {
                    try { _recorder.Write(buf, 0, n); RecordedBytes += n; }
                    catch { StopRecordingLocked(); }
                }
                // Size limit: drop the oldest files (never the one being written).
                while (_end - _start > _maxBytes && _segments.Count > 1)
                {
                    var old = _segments[0];
                    _segments.RemoveAt(0);
                    _start = _segments[0].Offset;
                    try { File.Delete(old.Path); } catch { }
                }
                Monitor.PulseAll(_lock);
            }
        }

        // ------------------------------------------------------------------ reading

        /// <summary>
        /// Where a new reader should start: cushionSeconds behind live (or the oldest byte kept),
        /// on an MPEG-TS packet boundary.
        /// </summary>
        public long LiveStartOffset(int cushionSeconds)
        {
            long start, end; double bps;
            lock (_lock) { start = _start; end = _end; bps = _bps; }
            var back = bps > 0 ? (long)(bps * cushionSeconds) : end - start;
            return AlignTs(Math.Max(start, end - back));
        }

        /// <summary>Waits until the first cushionSeconds of video arrived (or the wait limit passed).</summary>
        public bool WaitForCushion(int cushionSeconds, int maxWaitMs, Func<bool> cancelled)
        {
            var until = DateTime.UtcNow.AddMilliseconds(maxWaitMs);
            while (!_stopped && !cancelled() && DateTime.UtcNow < until)
            {
                if (EverReceived && (DateTime.UtcNow - _firstData).TotalSeconds >= cushionSeconds) return true;
                Thread.Sleep(100);
            }
            return EverReceived;
        }

        /// <summary>Moves an offset forward to the next MPEG-TS packet start (0x47 every 188 bytes).</summary>
        public long AlignTs(long offset)
        {
            var buf = new byte[188 * 4];
            var got = ReadAt(offset, buf, 0, buf.Length);
            for (int i = 0; i + 376 < got; i++)
                if (buf[i] == 0x47 && buf[i + 188] == 0x47 && buf[i + 376] == 0x47) return offset + i;
            return offset;
        }

        /// <summary>
        /// Reads at pos without waiting; returns bytes read (0 = nothing yet).
        /// If pos was already deleted (too far behind), pos moves to the oldest byte kept.
        /// </summary>
        public int ReadAt(long pos, byte[] buf, int offset, int count)
        {
            Segment seg;
            lock (_lock)
            {
                if (pos < _start || pos >= _end) return 0;
                seg = null;
                foreach (var s in _segments)
                    if (pos >= s.Offset && pos < s.Offset + s.Length) { seg = s; break; }
                if (seg == null) return 0;
                count = (int)Math.Min(count, seg.Offset + seg.Length - pos);
            }
            try
            {
                using (var fs = new FileStream(seg.Path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    fs.Position = pos - seg.Offset;
                    var total = 0;
                    while (total < count)
                    {
                        var n = fs.Read(buf, offset + total, count - total);
                        if (n <= 0) break;
                        total += n;
                    }
                    return total;
                }
            }
            catch (IOException) { return 0; }
            catch (UnauthorizedAccessException) { return 0; }
        }

        /// <summary>
        /// Reads at pos, waiting for new data. Returns 0 only when the relay was stopped or the reader gave up.
        /// pos is moved forward past what was read (and to the oldest byte if it fell behind).
        /// </summary>
        public int Read(ref long pos, byte[] buf, int offset, int count, Func<bool> cancelled)
        {
            while (!_stopped && !cancelled())
            {
                lock (_lock)
                {
                    if (pos < _start) pos = _start; // fell behind the size limit
                    if (pos >= _end)
                    {
                        Monitor.Wait(_lock, 500);
                        continue;
                    }
                }
                var n = ReadAt(pos, buf, offset, count);
                if (n > 0)
                {
                    pos += n;
                    lock (_lock) _lastReaderPos = pos;
                    return n;
                }
                Thread.Sleep(20);
            }
            return 0;
        }

        // ------------------------------------------------------------------ recording

        /// <summary>Starts saving the channel from now on to path (.ts). Uses the same connection.</summary>
        public void StartRecording(string path)
        {
            lock (_lock)
            {
                if (_recorder != null) return;
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                _recorder = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read, 256 * 1024);
                RecordingPath = path;
                RecordingSince = DateTime.Now;
                RecordedBytes = 0;
            }
            var h = Changed; if (h != null) try { h(this); } catch { }
        }

        public void StopRecording()
        {
            lock (_lock) StopRecordingLocked();
            var h = Changed; if (h != null) try { h(this); } catch { }
        }

        private void StopRecordingLocked()
        {
            if (_recorder == null) return;
            try { _recorder.Flush(); _recorder.Dispose(); } catch { }
            _recorder = null;
            RecordingPath = null;
        }

        public bool IsRecording { get { lock (_lock) return _recorder != null; } }

        // ------------------------------------------------------------------ stop

        /// <summary>Stops downloading, closes the connection at once and deletes the buffer files.</summary>
        public void Dispose()
        {
            if (_stopped) return;
            _stopped = true;
            RawResponse cur;
            lock (_lock)
            {
                cur = _current;
                Monitor.PulseAll(_lock);
            }
            if (cur != null) cur.Dispose(); // closes the socket: the provider sees the connection end
            if (_thread != null) _thread.Join(3000);
            lock (_lock)
            {
                StopRecordingLocked();
                if (_writer != null) { try { _writer.Dispose(); } catch { } _writer = null; }
                foreach (var s in _segments) { try { File.Delete(s.Path); } catch { } }
                _segments.Clear();
            }
            try { Directory.Delete(_folder, true); } catch { }
        }

        public bool IsStopped { get { return _stopped; } }
    }
}
