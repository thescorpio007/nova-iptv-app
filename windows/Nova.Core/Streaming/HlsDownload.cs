using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace Nova.Core.Streaming
{
    /// <summary>A stream that grows on disk (served to the player like a live channel until it is complete).</summary>
    public interface IGrowingSource
    {
        /// <summary>Reads at pos, waiting for more data. 0 = the end (or stopped / gave up).</summary>
        int Read(long pos, byte[] buf, int offset, int count, Func<bool> cancelled);
    }

    /// <summary>
    /// Downloads an HLS video (.m3u8): picks the best quality, downloads the parts with several connections
    /// (AES-128 parts are decrypted), and joins them into one .ts file. Live HLS streams (no end) can't be
    /// downloaded this way. Progress survives a restart (finished parts are kept).
    /// </summary>
    public sealed class HlsDownload : IGrowingSource, IDisposable
    {
        private sealed class Part
        {
            public string Url;
            public string KeyUrl;
            public byte[] Iv;
            public long Seq;
            public double Duration;
            public long Size = -1; // -1 = not downloaded
        }

        private readonly object _lock = new object();
        private readonly string _url;
        private readonly string _folder;
        private readonly Dictionary<string, byte[]> _keys = new Dictionary<string, byte[]>();
        private readonly List<Thread> _threads = new List<Thread>();
        private readonly List<RawResponse> _open = new List<RawResponse>();
        private List<Part> _parts;
        private int _next;
        private volatile bool _stopped;
        private bool _completeReported;

        public string Url { get { return _url; } }
        public Dictionary<string, string> Headers { get; set; }
        public Throttle Throttle { get; set; }
        public int Connections { get; set; }
        public VodState State { get; private set; }
        public string Message { get; private set; }
        public int Readers;

        /// <summary>
        /// Read-ahead mode for watching (0 = download everything): ONE connection downloads the next parts of the version
        /// being played into the disk cache, in order; at most this many bytes are kept (~80 % ahead, older parts behind the
        /// picture are deleted as you watch). The player reads a local playlist, so it can still jump anywhere.
        /// </summary>
        public long LimitBytes { get; set; }
        private volatile int _readIndex;
        private volatile int _want = -1;

        public event Action<HlsDownload> Changed;
        public event Action<HlsDownload> Completed;

        public HlsDownload(string url, string folder, int connections)
        {
            _url = url;
            _folder = folder;
            Connections = Math.Max(1, Math.Min(16, connections));
            Directory.CreateDirectory(folder);
        }

        public int TotalParts { get { lock (_lock) return _parts == null ? 0 : _parts.Count; } }
        public int DoneParts { get { lock (_lock) return _parts == null ? 0 : _parts.Count(p => p.Size >= 0); } }
        public long DoneBytes { get { lock (_lock) return _parts == null ? 0 : _parts.Where(p => p.Size > 0).Sum(p => p.Size); } }

        /// <summary>Estimated full size (average part size × parts), or -1.</summary>
        public long EstimatedBytes
        {
            get
            {
                lock (_lock)
                {
                    if (_parts == null) return -1;
                    var done = _parts.Where(p => p.Size > 0).ToList();
                    if (done.Count == 0) return -1;
                    return (long)(done.Average(p => p.Size) * _parts.Count);
                }
            }
        }

        public bool IsComplete { get { lock (_lock) return _parts != null && _parts.Count > 0 && _parts.All(p => p.Size >= 0); } }

        private string PartPath(int i) { return Path.Combine(_folder, "p" + i.ToString("D6", CultureInfo.InvariantCulture) + ".ts"); }

        private void Set(VodState s, string msg)
        {
            State = s;
            Message = msg;
            var h = Changed; if (h != null) try { h(this); } catch { }
        }

        public void Begin()
        {
            var t = new Thread(Run) { IsBackground = true, Name = "Nova HLS" };
            lock (_lock) _threads.Add(t);
            t.Start();
        }

        private void Run()
        {
            var backoff = 1000;
            while (!_stopped && _parts == null)
            {
                try
                {
                    Set(VodState.Probing, null);
                    var parts = ReadPlaylist(_url, 0);
                    // Parts already on disk from an earlier run.
                    for (int i = 0; i < parts.Count; i++)
                    {
                        var fi = new FileInfo(PartPath(i));
                        if (fi.Exists && File.Exists(PartPath(i) + ".ok")) parts[i].Size = fi.Length;
                    }
                    lock (_lock) _parts = parts;
                }
                catch (NovaException e)
                {
                    Set(VodState.Failed, e.Message);
                    return;
                }
                catch (Exception e)
                {
                    if (_stopped) return;
                    Set(VodState.Reconnecting, LiveRelay.Describe(e, false));
                    var until = DateTime.UtcNow.AddMilliseconds(backoff);
                    while (!_stopped && DateTime.UtcNow < until) Thread.Sleep(100);
                    backoff = Math.Min(30000, backoff * 2);
                }
            }
            if (_stopped) return;
            Set(VodState.Downloading, null);
            for (int w = 0; w < (LimitBytes > 0 ? 1 : Connections); w++)
            {
                var t = new Thread(Worker) { IsBackground = true, Name = "Nova HLS part" };
                lock (_lock) _threads.Add(t);
                t.Start();
            }
            while (!_stopped && !IsComplete) Thread.Sleep(300);
            if (!_stopped && IsComplete && !_completeReported)
            {
                _completeReported = true;
                Set(VodState.Complete, null);
                var c = Completed; if (c != null) try { c(this); } catch { }
            }
        }

        /// <summary>Reads a playlist; a master playlist leads to its best quality.</summary>
        private List<Part> ReadPlaylist(string url, int depth)
        {
            if (depth > 3) throw new NovaException("This HLS link points to itself.");
            var text = HttpPolicy.ReadText(url, Headers);
            if (!text.TrimStart().StartsWith("#EXTM3U")) throw new NovaException("This is not an HLS playlist.");
            var lines = text.Replace("\r", "").Split('\n').Select(l => l.Trim()).ToList();
            var baseUri = new Uri(url);
            // Master playlist: choose the highest bandwidth.
            if (lines.Any(l => l.StartsWith("#EXT-X-STREAM-INF")))
            {
                long best = -1; string bestUrl = null;
                for (int i = 0; i < lines.Count; i++)
                {
                    if (!lines[i].StartsWith("#EXT-X-STREAM-INF")) continue;
                    var bw = Attr(lines[i], "BANDWIDTH");
                    long b; long.TryParse(bw, out b);
                    var next = lines.Skip(i + 1).FirstOrDefault(l => l.Length > 0 && !l.StartsWith("#"));
                    if (next != null && b > best) { best = b; bestUrl = new Uri(baseUri, next).AbsoluteUri; }
                }
                if (bestUrl == null) throw new NovaException("No video found in this HLS playlist.");
                return ReadPlaylist(bestUrl, depth + 1);
            }
            if (!lines.Any(l => l.StartsWith("#EXT-X-ENDLIST")))
                throw new NovaException("This is a live HLS stream (it never ends), so it can't be downloaded. Play it instead.");
            var parts = new List<Part>();
            long seq = 0;
            string keyUrl = null; byte[] keyIv = null;
            double dur = 0;
            foreach (var l in lines)
            {
                if (l.StartsWith("#EXT-X-MEDIA-SEQUENCE:")) long.TryParse(l.Substring(22), out seq);
                else if (l.StartsWith("#EXTINF:"))
                {
                    var d = l.Substring(8).Split(',')[0].Trim();
                    double.TryParse(d, NumberStyles.Float, CultureInfo.InvariantCulture, out dur);
                }
                else if (l.StartsWith("#EXT-X-KEY"))
                {
                    var method = Attr(l, "METHOD");
                    if (method == "NONE") { keyUrl = null; keyIv = null; }
                    else if (method == "AES-128")
                    {
                        keyUrl = new Uri(baseUri, Attr(l, "URI")).AbsoluteUri;
                        var iv = Attr(l, "IV");
                        keyIv = string.IsNullOrEmpty(iv) ? null : HexBytes(iv);
                    }
                    else throw new NovaException("This video is protected (" + method + ") and can't be downloaded.");
                }
                else if (l.StartsWith("#EXT-X-MAP")) throw new NovaException("This HLS format (fMP4 parts) can't be downloaded yet. Play it instead.");
                else if (l.Length > 0 && !l.StartsWith("#"))
                {
                    parts.Add(new Part { Url = new Uri(baseUri, l).AbsoluteUri, KeyUrl = keyUrl, Iv = keyIv, Seq = seq, Duration = dur > 0 ? dur : 6 });
                    dur = 0;
                    seq++;
                }
            }
            if (parts.Count == 0) throw new NovaException("This HLS playlist has no parts.");
            return parts;
        }

        private static string Attr(string line, string name)
        {
            var i = line.IndexOf(name + "=", StringComparison.Ordinal);
            if (i < 0) return null;
            var v = line.Substring(i + name.Length + 1);
            if (v.StartsWith("\"")) { var end = v.IndexOf('"', 1); return end > 0 ? v.Substring(1, end - 1) : v.Trim('"'); }
            var comma = v.IndexOf(',');
            return comma >= 0 ? v.Substring(0, comma) : v;
        }

        private static byte[] HexBytes(string hex)
        {
            var h = hex.StartsWith("0x", StringComparison.OrdinalIgnoreCase) ? hex.Substring(2) : hex;
            h = h.PadLeft(32, '0');
            var b = new byte[16];
            for (int i = 0; i < 16; i++) b[i] = byte.Parse(h.Substring(i * 2, 2), NumberStyles.HexNumber, CultureInfo.InvariantCulture);
            return b;
        }

        private byte[] KeyFor(string url)
        {
            lock (_lock) { byte[] k; if (_keys.TryGetValue(url, out k)) return k; }
            using (var r = HttpPolicy.Open(url, -1, 15000, 20000, Headers))
            {
                if (r.Status >= 400) throw new NovaException("The video's key was refused (HTTP " + r.Status + ").");
                var ms = new MemoryStream();
                r.Body.CopyTo(ms);
                var key = ms.ToArray();
                if (key.Length != 16) throw new NovaException("The video's key is not valid.");
                lock (_lock) _keys[url] = key;
                return key;
            }
        }

        private void Worker()
        {
            while (!_stopped)
            {
                int index = -1;
                Part part = null;
                lock (_lock)
                {
                    if (LimitBytes > 0)
                    {
                        index = NextAheadLocked();
                        if (index < 0) { Monitor.Wait(_lock, 500); continue; }
                        part = _parts[index];
                        part.Size = -2;
                    }
                    else
                    {
                        while (_next < _parts.Count && _parts[_next].Size >= 0) _next++;
                        for (int i = _next; i < _parts.Count; i++)
                            if (_parts[i].Size == -1) { index = i; part = _parts[i]; part.Size = -2; break; } // -2 = being downloaded
                    }
                }
                if (part == null) return;
                var backoff = 1000;
                while (!_stopped)
                {
                    try
                    {
                        DownloadPart(index, part);
                        break;
                    }
                    catch (NovaException e)
                    {
                        Set(VodState.Failed, e.Message);
                        return;
                    }
                    catch (Exception e)
                    {
                        if (_stopped) return;
                        Set(VodState.Reconnecting, LiveRelay.Describe(e, true));
                        var until = DateTime.UtcNow.AddMilliseconds(backoff);
                        while (!_stopped && DateTime.UtcNow < until) Thread.Sleep(100);
                        backoff = Math.Min(30000, backoff * 2);
                    }
                }
                if (_stopped) lock (_lock) if (part.Size == -2) part.Size = -1;
            }
        }

        private void DownloadPart(int index, Part part)
        {
            var path = PartPath(index);
            byte[] data;
            using (var r = HttpPolicy.Open(part.Url, -1, 15000, 30000, Headers))
            {
                lock (_lock) _open.Add(r);
                try
                {
                    if (r.Status >= 400) throw new IOException("HTTP " + r.Status);
                    var ms = new MemoryStream();
                    var buf = new byte[64 * 1024];
                    int n;
                    while ((n = r.Body.Read(buf, 0, buf.Length)) > 0)
                    {
                        ms.Write(buf, 0, n);
                        var t = Throttle;
                        if (t != null && Readers == 0) t.Take(n);
                        if (_stopped) return;
                    }
                    data = ms.ToArray();
                }
                finally { lock (_lock) _open.Remove(r); }
            }
            if (part.KeyUrl != null)
            {
                var iv = part.Iv;
                if (iv == null)
                {
                    iv = new byte[16];
                    var s = BitConverter.GetBytes(part.Seq);
                    if (BitConverter.IsLittleEndian) Array.Reverse(s);
                    Buffer.BlockCopy(s, 0, iv, 8, 8);
                }
                using (var aes = Aes.Create())
                {
                    aes.Key = KeyFor(part.KeyUrl);
                    aes.IV = iv;
                    aes.Mode = CipherMode.CBC;
                    aes.Padding = PaddingMode.PKCS7;
                    using (var d = aes.CreateDecryptor()) data = d.TransformFinalBlock(data, 0, data.Length);
                }
            }
            File.WriteAllBytes(path, data);
            File.WriteAllText(path + ".ok", "ok");
            lock (_lock) { part.Size = data.Length; Monitor.PulseAll(_lock); }
            var h = Changed; if (h != null) try { h(this); } catch { }
        }

        // ------------------------------------------------------------------ read-ahead (watching a HLS film)

        /// <summary>The next part to fetch in read-ahead mode, or -1 (enough ahead, or nothing missing).</summary>
        private int NextAheadLocked()
        {
            var from = _want >= 0 ? _want : _readIndex;
            var known = _parts.Where(p => p.Size > 0).ToList();
            var avg = known.Count > 0 ? known.Average(p => p.Size) : 1024 * 1024;
            double ahead = 0;
            for (int i = Math.Max(0, _readIndex); i < _parts.Count; i++)
            {
                if (ahead > LimitBytes * 0.8) return -1;
                if (i >= from && _parts[i].Size == -1) return i;
                ahead += _parts[i].Size > 0 ? _parts[i].Size : avg;
            }
            return -1;
        }

        /// <summary>Waits until the list of parts is known (or failed). False when it can't be used.</summary>
        public bool WaitParts(int maxMs, Func<bool> cancelled)
        {
            var until = DateTime.UtcNow.AddMilliseconds(maxMs);
            while (!_stopped && !cancelled() && DateTime.UtcNow < until)
            {
                lock (_lock) if (_parts != null) return true;
                if (State == VodState.Failed) return false;
                Thread.Sleep(100);
            }
            lock (_lock) return _parts != null;
        }

        /// <summary>A local playlist of the parts (each one served by Nova from the disk cache).</summary>
        public string Playlist(string partBase)
        {
            List<Part> parts;
            lock (_lock) parts = _parts == null ? new List<Part>() : _parts.ToList();
            var sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
            var target = parts.Count == 0 ? 10 : (int)Math.Ceiling(parts.Max(p => p.Duration));
            sb.Append("#EXT-X-TARGETDURATION:" + Math.Max(1, target) + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
            for (int i = 0; i < parts.Count; i++)
            {
                sb.Append("#EXTINF:" + parts[i].Duration.ToString("0.###", CultureInfo.InvariantCulture) + ",\n");
                sb.Append(partBase + i.ToString(CultureInfo.InvariantCulture) + ".ts\n");
            }
            sb.Append("#EXT-X-ENDLIST\n");
            return sb.ToString();
        }

        /// <summary>The player wants part i: it is fetched first, and the file path returned once it is on disk (null = gave up).</summary>
        public string WaitPart(int i, Func<bool> cancelled)
        {
            lock (_lock)
            {
                if (_parts == null || i < 0 || i >= _parts.Count) return null;
                _readIndex = i;
                if (_parts[i].Size < 0) _want = i;
                TrimBehindLocked();
                Monitor.PulseAll(_lock);
            }
            while (!_stopped && !cancelled())
            {
                lock (_lock)
                {
                    if (_parts[i].Size >= 0) { if (_want == i) _want = -1; Monitor.PulseAll(_lock); return PartPath(i); }
                    if (State == VodState.Failed) return null;
                    Monitor.Wait(_lock, 300);
                }
            }
            return null;
        }

        /// <summary>Parts far behind the picture are deleted (~20 % of the cache stays behind for small jumps back).</summary>
        private void TrimBehindLocked()
        {
            double behind = 0;
            for (int j = _readIndex - 1; j >= 0; j--)
            {
                var size = _parts[j].Size;
                if (size < 0) continue;
                behind += size;
                if (behind > LimitBytes * 0.2)
                {
                    try { File.Delete(PartPath(j)); File.Delete(PartPath(j) + ".ok"); } catch { }
                    _parts[j].Size = -1;
                }
            }
        }

        /// <summary>Seconds of video ready on disk ahead of the picture.</summary>
        public double BufferedAheadSeconds
        {
            get
            {
                lock (_lock)
                {
                    if (_parts == null) return 0;
                    double s = 0;
                    for (int i = Math.Max(0, _readIndex); i < _parts.Count && _parts[i].Size >= 0; i++) s += _parts[i].Duration;
                    return s;
                }
            }
        }

        /// <summary>Stops and deletes the read-ahead cache.</summary>
        public void DisposeAndDelete()
        {
            Dispose();
            try { Directory.Delete(_folder, true); } catch { }
        }

        // ------------------------------------------------------------------ watching while downloading

        public int Read(long pos, byte[] buf, int offset, int count, Func<bool> cancelled)
        {
            while (!_stopped && !cancelled())
            {
                int index = -1; long within = 0; long size = 0; bool waiting = false;
                lock (_lock)
                {
                    if (_parts == null) { waiting = true; }
                    else
                    {
                        long start = 0;
                        for (int i = 0; i < _parts.Count; i++)
                        {
                            var s = _parts[i].Size;
                            if (s < 0) { waiting = true; break; }
                            if (pos < start + s) { index = i; within = pos - start; size = s; break; }
                            start += s;
                        }
                        if (index < 0 && !waiting) return 0; // the end
                    }
                    if (waiting) { if (State == VodState.Failed) return 0; Monitor.Wait(_lock, 300); continue; }
                }
                using (var fs = new FileStream(PartPath(index), FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                {
                    fs.Position = within;
                    var n = fs.Read(buf, offset, (int)Math.Min(count, size - within));
                    if (n > 0) return n;
                }
            }
            return 0;
        }

        /// <summary>Joins the parts into one .ts file and removes them.</summary>
        public void Assemble(string finalPath)
        {
            if (!IsComplete) throw new InvalidOperationException("The download is not complete.");
            Dispose();
            Directory.CreateDirectory(Path.GetDirectoryName(finalPath));
            var tmp = finalPath + ".joining";
            using (var output = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None, 1024 * 1024))
            {
                for (int i = 0; i < _parts.Count; i++)
                    using (var input = File.OpenRead(PartPath(i))) input.CopyTo(output);
            }
            if (File.Exists(finalPath)) File.Delete(finalPath);
            File.Move(tmp, finalPath);
            try { Directory.Delete(_folder, true); } catch { }
        }

        public bool IsStopped { get { return _stopped; } }

        public void Dispose()
        {
            if (_stopped) return;
            _stopped = true;
            List<RawResponse> open;
            lock (_lock) { open = _open.ToList(); Monitor.PulseAll(_lock); }
            foreach (var r in open) try { r.Dispose(); } catch { }
            List<Thread> threads;
            lock (_lock) threads = _threads.ToList();
            foreach (var t in threads) if (t != Thread.CurrentThread) t.Join(3000);
        }
    }
}
