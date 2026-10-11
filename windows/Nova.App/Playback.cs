using System;
using System.Globalization;
using LibVLCSharp.Shared;

namespace Nova.App
{
    /// <summary>Builds VLC media with Nova's settings (buffer, user agent, hardware decoding).</summary>
    public static class Playback
    {
        /// <summary>local = the address is Nova's own disk buffer on this PC (small VLC buffer is enough).</summary>
        public static Media MakeMedia(string url, bool live, bool local = false)
        {
            var s = App.Store.Settings;
            var media = new Media(App.Vlc, new Uri(url));
            var caching = local ? (live ? 1200 : 1500) : Math.Max(300, s.NetworkCachingMs);
            media.AddOption(":network-caching=" + caching.ToString(CultureInfo.InvariantCulture));
            media.AddOption(":file-caching=1000");
            media.AddOption(":http-user-agent=" + s.UserAgent);
            media.AddOption(":avcodec-hw=" + (s.HardwareDecoding ? "any" : "none"));
            media.AddOption(":http-reconnect");
            return media;
        }

        public static string Time(long ms)
        {
            if (ms < 0) ms = 0;
            var t = TimeSpan.FromMilliseconds(ms);
            return t.TotalHours >= 1 ? ((int)t.TotalHours) + ":" + t.Minutes.ToString("00") + ":" + t.Seconds.ToString("00")
                : t.Minutes + ":" + t.Seconds.ToString("00");
        }
    }
}
