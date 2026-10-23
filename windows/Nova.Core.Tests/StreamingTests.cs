using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using Nova.Core.Streaming;

namespace Nova.Core.Tests
{
    /// <summary>
    /// A pretend IPTV provider on this PC: live channels that work, drop, freeze, refuse,
    /// redirect or use chunked encoding, and a film that supports (or ignores) byte ranges.
    /// </summary>
    internal sealed class FakeProvider : IDisposable
    {
        public const long FilmLength = 10 * 1024 * 1024 + 512 * 1024; // 10.5 MB = 3 pieces of 4 MB
        private readonly TcpListener _listener;
        private volatile bool _stopped;
        public int Port { get; private set; }
        public int Connections;

        public FakeProvider()
        {
            _listener = new TcpListener(IPAddress.Loopback, 0);
            _listener.Start();
            Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
            new Thread(() =>
            {
                while (!_stopped)
                {
                    try
                    {
                        var c = _listener.AcceptTcpClient();
                        Interlocked.Increment(ref Connections);
                        new Thread(() => Handle(c)) { IsBackground = true }.Start();
                    }
                    catch { }
                }
            }) { IsBackground = true }.Start();
        }

        public string Url(string path) { return "http://127.0.0.1:" + Port + path; }

        public static byte FilmByte(long i) { return (byte)((i * 7 + (i >> 11)) & 0xFF); }

        private static byte[] TsPackets(int count, ref int counter)
        {
            var b = new byte[188 * count];
            for (int p = 0; p < count; p++)
            {
                b[p * 188] = 0x47;
                for (int i = 1; i < 188; i++) b[p * 188 + i] = (byte)(counter + i);
                counter++;
            }
            return b;
        }

        private void Handle(TcpClient c)
        {
            Interlocked.Increment(ref Active);
            try { HandleInner(c); } finally { Interlocked.Decrement(ref Active); }
        }

        private void HandleInner(TcpClient c)
        {
            using (c)
            {
                try
                {
                    var s = c.GetStream();
                    var lines = new List<string>();
                    var sb = new StringBuilder();
                    int b;
                    while ((b = s.ReadByte()) >= 0)
                    {
                        if (b == '\n') { var l = sb.ToString().TrimEnd('\r'); sb.Clear(); if (l.Length == 0) break; lines.Add(l); }
                        else sb.Append((char)b);
                    }
                    if (lines.Count == 0) return;
                    var path = lines[0].Split(' ')[1];
                    var ua = lines.FirstOrDefault(l => l.StartsWith("User-Agent:")) ?? "";
                    long from = -1;
                    foreach (var l in lines)
                        if (l.StartsWith("Range: bytes=")) from = long.Parse(l.Substring(13).TrimEnd('-'), CultureInfo.InvariantCulture);
                    if (path.StartsWith("/hls/") || path.StartsWith("/site/")) { if (HandleExtra(s, path, ua, from)) return; }
                    var counter = 0;
                    switch (path)
                    {
                        case "/live/ok":
                            Head(s, "200 OK", null);
                            while (!_stopped) { s.Write(TsPackets(70, ref counter), 0, 188 * 70); Thread.Sleep(20); }
                            break;
                        case "/live/drop":
                            Head(s, "200 OK", null);
                            for (int i = 0; i < 30; i++) { s.Write(TsPackets(70, ref counter), 0, 188 * 70); Thread.Sleep(10); }
                            break; // closes: the stream "drops"
                        case "/live/stall":
                            Head(s, "200 OK", null);
                            for (int i = 0; i < 15; i++) s.Write(TsPackets(70, ref counter), 0, 188 * 70);
                            Thread.Sleep(60000); // frozen: connection open, nothing sent
                            break;
                        case "/live/dead":
                            Head(s, "404 Not Found", "0");
                            break;
                        case "/live/redirect":
                            Send(s, "HTTP/1.1 302 Found\r\nLocation: /live/ok\r\nContent-Length: 0\r\n\r\n");
                            break;
                        case "/live/chunked":
                            Send(s, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
                            while (!_stopped)
                            {
                                var data = TsPackets(40, ref counter);
                                Send(s, data.Length.ToString("x") + "\r\n");
                                s.Write(data, 0, data.Length);
                                Send(s, "\r\n");
                                Thread.Sleep(20);
                            }
                            break;
                        case "/vod/film.mkv":
                        case "/vod/norange.mp4":
                            var filmNow = Interlocked.Increment(ref ActiveFilm);
                            lock (this) if (filmNow > MaxActive) MaxActive = filmNow;
                            try
                            {
                                var ranged = path.Contains("film") && from >= 0;
                                var start = ranged ? from : 0;
                                var len = FilmLength - start;
                                if (ranged)
                                    Send(s, "HTTP/1.1 206 Partial Content\r\nContent-Length: " + len + "\r\nContent-Range: bytes " + start + "-" + (FilmLength - 1) + "/" + FilmLength + "\r\n\r\n");
                                else
                                    Send(s, "HTTP/1.1 200 OK\r\nContent-Length: " + len + "\r\n\r\n");
                                var buf = new byte[64 * 1024];
                                var pos = start;
                                while (pos < FilmLength && !_stopped)
                                {
                                    var n = (int)Math.Min(buf.Length, FilmLength - pos);
                                    for (int i = 0; i < n; i++) buf[i] = FilmByte(pos + i);
                                    s.Write(buf, 0, n);
                                    pos += n;
                                    Thread.Sleep(15);
                                }
                            }
                            finally { Interlocked.Decrement(ref ActiveFilm); }
                            break;
                        default:
                            Head(s, "404 Not Found", "0");
                            break;
                    }
                }
                catch { }
            }
        }

        public int Active, ActiveFilm, MaxActive;
        public static readonly byte[] HlsKey = Enumerable.Range(1, 16).Select(i => (byte)i).ToArray();

        public static byte[] HlsPart(int i)
        {
            var b = new byte[188 * 50];
            for (int p = 0; p < 50; p++) { b[p * 188] = 0x47; for (int k = 1; k < 188; k++) b[p * 188 + k] = (byte)(i * 31 + k); }
            return b;
        }

        private bool HandleExtra(Stream s, string path, string ua, long from)
        {
            if (path == "/hls/master.m3u8")
            {
                Body(s, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nmedia.m3u8\n");
                return true;
            }
            if (path == "/hls/media.m3u8")
            {
                var sb = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n");
                for (int i = 0; i < 6; i++) sb.Append("#EXTINF:4.0,\nseg" + i + ".ts\n");
                sb.Append("#EXT-X-ENDLIST\n");
                Body(s, sb.ToString());
                return true;
            }
            if (path == "/hls/live.m3u8") { Body(s, "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg0.ts\n"); return true; }
            if (path == "/hls/key.bin") { Bytes(s, HlsKey); return true; }
            if (path.StartsWith("/hls/seg"))
            {
                var i = int.Parse(path.Substring(8, path.IndexOf('.') - 8));
                var iv = new byte[16];
                iv[15] = (byte)i; // sequence number as IV
                using (var aes = System.Security.Cryptography.Aes.Create())
                {
                    aes.Key = HlsKey; aes.IV = iv;
                    using (var enc = aes.CreateEncryptor()) { var plain = HlsPart(i); Bytes(s, enc.TransformFinalBlock(plain, 0, plain.Length)); }
                }
                return true;
            }
            if (path == "/site/film.mp4")
            {
                // A website that only answers web browsers.
                if (!ua.Contains("Mozilla")) { Head(s, "403 Forbidden", "0"); return true; }
                var start = Math.Max(0, from);
                var len = 300000 - start;
                Send(s, "HTTP/1.1 206 Partial Content\r\nContent-Length: " + len + "\r\nContent-Range: bytes " + start + "-299999/300000\r\n\r\n");
                var data = new byte[len];
                for (int i = 0; i < len; i++) data[i] = FilmByte(start + i);
                s.Write(data, 0, data.Length);
                return true;
            }
            return false;
        }

        private static void Body(Stream s, string text)
        {
            Bytes(s, Encoding.UTF8.GetBytes(text));
        }

        private static void Bytes(Stream s, byte[] b)
        {
            Send(s, "HTTP/1.1 200 OK\r\nContent-Length: " + b.Length + "\r\nConnection: close\r\n\r\n");
            s.Write(b, 0, b.Length);
        }

        private static void Head(Stream s, string status, string length)
        {
            Send(s, "HTTP/1.1 " + status + "\r\n" + (length != null ? "Content-Length: " + length + "\r\n" : "") + "Connection: close\r\n\r\n");
        }

        private static void Send(Stream s, string text)
        {
            var b = Encoding.ASCII.GetBytes(text);
            s.Write(b, 0, b.Length);
        }

        public void Dispose()
        {
            _stopped = true;
            try { _listener.Stop(); } catch { }
        }
    }

    internal static partial class Program
    {
        private static bool WaitFor(Func<bool> condition, int ms)
        {
            var until = DateTime.UtcNow.AddMilliseconds(ms);
            while (DateTime.UtcNow < until) { if (condition()) return true; Thread.Sleep(50); }
            return condition();
        }

        private static void RunStreamingTests()
        {
            var root = Path.Combine(Path.GetTempPath(), "nova-stream-" + Guid.NewGuid().ToString("N"));
            var settings = new Settings { CushionSeconds = 1, StallSeconds = 3, DeadSeconds = 5, LiveBufferMb = 100, FilmBufferMb = 512 };
            using (var provider = new FakeProvider())
            using (var hub = new StreamHub(() => settings, () => root))
            {
                Run("Raw HTTP: redirect", () =>
                {
                    using (var r = RawHttp.Open(provider.Url("/live/redirect"), "UA", -1, 5000, 5000))
                    {
                        Eq(200, r.Status, "redirect followed");
                        Eq(0x47, r.Body.ReadByte(), "stream data after redirect");
                    }
                });

                Run("Live: buffer on disk and play from the local server", () =>
                {
                    using (var lease = hub.OpenLive("ok", provider.Url("/live/ok")))
                    {
                        Check(WaitFor(() => lease.Relay.End > 400 * 1024, 10000), "data collected on disk");
                        Eq(RelayState.Playing, lease.Relay.State, "relay playing");
                        Check(Directory.GetFiles(hub.LiveFolder, "*.ts", SearchOption.AllDirectories).Length > 0, "buffer files in the buffer folder");
                        var got = ReadLocal(lease.Url, 200 * 1024, null);
                        Check(got.Length == 200 * 1024, "player read 200 KB from the local server (" + got.Length + ")");
                        Check(got.Length > 376 && got[0] == 0x47 && got[188] == 0x47, "starts on an MPEG-TS packet");
                        var rewind = ReadLocal(lease.UrlAt(lease.Relay.Start), 188 * 10, null);
                        Check(rewind.Length > 188 && rewind[0] == 0x47, "rewind address works");

                        // A second viewer shares the same download (one connection).
                        var before = provider.Connections;
                        using (var second = hub.OpenLive("ok", provider.Url("/live/ok")))
                        {
                            Check(ReferenceEquals(second.Relay, lease.Relay), "same channel = same relay");
                            Thread.Sleep(500);
                            Eq(before, provider.Connections, "no extra provider connection");
                        }
                        Check(!lease.Relay.IsStopped, "relay kept while still used");
                    }
                    Check(WaitFor(() => hub.FindLive("ok") == null, 5000), "relay stops when nobody uses it");
                });

                Run("Live: watchdog after a drop", () =>
                {
                    var reconnects = 0;
                    using (var lease = hub.OpenLive("drop", provider.Url("/live/drop")))
                    {
                        lease.Relay.Changed += r => { if (r.State == RelayState.Reconnecting) Interlocked.Increment(ref reconnects); };
                        Check(WaitFor(() => lease.Relay.End > 1200 * 1024, 15000), "keeps collecting after drops (" + lease.Relay.End + " bytes)");
                        Check(reconnects > 0, "reconnected");
                    }
                });

                Run("Live: watchdog after a freeze", () =>
                {
                    using (var lease = hub.OpenLive("stall", provider.Url("/live/stall")))
                    {
                        Check(WaitFor(() => lease.Relay.End > 0, 5000), "first data");
                        var first = lease.Relay.End;
                        Check(WaitFor(() => lease.Relay.End > first, 12000), "reconnected after the freeze (" + lease.Relay.End + ")");
                        Check(lease.Relay.Message == null || lease.Relay.State == RelayState.Playing || lease.Relay.Message.Contains("froze"), "freeze explained: " + lease.Relay.Message);
                    }
                });

                Run("Live: dead channel reported", () =>
                {
                    var dead = 0;
                    using (var lease = hub.OpenLive("dead", provider.Url("/live/dead")))
                    {
                        lease.Relay.Dead += r => Interlocked.Increment(ref dead);
                        Check(WaitFor(() => dead > 0, 12000), "dead event");
                        Check(!lease.Relay.EverReceived, "never received");
                        Check(lease.Relay.Message != null && lease.Relay.Message.Contains("404"), "refusal explained: " + lease.Relay.Message);
                    }
                });

                Run("Live: chunked answers", () =>
                {
                    using (var lease = hub.OpenLive("chunked", provider.Url("/live/chunked")))
                    {
                        Check(WaitFor(() => lease.Relay.End > 100 * 1024, 8000), "chunked data collected");
                        var buf = new byte[376];
                        lease.Relay.ReadAt(0, buf, 0, buf.Length);
                        Check(buf[0] == 0x47 && buf[188] == 0x47, "chunk headers removed");
                    }
                });

                Run("Live: recording uses the same stream", () =>
                {
                    var file = Path.Combine(root, "rec", "test.ts");
                    Thread.Sleep(800); // let earlier channels finish closing
                    var before = provider.Connections;
                    using (var lease = hub.OpenLive("rec", provider.Url("/live/ok")))
                    {
                        Check(WaitFor(() => lease.Relay.End > 0, 5000), "playing");
                        lease.Relay.StartRecording(file);
                        Check(lease.Relay.IsRecording, "recording");
                        Thread.Sleep(1500);
                        lease.Relay.StopRecording();
                        var len = new FileInfo(file).Length;
                        Check(len > 50 * 1024, "recording has video (" + len + ")");
                        Eq(len, lease.Relay.RecordedBytes, "recorded size");
                    }
                    Eq(before + 1, provider.Connections, "one connection for watching + recording");
                });

                Run("Films: index at the end, then from the start (one connection)", () =>
                {
                    var before = provider.Connections;
                    using (var lease = hub.OpenVod(provider.Url("/vod/film.mkv"), "mkv", null, false))
                    {
                        Check(lease.File.WaitReady(8000), "size known");
                        Eq(FakeProvider.FilmLength, lease.File.Length, "size");
                        var tail = ReadLocal(lease.Url, -1, "bytes=" + (FakeProvider.FilmLength - 100000) + "-");
                        Eq(100000, tail.Length, "end of the file read first");
                        Check(Same(tail, FakeProvider.FilmLength - 100000), "end bytes correct");
                        var all = ReadLocal(lease.Url, -1, "bytes=0-");
                        Eq((int)FakeProvider.FilmLength, all.Length, "whole film read");
                        Check(Same(all, 0), "whole film correct");
                    }
                    Check(provider.Connections - before <= 4, "few provider connections (" + (provider.Connections - before) + ")");
                    Check(Directory.Exists(hub.FilmsFolder), "cache folder used");
                });

                Run("Films: server without byte ranges", () =>
                {
                    using (var lease = hub.OpenVod(provider.Url("/vod/norange.mp4"), "mp4", null, false))
                    {
                        Check(!lease.File.WaitReady(8000), "not cached (falls back to direct play)");
                        Eq(VodState.Failed, lease.File.State, "reported");
                    }
                });

                Run("Download: resume after a restart, then join the pieces", () =>
                {
                    var folder = Path.Combine(root, "dl", "parts");
                    var url = provider.Url("/vod/film.mkv");
                    var first = new VodFile("dl", url, () => "UA", folder, true, long.MaxValue / 4, 5);
                    first.Begin();
                    Check(WaitFor(() => first.Downloaded > VodFile.ChunkSize, 15000), "part downloaded");
                    first.Dispose();
                    var kept = first.Downloaded;
                    var second = new VodFile("dl", url, () => "UA", folder, true, long.MaxValue / 4, 5);
                    Check(second.Downloaded >= VodFile.ChunkSize && second.Downloaded <= kept, "progress kept after restart (" + second.Downloaded + ")");
                    second.Begin();
                    Check(WaitFor(() => second.IsComplete, 20000), "download complete");
                    var final = Path.Combine(root, "dl", "Film.mkv");
                    second.Assemble(final);
                    var bytes = File.ReadAllBytes(final);
                    Eq(FakeProvider.FilmLength, (long)bytes.Length, "final file size");
                    Check(Same(bytes, 0), "final file correct");
                    Check(!Directory.Exists(folder), "pieces removed");
                });

                Run("Downloads: film saved to the downloads folder", () =>
                {
                    settings.DownloadFolder = Path.Combine(root, "MyDownloads");
                    var max = 0;
                    using (var dm = new DownloadManager(hub, () => settings, a => max, Path.Combine(root, "data")))
                    {
                        var film = new Movie { AccountId = "acc", StreamId = "7", Name = "La chaleur: (2026)", Extension = "mkv" };
                        var item = dm.AddFilm(film, provider.Url("/vod/film.mkv"));
                        Check(item.FinalPath.StartsWith(Path.Combine(settings.DownloadFolder, "Films")), "goes to Downloads\\NovaIPTV\\Films: " + item.FinalPath);
                        Check(Path.GetFileName(item.FinalPath) == "La chaleur_ (2026).mkv", "safe file name: " + Path.GetFileName(item.FinalPath));
                        Check(WaitFor(() => dm.Items.First().IsDone, 25000), "download finished (" + dm.Items.First().Status + " " + dm.Items.First().Error + ")");
                        var bytes = File.ReadAllBytes(item.FinalPath);
                        Check(bytes.Length == FakeProvider.FilmLength && Same(bytes, 0), "downloaded file correct");
                        Eq(item.FinalPath, dm.DoneFile(provider.Url("/vod/film.mkv")), "finished file found for offline play");
                        Eq(item.Id, dm.AddFilm(film, provider.Url("/vod/film.mkv")).Id, "same film not downloaded twice");
                        dm.Remove(item.Id, true);
                        Check(!File.Exists(item.FinalPath), "removed with its file");
                    }
                    Check(File.Exists(Path.Combine(root, "data", "downloads.json")), "list saved");

                    max = 1;
                    using (var dm = new DownloadManager(hub, () => settings, a => max, Path.Combine(root, "data2")))
                    using (var watching = hub.OpenLive("acc|99", provider.Url("/live/ok")))
                    {
                        var film = new Movie { AccountId = "acc", StreamId = "8", Name = "Second", Extension = "mkv" };
                        var item = dm.AddFilm(film, provider.Url("/vod/film.mkv"));
                        Thread.Sleep(3000);
                        var st = dm.Items.First().Status;
                        Check(st == DownloadStatus.Queued || st == DownloadStatus.Waiting, "waits while the 1-connection account is watching (" + st + ")");
                        watching.Dispose();
                        Check(WaitFor(() => dm.Items.First().IsDone, 30000), "continues when the connection is free (" + dm.Items.First().Status + ")");
                    }
                });

                Run("Recordings: saved in Recordings\\<channel>", () =>
                {
                    using (var dm = new DownloadManager(hub, () => settings, a => 0, Path.Combine(root, "data3")))
                    using (var rm = new RecordingManager(hub, dm, () => settings))
                    {
                        var ch = new LiveChannel { AccountId = "acc", StreamId = "5", Name = "News HD", CategoryId = "" };
                        var path = rm.Start(ch, provider.Url("/live/ok"));
                        Check(path.StartsWith(Path.Combine(settings.DownloadFolder, "Recordings", "News HD")), "recording folder: " + path);
                        Check(rm.IsRecording(ch.Key), "recording");
                        Eq(DownloadStatus.Recording, dm.Items.First().Status, "listed as recording");
                        Thread.Sleep(2500);
                        rm.Stop(ch.Key);
                        Check(new FileInfo(path).Length > 50 * 1024, "recording has video");
                        Eq(DownloadStatus.Done, dm.Items.First().Status, "listed as done");
                        Check(!rm.IsRecording(ch.Key), "stopped");
                    }
                });

                Run("Downloads: several connections, in order, correct file", () =>
                {
                    settings.DlConnLinks = 4;
                    provider.MaxActive = 0;
                    using (var dm = new DownloadManager(hub, () => settings, a => 0, Path.Combine(root, "data4")))
                    {
                        var item = dm.AddLink(provider.Url("/vod/film.mkv"), "Multi", "mkv", false, null);
                        Check(WaitFor(() => dm.Items.First().IsDone, 30000), "multi-connection download finished (" + dm.Items.First().Status + " " + dm.Items.First().Error + ")");
                        var bytes = File.ReadAllBytes(dm.Items.First().FinalPath);
                        Check(bytes.Length == FakeProvider.FilmLength && Same(bytes, 0), "multi-connection file correct");
                        Check(provider.MaxActive >= 2, "used several connections at once (" + provider.MaxActive + ")");
                        Check(dm.Items.First().FinalPath.Contains(Path.Combine("MyDownloads", "Links")), "links saved in Downloads\\NovaIPTV\\Links");
                    }
                });

                Run("Downloads: server connections never above the account's limit", () =>
                {
                    settings.DlConnServers = 8;
                    var max = 3;
                    using (var dm = new DownloadManager(hub, () => settings, a => max, Path.Combine(root, "data5")))
                    {
                        Eq(2, dm.ConnectionsFor(new DownloadItem { AccountId = "acc", Kind = "film" }), "3 allowed: 2 used, one free for watching");
                        max = 1;
                        Eq(1, dm.ConnectionsFor(new DownloadItem { AccountId = "acc", Kind = "film" }), "1-connection account: 1");
                        max = 0;
                        Eq(1, dm.ConnectionsFor(new DownloadItem { AccountId = "acc", Kind = "film" }), "unknown limit: 1");
                        Eq(4, dm.ConnectionsFor(new DownloadItem { Kind = "link" }), "links: the links setting");
                    }
                });

                Run("Speed limit", () =>
                {
                    long limit = 1024 * 1024;
                    var t = new Throttle(() => limit);
                    var sw = System.Diagnostics.Stopwatch.StartNew();
                    for (int i = 0; i < 40; i++) t.Take(64 * 1024); // 2.5 MB at 1 MB/s
                    Check(sw.Elapsed.TotalSeconds >= 1.5, "limited to about 1 MB/s (" + sw.Elapsed.TotalSeconds.ToString("0.0") + " s)");
                    limit = 0;
                    sw.Restart();
                    for (int i = 0; i < 40; i++) t.Take(64 * 1024);
                    Check(sw.Elapsed.TotalSeconds < 0.5, "no limit = no wait");
                });

                Run("HLS: best quality, AES-128 parts, joined into one .ts", () =>
                {
                    using (var dm = new DownloadManager(hub, () => settings, a => 0, Path.Combine(root, "data6")))
                    {
                        var item = dm.AddLink(provider.Url("/hls/master.m3u8"), "HLS film", "m3u8", true, null);
                        Check(item.FinalPath.EndsWith(".ts"), "saved as .ts");
                        Check(WaitFor(() => dm.Items.First().IsDone, 30000), "HLS download finished (" + dm.Items.First().Status + " " + dm.Items.First().Error + ")");
                        var bytes = File.ReadAllBytes(dm.Items.First().FinalPath);
                        var expected = Enumerable.Range(0, 6).SelectMany(FakeProvider.HlsPart).ToArray();
                        Check(bytes.SequenceEqual(expected), "parts decrypted and joined in order (" + bytes.Length + " bytes)");
                    }
                    using (var dm = new DownloadManager(hub, () => settings, a => 0, Path.Combine(root, "data7")))
                    {
                        dm.AddLink(provider.Url("/hls/live.m3u8"), "Live HLS", "m3u8", true, null);
                        Check(WaitFor(() => dm.Items.First().Status == DownloadStatus.Failed, 15000), "live HLS refused");
                        Check((dm.Items.First().Error ?? "").Contains("live"), "explained: " + dm.Items.First().Error);
                    }
                });

                Run("Downloads: one per server, server and links together, waiting reason", () =>
                {
                    settings.DlSpeedServers = 1; // slow on purpose: the server download takes ~10 s
                    settings.DlConnServers = 1;
                    using (var dm = new DownloadManager(hub, () => settings, a => 0, Path.Combine(root, "data8")))
                    {
                        var f1 = dm.AddFilm(new Movie { AccountId = "acc", StreamId = "11", Name = "First", Extension = "mkv" }, provider.Url("/vod/film.mkv?a=1"));
                        var f2 = dm.AddFilm(new Movie { AccountId = "acc", StreamId = "12", Name = "Second", Extension = "mkv" }, provider.Url("/vod/film.mkv?a=2"));
                        var link = dm.AddLink(provider.Url("/vod/film.mkv?link=1"), "A link", "mkv", false, null);
                        Check(WaitFor(() => dm.Items.Any(i => i.Id == f1.Id && i.Status == DownloadStatus.Downloading), 10000), "first server download runs");
                        Thread.Sleep(2500);
                        var second = dm.Items.First(i => i.Id == f2.Id);
                        Check(second.Status == DownloadStatus.Queued, "second film of the same server waits (" + second.Status + ")");
                        Check((second.WaitingFor ?? "").Contains("First"), "says what it waits for: " + second.WaitingFor);
                        Check(WaitFor(() => dm.Items.First(i => i.Id == link.Id).IsDone, 20000), "a link downloads at the same time (" + dm.Items.First(i => i.Id == link.Id).Status + ")");
                        Check(!dm.Items.First(i => i.Id == f1.Id).IsDone, "while the server film is still downloading");
                    }
                    settings.DlSpeedServers = 0;
                });

                Run("HLS read-ahead: local playlist, parts on demand, old parts deleted", () =>
                {
                    var folder = Path.Combine(root, "hlsra");
                    var hls = new HlsDownload(provider.Url("/hls/master.m3u8"), folder, 1) { LimitBytes = 188 * 50 * 2 };
                    hls.Begin();
                    try
                    {
                        Check(hls.WaitParts(10000, () => false), "parts listed");
                        var url = hub.ServeHlsReadAhead("ra1", hls);
                        var list = Encoding.UTF8.GetString(ReadLocal(url, -1, null));
                        Check(list.Contains("#EXT-X-ENDLIST") && list.Contains("/hlsra/ra1/5.ts") && list.Contains("#EXTINF:4,"), "playlist made: " + list.Replace("\n", " | "));
                        var part4 = ReadLocal(url.Replace(".m3u8", "/4.ts"), -1, null);
                        Check(part4.SequenceEqual(FakeProvider.HlsPart(4)), "a jump: part 4 fetched first and decrypted");
                        var part5 = ReadLocal(url.Replace(".m3u8", "/5.ts"), -1, null);
                        Check(part5.SequenceEqual(FakeProvider.HlsPart(5)), "next part");
                        var part0 = ReadLocal(url.Replace(".m3u8", "/0.ts"), -1, null);
                        Check(part0.SequenceEqual(FakeProvider.HlsPart(0)), "back to the start");
                        Thread.Sleep(500);
                        var files = Directory.GetFiles(folder, "p*.ts").Length;
                        Check(files <= 4, "cache kept small (" + files + " parts on disk)");
                        hub.StopServing("ra1");
                    }
                    finally { hls.DisposeAndDelete(); }
                    Check(!Directory.Exists(folder), "cache deleted when closed");
                });

                Run("Websites: retry as a browser, then remembered", () =>
                {
                    var remembered = new Dictionary<string, Dictionary<string, string>>();
                    HttpPolicy.SavedHeaders = h => remembered.ContainsKey(h) ? remembered[h] : new Dictionary<string, string>();
                    HttpPolicy.RememberHeaders = (h, d) => remembered[h] = d;
                    var info = LinkCheck.Probe(provider.Url("/site/film.mp4"), null);
                    Check(info.Ok && info.Kind == "video", "refused link works as a browser (" + info.Message + ")");
                    Check(remembered.ContainsKey("127.0.0.1") && remembered["127.0.0.1"]["User-Agent"].Contains("Mozilla"), "browser name remembered for the site");
                    var hls = LinkCheck.Probe(provider.Url("/hls/master.m3u8"), null);
                    Eq("hls", hls.Kind, "HLS recognised");
                    var gone = LinkCheck.Probe(provider.Url("/nothing"), null);
                    Check(!gone.Ok && gone.Message.Contains("expired"), "missing link explained: " + gone.Message);
                    HttpPolicy.SavedHeaders = h => new Dictionary<string, string>();
                    HttpPolicy.RememberHeaders = (h, d) => { };
                });
            }
            try { Directory.Delete(root, true); } catch { }
        }

        private static bool Same(byte[] data, long offset)
        {
            for (int i = 0; i < data.Length; i++)
                if (data[i] != FakeProvider.FilmByte(offset + i)) { Console.WriteLine("  first difference at " + (offset + i)); return false; }
            return true;
        }

        /// <summary>Reads from the local server like VLC would (max bytes, or everything when max &lt; 0).</summary>
        private static byte[] ReadLocal(string url, int max, string range)
        {
            using (var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60) })
            {
                var req = new HttpRequestMessage(HttpMethod.Get, url);
                if (range != null) req.Headers.TryAddWithoutValidation("Range", range);
                using (var resp = http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult())
                using (var s = resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult())
                {
                    var ms = new MemoryStream();
                    var buf = new byte[64 * 1024];
                    while (max < 0 || ms.Length < max)
                    {
                        var want = max < 0 ? buf.Length : (int)Math.Min(buf.Length, max - ms.Length);
                        var n = s.Read(buf, 0, want);
                        if (n <= 0) break;
                        ms.Write(buf, 0, n);
                    }
                    return ms.ToArray();
                }
            }
        }
    }
}
