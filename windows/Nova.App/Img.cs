using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Nova.App
{
    /// <summary>
    /// Loads logos and posters from the internet in the background, keeps them in a disk cache,
    /// and decodes them at the size shown (keeps memory low on old PCs).
    /// Usage in XAML: &lt;Image local:Img.Url="{Binding Icon}" local:Img.Width="120"/&gt;
    /// </summary>
    public static class Img
    {
        private static string _folder;
        private static Func<string> _userAgent;
        private static HttpClient _http;
        private static readonly SemaphoreSlim Gate = new SemaphoreSlim(6);
        private static readonly object MemLock = new object();
        private static readonly Dictionary<string, ImageSource> Mem = new Dictionary<string, ImageSource>();
        private static readonly LinkedList<string> MemOrder = new LinkedList<string>();
        private static readonly HashSet<string> Failed = new HashSet<string>();
        private const int MemMax = 400;
        private const long DiskMax = 300L * 1024 * 1024;

        public static void Init(string folder, Func<string> userAgent)
        {
            _folder = folder;
            _userAgent = userAgent;
            try { Directory.CreateDirectory(folder); } catch { }
            var h = new HttpClientHandler { AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate };
            _http = new HttpClient(h) { Timeout = TimeSpan.FromSeconds(20) };
            Task.Run(() => TrimDisk());
        }

        public static readonly DependencyProperty UrlProperty = DependencyProperty.RegisterAttached(
            "Url", typeof(string), typeof(Img), new PropertyMetadata(null, OnUrlChanged));

        public static string GetUrl(DependencyObject o) { return (string)o.GetValue(UrlProperty); }
        public static void SetUrl(DependencyObject o, string v) { o.SetValue(UrlProperty, v); }

        /// <summary>Decode width in pixels (0 = natural size).</summary>
        public static readonly DependencyProperty WidthProperty = DependencyProperty.RegisterAttached(
            "Width", typeof(int), typeof(Img), new PropertyMetadata(0, OnWidthChanged));

        // Bindings may set the address before the size: load again at the right size.
        private static void OnWidthChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            var url = GetUrl(d);
            if (!string.IsNullOrEmpty(url)) OnUrlChanged(d, new DependencyPropertyChangedEventArgs(UrlProperty, null, url));
        }

        public static int GetWidth(DependencyObject o) { return (int)o.GetValue(WidthProperty); }
        public static void SetWidth(DependencyObject o, int v) { o.SetValue(WidthProperty, v); }

        private static async void OnUrlChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            var image = d as Image;
            if (image == null) return;
            var url = e.NewValue as string;
            image.Source = null;
            if (string.IsNullOrWhiteSpace(url) || !(url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)))
                return;
            var width = GetWidth(image);
            var key = width + "|" + url;
            lock (MemLock)
            {
                ImageSource hit;
                if (Mem.TryGetValue(key, out hit)) { image.Source = hit; return; }
                if (Failed.Contains(url)) return;
            }
            ImageSource src = null;
            try
            {
                src = await Task.Run(() => Load(url, width));
            }
            catch (Exception)
            {
                src = null;
            }
            // The list may have reused this image for another item while it loaded.
            if (src != null && GetUrl(image) == url) image.Source = src;
        }

        private static async Task<ImageSource> Load(string url, int width)
        {
            var key = width + "|" + url;
            byte[] bytes = null;
            var file = _folder == null ? null : Path.Combine(_folder, Hash(url) + ".img");
            if (file != null && File.Exists(file))
            {
                try { bytes = File.ReadAllBytes(file); } catch { bytes = null; }
            }
            if (bytes == null)
            {
                await Gate.WaitAsync().ConfigureAwait(false);
                try
                {
                    var req = new HttpRequestMessage(HttpMethod.Get, url);
                    req.Headers.TryAddWithoutValidation("User-Agent", _userAgent == null ? "NovaIPTV" : _userAgent());
                    using (var resp = await _http.SendAsync(req).ConfigureAwait(false))
                    {
                        if (!resp.IsSuccessStatusCode) { MarkFailed(url); return null; }
                        bytes = await resp.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                    }
                }
                catch
                {
                    MarkFailed(url);
                    return null;
                }
                finally
                {
                    Gate.Release();
                }
                if (bytes == null || bytes.Length == 0 || bytes.Length > 8 * 1024 * 1024) { MarkFailed(url); return null; }
                if (file != null)
                {
                    try { File.WriteAllBytes(file, bytes); } catch { }
                }
            }

            BitmapImage bmp;
            try
            {
                bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
                if (width > 0) bmp.DecodePixelWidth = width;
                bmp.StreamSource = new MemoryStream(bytes);
                bmp.EndInit();
                bmp.Freeze();
            }
            catch
            {
                MarkFailed(url);
                if (file != null) { try { File.Delete(file); } catch { } }
                return null;
            }

            lock (MemLock)
            {
                if (!Mem.ContainsKey(key))
                {
                    Mem[key] = bmp;
                    MemOrder.AddLast(key);
                    while (MemOrder.Count > MemMax)
                    {
                        Mem.Remove(MemOrder.First.Value);
                        MemOrder.RemoveFirst();
                    }
                }
            }
            return bmp;
        }

        private static void MarkFailed(string url)
        {
            lock (MemLock) Failed.Add(url);
        }

        private static string Hash(string s)
        {
            using (var sha = SHA1.Create())
            {
                var b = sha.ComputeHash(Encoding.UTF8.GetBytes(s));
                var sb = new StringBuilder(40);
                foreach (var x in b) sb.Append(x.ToString("x2"));
                return sb.ToString();
            }
        }

        /// <summary>Keeps the picture cache under its size limit (oldest pictures go first).</summary>
        private static void TrimDisk()
        {
            try
            {
                var files = new DirectoryInfo(_folder).GetFiles("*.img");
                long total = files.Sum(f => f.Length);
                if (total <= DiskMax) return;
                foreach (var f in files.OrderBy(f => f.LastWriteTimeUtc))
                {
                    if (total <= DiskMax * 8 / 10) break;
                    total -= f.Length;
                    try { f.Delete(); } catch { }
                }
            }
            catch { }
        }

        public static void ClearCache()
        {
            lock (MemLock) { Mem.Clear(); MemOrder.Clear(); Failed.Clear(); }
            try
            {
                foreach (var f in new DirectoryInfo(_folder).GetFiles("*.img")) { try { f.Delete(); } catch { } }
            }
            catch { }
        }
    }
}
