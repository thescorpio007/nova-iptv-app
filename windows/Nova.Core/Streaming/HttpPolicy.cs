using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

namespace Nova.Core.Streaming
{
    /// <summary>
    /// How Nova talks to websites (same rules as the Android app):
    ///  - your IPTV servers get Nova's User-Agent only, nothing else;
    ///  - other websites get the headers remembered for them (Referer, User-Agent…), typed in Open link → Advanced;
    ///  - when a website refuses (401 / 403), the request is tried once more as a normal web browser,
    ///    and if that works it is remembered for that website.
    /// </summary>
    public static class HttpPolicy
    {
        public const string BrowserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36";

        public static Func<string> UserAgent = () => Store.DefaultUserAgent;
        public static Func<string, bool> IsAccountHost = h => false;
        public static Func<string, Dictionary<string, string>> SavedHeaders = h => new Dictionary<string, string>();
        public static Action<string, Dictionary<string, string>> RememberHeaders = (h, d) => { };

        public static string HostOf(string url)
        {
            Uri u;
            return Uri.TryCreate(url, UriKind.Absolute, out u) ? u.Host.ToLowerInvariant() : null;
        }

        /// <summary>The headers Nova sends to this address (extra = headers given with the link).</summary>
        public static Dictionary<string, string> HeadersFor(string url, IDictionary<string, string> extra)
        {
            var host = HostOf(url);
            var h = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            if (host != null && IsAccountHost(host))
            {
                h["User-Agent"] = UserAgent();
                return h;
            }
            if (host != null) foreach (var kv in SavedHeaders(host)) h[kv.Key] = kv.Value;
            if (extra != null) foreach (var kv in extra) if (!string.IsNullOrWhiteSpace(kv.Value)) h[kv.Key] = kv.Value;
            if (!h.ContainsKey("User-Agent")) h["User-Agent"] = UserAgent();
            return h;
        }

        /// <summary>Opens the address with the right headers, retrying once as a web browser if a website refuses.</summary>
        public static RawResponse Open(string url, long from, int connectMs, int readMs, IDictionary<string, string> extra = null)
        {
            var headers = HeadersFor(url, extra);
            var resp = RawHttp.Open(url, headers, from, connectMs, readMs);
            var host = HostOf(url);
            if ((resp.Status == 401 || resp.Status == 403) && host != null && !IsAccountHost(host) &&
                headers["User-Agent"] != BrowserAgent)
            {
                resp.Dispose();
                var browser = new Dictionary<string, string>(headers, StringComparer.OrdinalIgnoreCase);
                browser["User-Agent"] = BrowserAgent;
                if (!browser.ContainsKey("Referer"))
                {
                    var u = new Uri(url);
                    browser["Referer"] = u.Scheme + "://" + u.Authority + "/";
                }
                var again = RawHttp.Open(url, browser, from, connectMs, readMs);
                if (again.Status < 400)
                {
                    var keep = SavedHeaders(host);
                    keep = keep == null ? new Dictionary<string, string>() : new Dictionary<string, string>(keep);
                    keep["User-Agent"] = BrowserAgent;
                    if (!keep.ContainsKey("Referer")) keep["Referer"] = browser["Referer"];
                    try { RememberHeaders(host, keep); } catch { }
                }
                return again;
            }
            return resp;
        }

        /// <summary>Reads a small text answer (playlists).</summary>
        public static string ReadText(string url, IDictionary<string, string> extra, int maxBytes = 8 * 1024 * 1024)
        {
            using (var r = Open(url, -1, 15000, 20000, extra))
            {
                if (r.Status >= 400) throw new NovaException(StatusMessage(r.Status));
                using (var ms = new MemoryStream())
                {
                    var buf = new byte[32 * 1024];
                    int n;
                    while ((n = r.Body.Read(buf, 0, buf.Length)) > 0)
                    {
                        ms.Write(buf, 0, n);
                        if (ms.Length > maxBytes) break;
                    }
                    return Encoding.UTF8.GetString(ms.ToArray()).TrimStart('﻿');
                }
            }
        }

        /// <summary>A clear message for an HTTP error (same wording as Android).</summary>
        public static string StatusMessage(int status)
        {
            if (status == 401 || status == 403)
                return "The website refused this link (HTTP " + status + "). It may have expired, or it only works from the website: try Open link → Advanced, or get a fresh link.";
            if (status == 404 || status == 410)
                return "This link has expired or doesn't exist any more (HTTP " + status + "). Get a fresh link.";
            return "The website answered HTTP " + status + ".";
        }
    }

    /// <summary>Shared speed limit (bytes per second, 0 = none).</summary>
    public sealed class Throttle
    {
        private readonly object _lock = new object();
        private readonly Func<long> _bytesPerSecond;
        private DateTime _windowStart = DateTime.UtcNow;
        private long _windowBytes;

        public Throttle(Func<long> bytesPerSecond) { _bytesPerSecond = bytesPerSecond; }

        public void Take(int n)
        {
            var limit = _bytesPerSecond();
            if (limit <= 0) return;
            TimeSpan wait;
            lock (_lock)
            {
                var now = DateTime.UtcNow;
                if ((now - _windowStart).TotalSeconds >= 1) { _windowStart = now; _windowBytes = 0; }
                _windowBytes += n;
                if (_windowBytes <= limit) return;
                wait = _windowStart.AddSeconds(1) - now;
            }
            if (wait > TimeSpan.Zero) Thread.Sleep(wait);
        }
    }

    /// <summary>What a link really is, found by asking the website for its first bytes.</summary>
    public class LinkInfo
    {
        public bool Ok;
        public int Status;
        public string Message;
        /// <summary>"video", "hls", "playlist" (M3U list of channels), "page" (a web page).</summary>
        public string Kind;
        public long Length = -1;
        public string ContentType;
        public bool Ranges;
        public string Ext;
    }

    public static class LinkCheck
    {
        public static LinkInfo Probe(string url, IDictionary<string, string> extra)
        {
            var info = new LinkInfo();
            try
            {
                using (var r = HttpPolicy.Open(url, 0, 15000, 20000, extra))
                {
                    info.Status = r.Status;
                    if (r.Status >= 400) { info.Message = HttpPolicy.StatusMessage(r.Status); return info; }
                    string ct;
                    r.Headers.TryGetValue("Content-Type", out ct);
                    info.ContentType = ct ?? "";
                    info.Length = r.TotalLength;
                    info.Ranges = r.Status == 206;
                    var head = new byte[2048];
                    var got = 0;
                    while (got < head.Length)
                    {
                        var n = r.Body.Read(head, got, head.Length - got);
                        if (n <= 0) break;
                        got += n;
                    }
                    var text = Encoding.UTF8.GetString(head, 0, got).TrimStart('﻿', ' ', '\r', '\n', '\t');
                    var lowerUrl = url.ToLowerInvariant().Split('?')[0];
                    if (text.StartsWith("#EXTM3U") || text.StartsWith("#EXT-X-"))
                        info.Kind = text.Contains("#EXT-X-") ? "hls" : "playlist";
                    else if (text.StartsWith("<") && (ct ?? "").IndexOf("html", StringComparison.OrdinalIgnoreCase) >= 0)
                        info.Kind = "page";
                    else info.Kind = "video";
                    var ext = Path.GetExtension(lowerUrl).TrimStart('.');
                    info.Ext = info.Kind == "hls" ? "ts" : ext.Length > 0 && ext.Length <= 4 ? ext : (got > 0 && head[0] == 0x47 ? "ts" : "mp4");
                    info.Ok = info.Kind != "page";
                    if (info.Kind == "page") info.Message = "This link opens a web page, not a video. Use the video's own address (it often ends in .mp4, .mkv or .m3u8).";
                }
            }
            catch (Exception e)
            {
                info.Message = "Can't reach this link: " + LiveRelay.Describe(e, false);
            }
            return info;
        }
    }
}
