using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Nova.Core;
using Nova.Core.Streaming;

namespace Nova.App
{
    /// <summary>Open link: play / download any video link, M3U lists, recent links (same as the Android tab).</summary>
    public partial class OpenLinkView : UserControl, IView
    {
        public class RecentRow
        {
            public string Entry { get; set; }
            public string Url { get; set; }
            public string Title { get; set; }
            public string Sub { get; set; }
        }

        private LinkInfo _info;
        private string _infoUrl;
        private List<M3uEntry> _playlist;
        private bool _busy;

        public OpenLinkView()
        {
            InitializeComponent();
        }

        public void OnShown() { FillRecent(); }
        public void OnHidden() { }

        public void FocusStart()
        {
            LinkBox.Focus();
            LinkBox.SelectAll();
        }

        /// <summary>Opens a link from outside (drag &amp; drop, "Open with Nova", command line).</summary>
        public void OpenFromOutside(string url)
        {
            LinkBox.Text = url;
            OnPlay(null, null);
        }

        private string Url { get { return (LinkBox.Text ?? "").Trim(); } }

        private Dictionary<string, string> Headers()
        {
            var h = new Dictionary<string, string>();
            if (!string.IsNullOrWhiteSpace(RefererBox.Text)) h["Referer"] = RefererBox.Text.Trim();
            if (!string.IsNullOrWhiteSpace(AgentBox.Text)) h["User-Agent"] = AgentBox.Text.Trim();
            return h.Count == 0 ? null : h;
        }

        private void OnLinkChanged(object sender, TextChangedEventArgs e)
        {
            Placeholder.Visibility = LinkBox.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
            string srv, u, p;
            AccountBtn.Visibility = XtreamApi.TryParseLink(Url, out srv, out u, out p) ? Visibility.Visible : Visibility.Collapsed;
            // The website's remembered details appear in Advanced.
            var host = HttpPolicy.HostOf(Url);
            if (host != null && !App.Store.IsAccountHost(host))
            {
                var saved = App.Store.LinkHeaders(host);
                string v;
                RefererBox.Text = saved.TryGetValue("Referer", out v) ? v : "";
                AgentBox.Text = saved.TryGetValue("User-Agent", out v) ? v : "";
            }
        }

        private void OnLinkKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) { e.Handled = true; OnPlay(null, null); }
            else if (e.Key == Key.Down) { e.Handled = true; PlayBtn.Focus(); }
            else if (e.Key == Key.Up) { e.Handled = true; App.Home.FocusTabs(); }
        }

        private void OnPaste(object sender, RoutedEventArgs e)
        {
            try
            {
                var text = Clipboard.ContainsText() ? Clipboard.GetText().Trim() : "";
                if (text.Length == 0) { App.Home.Toast("Nothing copied yet"); return; }
                // "Look at this: https://…" → just the link.
                var i = text.IndexOf("http", StringComparison.OrdinalIgnoreCase);
                if (i > 0) text = text.Substring(i).Split(' ', '\n', '\r', '\t')[0];
                LinkBox.Text = text;
                PlayBtn.Focus();
            }
            catch (Exception ex) { App.Home.Toast(ex.Message); }
        }

        private void OnAdvanced(object sender, RoutedEventArgs e)
        {
            var open = AdvancedBox.Visibility != Visibility.Visible;
            AdvancedBox.Visibility = open ? Visibility.Visible : Visibility.Collapsed;
            AdvancedBtn.Content = open ? "Advanced ▾" : "Advanced ▸";
            if (open) RefererBox.Focus();
        }

        private void OnBrowserAgent(object sender, RoutedEventArgs e)
        {
            AgentBox.Text = HttpPolicy.BrowserAgent;
        }

        private void SaveHeaders()
        {
            var host = HttpPolicy.HostOf(Url);
            if (host == null || App.Store.IsAccountHost(host)) return;
            var h = Headers();
            if (h != null) App.Store.SetLinkHeaders(host, h);
        }

        private void SetStatus(string text, bool error)
        {
            Status.Text = text ?? "";
            Status.Foreground = (Brush)FindResource(error ? "ErrorBrush" : "SubText");
        }

        /// <summary>Finds out what the link is (video, HLS, M3U list, web page). Shared by the three buttons.</summary>
        private async Task<LinkInfo> Check()
        {
            var url = Url;
            if (!url.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                if (File.Exists(url)) return new LinkInfo { Ok = true, Kind = "file" };
                SetStatus("Paste or type a video link (http:// or https://).", true);
                return null;
            }
            if (_info != null && _infoUrl == url) return _info;
            SaveHeaders();
            SetStatus("Checking the link…", false);
            var headers = Headers();
            var info = await Task.Run(() => LinkCheck.Probe(url, headers));
            if (!info.Ok) { SetStatus(info.Message, true); return null; }
            _info = info;
            _infoUrl = url;
            var size = info.Length > 0 ? "  ·  " + DownloadRow.Size(info.Length) : "";
            SetStatus(info.Kind == "hls" ? "HLS video (.m3u8)" + size : info.Kind == "playlist" ? "A list of channels / videos (M3U)" : "Video" + (info.Ext != null ? " (" + info.Ext + ")" : "") + size, false);
            App.Store.RememberLink(url);
            FillRecent();
            return info;
        }

        private string TitleOf(string url)
        {
            try
            {
                var u = new Uri(url);
                var name = Uri.UnescapeDataString(Path.GetFileNameWithoutExtension(u.AbsolutePath));
                return string.IsNullOrWhiteSpace(name) ? u.Host : name;
            }
            catch { return "Video"; }
        }

        private PlayItem ItemFor(string url, LinkInfo info, bool downloadWatch)
        {
            return new PlayItem
            {
                Title = TitleOf(url),
                Subtitle = HttpPolicy.HostOf(url),
                Url = url,
                Kind = "link",
                Ext = info.Ext,
                Hls = info.Kind == "hls",
                Headers = Headers(),
                DownloadWatch = downloadWatch,
                ResumeKey = "link__" + url,
            };
        }

        private async void OnPlay(object sender, RoutedEventArgs e)
        {
            if (_busy) return;
            _busy = true;
            try
            {
                var url = Url;
                if (File.Exists(url)) { await PlayerWindow.Play(Window.GetWindow(this), new List<PlayItem> { PlayItem.OfFile(url, null) }, 0); return; }
                var info = await Check();
                if (info == null) return;
                if (info.Kind == "playlist") { await ShowPlaylist(url); return; }
                await PlayerWindow.Play(Window.GetWindow(this), new List<PlayItem> { ItemFor(url, info, false) }, 0);
            }
            finally { _busy = false; }
        }

        private async void OnDownloadWatch(object sender, RoutedEventArgs e)
        {
            if (_busy) return;
            _busy = true;
            try
            {
                var url = Url;
                var info = await Check();
                if (info == null) return;
                if (info.Kind == "playlist") { SetStatus("This is a list of channels: choose one below to play it.", false); await ShowPlaylist(url); return; }
                var d = App.Downloads.AddLink(url, TitleOf(url), info.Ext, info.Kind == "hls", Headers());
                App.Downloads.Prioritize(d.Id);
                await PlayerWindow.Play(Window.GetWindow(this), new List<PlayItem> { ItemFor(url, info, true) }, 0);
            }
            finally { _busy = false; }
        }

        private async void OnDownload(object sender, RoutedEventArgs e)
        {
            if (_busy) return;
            _busy = true;
            try
            {
                var url = Url;
                var info = await Check();
                if (info == null) return;
                if (info.Kind == "playlist") { SetStatus("A list of channels can't be downloaded — open one of its videos instead.", true); await ShowPlaylist(url); return; }
                var d = App.Downloads.AddLink(url, TitleOf(url), info.Ext, info.Kind == "hls", Headers());
                SetStatus("Download added — see the Downloads tab (" + Path.GetDirectoryName(d.FinalPath) + ")", false);
                App.Home.Toast("⬇ Download added — see the Downloads tab");
            }
            finally { _busy = false; }
        }

        private void OnAddAccount(object sender, RoutedEventArgs e)
        {
            string srv, u, p;
            if (!XtreamApi.TryParseLink(Url, out srv, out u, out p)) return;
            AccountDialog.Edit(Window.GetWindow(this), new Account { Server = srv, Username = u, Password = p });
        }

        // ---------------------------------------------------------------- M3U lists

        private async Task ShowPlaylist(string url)
        {
            SetStatus("Reading the list…", false);
            var headers = Headers();
            try
            {
                var text = await Task.Run(() => HttpPolicy.ReadText(url, headers, 30 * 1024 * 1024));
                _playlist = M3uPlaylist.Parse(text, url);
            }
            catch (Exception ex) { SetStatus("Couldn't read the list: " + ex.Message, true); return; }
            PlaylistBox.Visibility = Visibility.Visible;
            PlaylistTitle.Text = _playlist.Count + " in this list  ·  Enter plays (▲ ▼ in the player changes)";
            PlaylistList.ItemsSource = _playlist;
            SetStatus(_playlist.Count == 0 ? "This list is empty." : "Choose a channel or video below.", _playlist.Count == 0);
            if (_playlist.Count > 0)
            {
                PlaylistList.SelectedIndex = 0;
                PlaylistList.UpdateLayout();
                var c = PlaylistList.ItemContainerGenerator.ContainerFromIndex(0) as ListBoxItem;
                if (c != null) c.Focus();
            }
        }

        private void OnPlaylistKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) { e.Handled = true; PlayPlaylist(PlaylistList.SelectedIndex); }
        }

        private void OnPlaylistDoubleClick(object sender, MouseButtonEventArgs e)
        {
            PlayPlaylist(PlaylistList.SelectedIndex);
        }

        private async void PlayPlaylist(int index)
        {
            if (_playlist == null || index < 0) return;
            var headers = Headers();
            var items = _playlist.Select(m =>
            {
                var path = m.Url.ToLowerInvariant().Split('?')[0];
                var film = path.EndsWith(".mp4") || path.EndsWith(".mkv") || path.EndsWith(".avi") || path.EndsWith(".mov");
                return new PlayItem
                {
                    Title = m.Name,
                    Subtitle = m.Group,
                    Url = m.Url,
                    Kind = film ? "link" : "live",
                    IsLive = !film,
                    LiveKey = film ? null : "link|" + m.Url,
                    Hls = path.EndsWith(".m3u8"),
                    Ext = Path.GetExtension(path).TrimStart('.'),
                    Headers = headers,
                    Icon = m.Logo,
                };
            }).ToList();
            await PlayerWindow.Play(Window.GetWindow(this), items, index);
        }

        // ---------------------------------------------------------------- recent links

        private void FillRecent()
        {
            var rows = App.Store.State.RecentLinks.Select(e =>
            {
                var url = Store.LinkOf(e);
                var when = Store.LinkTime(e);
                return new RecentRow
                {
                    Entry = e,
                    Url = url,
                    Title = TitleOf(url),
                    Sub = (when.HasValue ? "Added " + when.Value.ToString("d MMM yyyy  HH:mm") + "  ·  " : "") + url,
                };
            }).ToList();
            RecentList.ItemsSource = rows;
            NoRecent.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            DeleteAllBtn.Visibility = rows.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        }

        private void OnRecentKey(object sender, KeyEventArgs e)
        {
            var row = RecentList.SelectedItem as RecentRow;
            if (row == null) return;
            if (e.Key == Key.Enter) { e.Handled = true; LinkBox.Text = row.Url; OnPlay(null, null); }
            else if (e.Key == Key.Delete) { e.Handled = true; Delete(row); }
        }

        private void OnRecentDoubleClick(object sender, MouseButtonEventArgs e)
        {
            var row = RecentList.SelectedItem as RecentRow;
            if (row != null) { LinkBox.Text = row.Url; OnPlay(null, null); }
        }

        private void OnDeleteOne(object sender, RoutedEventArgs e)
        {
            Delete(((FrameworkElement)sender).DataContext as RecentRow);
        }

        private void Delete(RecentRow row)
        {
            if (row == null) return;
            if (!NovaDialog.Ask(Window.GetWindow(this), "Delete this link?", row.Title + "\n" + row.Url, "Yes, delete")) return;
            App.Store.ForgetLink(row.Entry);
            FillRecent();
        }

        private void OnDeleteAll(object sender, RoutedEventArgs e)
        {
            if (!NovaDialog.Ask(Window.GetWindow(this), "Delete all recent links?", null, "Yes, delete all")) return;
            App.Store.ForgetLink(null);
            FillRecent();
        }
    }
}
