using System;
using System.Collections.Generic;
using System.Globalization;
using LibVLCSharp.Shared;

namespace Nova.App
{
    /// <summary>Builds VLC media with Nova's settings (buffer, user agent, hardware decoding, website headers).</summary>
    public static class Playback
    {
        /// <summary>local = the address is Nova's own disk buffer on this PC (a small VLC buffer is enough).</summary>
        public static Media MakeMedia(string url, bool live, bool local = false)
        {
            return MakeMedia(App.Vlc, url, live, local, false, null);
        }

        public static Media MakeMedia(LibVLC vlc, string url, bool live, bool local, bool softwareOnly, IDictionary<string, string> headers)
        {
            return MakeMedia(vlc, url, live, local, softwareOnly, headers, 100);
        }

        /// <summary>boost above 100 % adds a soft limiter (compressor), so loud scenes don't crackle.</summary>
        public static Media MakeMedia(LibVLC vlc, string url, bool live, bool local, bool softwareOnly, IDictionary<string, string> headers, int boost)
        {
            var s = App.Store.Settings;
            var media = new Media(vlc, new Uri(url));
            var caching = local ? (live ? 1200 : 1500) : Math.Max(300, s.NetworkCachingMs);
            media.AddOption(":network-caching=" + caching.ToString(CultureInfo.InvariantCulture));
            media.AddOption(":file-caching=1000");
            string ua = null, referer = null;
            if (headers != null) { headers.TryGetValue("User-Agent", out ua); headers.TryGetValue("Referer", out referer); }
            media.AddOption(":http-user-agent=" + (string.IsNullOrEmpty(ua) ? s.UserAgent : ua));
            if (!string.IsNullOrEmpty(referer)) media.AddOption(":http-referrer=" + referer);
            media.AddOption(":avcodec-hw=" + (s.HardwareDecoding && !softwareOnly ? "any" : "none"));
            media.AddOption(":http-reconnect");
            if (boost > 100) media.AddOption(":audio-filter=compressor");
            return media;
        }

        /// <summary>VLC start options; withSubtitleStyle adds the subtitle size / colour / background from Settings.</summary>
        public static string[] VlcOptions(bool withSubtitleStyle)
        {
            var list = new List<string> { "--no-video-title-show", "--no-snapshot-preview", "--no-stats", "--quiet" };
            if (withSubtitleStyle)
            {
                var s = App.Store.Settings;
                // Android size 10–48 (20 = normal) → VLC relative size (16 = normal, smaller number = bigger text).
                var rel = (int)Math.Round(320.0 / Math.Max(10, Math.Min(48, s.SubtitleSize)));
                rel = Math.Max(5, Math.Min(32, rel));
                list.Add("--freetype-rel-fontsize=" + rel.ToString(CultureInfo.InvariantCulture));
                list.Add("--freetype-color=" + ColorOf(s.SubtitleColor).ToString(CultureInfo.InvariantCulture));
                list.Add("--freetype-outline-thickness=" + (s.SubtitleBackground ? "0" : "4"));
                if (s.SubtitleBackground)
                {
                    list.Add("--freetype-background-opacity=160");
                    list.Add("--freetype-background-color=0");
                }
            }
            return list.ToArray();
        }

        public static int ColorOf(string name)
        {
            switch (name)
            {
                case "yellow": return 0xFFFF00;
                case "green": return 0x66FF66;
                case "cyan": return 0x66FFFF;
                default: return 0xFFFFFF;
            }
        }

        public static string Time(long ms)
        {
            if (ms < 0) ms = 0;
            var t = TimeSpan.FromMilliseconds(ms);
            return t.TotalHours >= 1 ? ((int)t.TotalHours) + ":" + t.Minutes.ToString("00") + ":" + t.Seconds.ToString("00")
                : t.Minutes + ":" + t.Seconds.ToString("00");
        }

        /// <summary>A VLC codec code (four letters) as text.</summary>
        public static string FourCC(uint code)
        {
            if (code == 0) return "?";
            var b = BitConverter.GetBytes(code);
            var s = System.Text.Encoding.ASCII.GetString(b).Trim('\0', ' ');
            return s.Length == 0 ? "?" : s;
        }
    }
}
