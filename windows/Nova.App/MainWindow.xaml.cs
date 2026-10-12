using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using Nova.Core;

namespace Nova.App
{
    /// <summary>A screen that knows when it is shown or hidden, and where keyboard focus should start.</summary>
    public interface IView
    {
        void OnShown();
        void OnHidden();
        void FocusStart();
    }

    public enum Tab { Live, Films, Series, Search, Link, Recent, Downloads, Settings }

    /// <summary>A screen that uses Back itself first (edit mode, preview…). True = handled.</summary>
    public interface IBackHandler
    {
        bool HandleBack();
    }

    public partial class MainWindow : Window
    {
        private readonly Dictionary<Tab, FrameworkElement> _views = new Dictionary<Tab, FrameworkElement>();
        private readonly Stack<Tuple<FrameworkElement, IInputElement>> _pages = new Stack<Tuple<FrameworkElement, IInputElement>>();
        private readonly DispatcherTimer _toastTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.6) };
        private readonly DispatcherTimer _clock = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
        private Tab _tab = Tab.Live;
        private DateTime _lastBack = DateTime.MinValue;
        private bool _switching;
        private readonly DispatcherTimer _vpnTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(8) };

        public MainWindow()
        {
            InitializeComponent();
            var s = App.Store.Settings;
            if (s.WindowWidth >= MinWidth && s.WindowHeight >= MinHeight) { Width = s.WindowWidth; Height = s.WindowHeight; }
            if (s.WindowMaximized) WindowState = WindowState.Maximized;

            _toastTimer.Tick += (o, e) => { _toastTimer.Stop(); ToastBox.Visibility = Visibility.Collapsed; };
            _clock.Tick += (o, e) => UpdateClock();
            UpdateClock();
            _clock.Start();

            App.Store.AccountsChanged += OnAccountsChanged;
            Title = "Nova IPTV " + Brand.Version;
            SignatureText.Text = Brand.Signature;
            WelcomeVersion.Text = "Nova IPTV for Windows " + Brand.Version + "  ·  " + Brand.Signature;
            _vpnTimer.Tick += (o, e) => UpdateVpnBadge();
            _vpnTimer.Start();
            UpdateVpnBadge();
            App.Recordings.Changed += () => Dispatcher.BeginInvoke(new Action(UpdateRecBadge));
        }

        private void UpdateVpnBadge()
        {
            var vpn = Vpn.Active();
            VpnBadge.Visibility = vpn != null ? Visibility.Visible : Visibility.Collapsed;
            VpnBadge.ToolTip = vpn != null ? "VPN on: " + vpn : null;
        }

        private void UpdateRecBadge()
        {
            var keys = App.Recordings.RecordingKeys;
            RecBadge.Visibility = keys.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            RecBadgeText.Text = keys.Count == 1 ? "● REC " + App.Recordings.NameOf(keys[0]) : "● REC " + keys.Count;
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            ShowTab(Tab.Live);
            UpdateWelcome();
            Dispatcher.BeginInvoke(new Action(App.AfterStart), DispatcherPriority.ApplicationIdle);
        }

        // ---------------------------------------------------------------- from outside: drag & drop, "Open with Nova"

        /// <summary>A file or link given to Nova (command line, "Open with Nova", a second start, drag &amp; drop).</summary>
        public async void OpenFromOutside(string arg)
        {
            if (string.IsNullOrWhiteSpace(arg)) return;
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
            Activate();
            var a = arg.Trim().Trim('"');
            if (System.IO.File.Exists(a))
            {
                var ext = System.IO.Path.GetExtension(a).ToLowerInvariant();
                if (ext == ".bak") { BackupUi.RestoreFromFile(this, a); return; }
                if (ext == ".m3u" || ext == ".m3u8")
                {
                    var entries = M3uPlaylist.Parse(System.IO.File.ReadAllText(a), new Uri(a).AbsoluteUri);
                    if (entries.Count > 0 && entries.Any(x => x.Url.StartsWith("http", StringComparison.OrdinalIgnoreCase)))
                    {
                        ShowTab(Tab.Link);
                        var v = ViewFor(Tab.Link) as OpenLinkView;
                        if (v != null) v.OpenFromOutside(entries[0].Url);
                        return;
                    }
                }
                await PlayerWindow.Play(this, new List<PlayItem> { PlayItem.OfFile(a, null) }, 0);
                return;
            }
            if (a.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || a.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                ShowTab(Tab.Link);
                var v = ViewFor(Tab.Link) as OpenLinkView;
                if (v != null) v.OpenFromOutside(a);
            }
        }

        private void OnDragOver(object sender, DragEventArgs e)
        {
            e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) || e.Data.GetDataPresent(DataFormats.Text) || e.Data.GetDataPresent(DataFormats.UnicodeText)
                ? DragDropEffects.Copy : DragDropEffects.None;
            e.Handled = true;
        }

        private void OnDrop(object sender, DragEventArgs e)
        {
            if (e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                var files = e.Data.GetData(DataFormats.FileDrop) as string[];
                if (files != null && files.Length > 0) OpenFromOutside(files[0]);
                return;
            }
            var text = (e.Data.GetData(DataFormats.UnicodeText) ?? e.Data.GetData(DataFormats.Text)) as string;
            if (!string.IsNullOrWhiteSpace(text)) OpenFromOutside(text.Trim().Split('\n')[0]);
        }

        private void UpdateClock()
        {
            Clock.Text = DateTime.Now.ToString("HH:mm");
        }

        // ---------------------------------------------------------------- tabs

        private FrameworkElement ViewFor(Tab t)
        {
            FrameworkElement v;
            if (_views.TryGetValue(t, out v)) return v;
            switch (t)
            {
                case Tab.Live: v = new LiveView(); break;
                case Tab.Films: v = new VodView(ContentType.Movie); break;
                case Tab.Series: v = new VodView(ContentType.Series); break;
                case Tab.Search: v = new SearchView(); break;
                case Tab.Link: v = new OpenLinkView(); break;
                case Tab.Recent: v = new RecentView(); break;
                case Tab.Downloads: v = new DownloadsView(); break;
                default: v = new SettingsView(); break;
            }
            _views[t] = v;
            return v;
        }

        public void ShowTab(Tab t)
        {
            while (_pages.Count > 0) PopPage(false);
            var old = TabHost.Content as IView;
            var view = ViewFor(t);
            if (old != null && !ReferenceEquals(old, view)) old.OnHidden();
            _tab = t;
            _switching = true;
            RadioFor(t).IsChecked = true;
            _switching = false;
            TabHost.Content = view;
            var iv = view as IView;
            if (iv != null)
            {
                iv.OnShown();
                Dispatcher.BeginInvoke(new Action(iv.FocusStart), DispatcherPriority.Loaded);
            }
        }

        private RadioButton RadioFor(Tab t)
        {
            switch (t)
            {
                case Tab.Films: return TabFilms;
                case Tab.Series: return TabSeries;
                case Tab.Search: return TabSearch;
                case Tab.Link: return TabLink;
                case Tab.Recent: return TabRecent;
                case Tab.Downloads: return TabDownloads;
                case Tab.Settings: return TabSettings;
                default: return TabLive;
            }
        }

        private void OnTabChecked(object sender, RoutedEventArgs e)
        {
            if (_switching || !IsLoaded) return;
            var t = sender == TabFilms ? Tab.Films : sender == TabSeries ? Tab.Series : sender == TabSearch ? Tab.Search
                : sender == TabLink ? Tab.Link : sender == TabRecent ? Tab.Recent
                : sender == TabDownloads ? Tab.Downloads : sender == TabSettings ? Tab.Settings : Tab.Live;
            if (t != _tab || _pages.Count > 0) ShowTab(t);
        }

        public Tab CurrentTab { get { return _tab; } }

        // ---------------------------------------------------------------- pages (film / series details)

        /// <summary>Shows a page over the current tab; Back returns to where the focus was.</summary>
        public void PushPage(FrameworkElement page)
        {
            var current = _pages.Count > 0 ? _pages.Peek().Item1 : null;
            if (current != null) current.Visibility = Visibility.Collapsed;
            _pages.Push(Tuple.Create(page, Keyboard.FocusedElement));
            PageHost.Content = page;
            page.Visibility = Visibility.Visible;
            PageHost.Visibility = Visibility.Visible;
            TabHost.Visibility = Visibility.Hidden;
            var iv = page as IView;
            if (iv != null)
            {
                iv.OnShown();
                Dispatcher.BeginInvoke(new Action(iv.FocusStart), DispatcherPriority.Loaded);
            }
        }

        private void PopPage(bool restoreFocus)
        {
            if (_pages.Count == 0) return;
            var top = _pages.Pop();
            var iv = top.Item1 as IView;
            if (iv != null) iv.OnHidden();
            if (_pages.Count > 0)
            {
                PageHost.Content = _pages.Peek().Item1;
                _pages.Peek().Item1.Visibility = Visibility.Visible;
            }
            else
            {
                PageHost.Content = null;
                PageHost.Visibility = Visibility.Collapsed;
                TabHost.Visibility = Visibility.Visible;
            }
            if (restoreFocus)
            {
                var target = top.Item2;
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    var fe = target as UIElement;
                    if (fe != null && fe.IsVisible) fe.Focus();
                    else
                    {
                        var v = (_pages.Count > 0 ? _pages.Peek().Item1 : TabHost.Content) as IView;
                        if (v != null) v.FocusStart();
                    }
                }), DispatcherPriority.Loaded);
            }
        }

        // ---------------------------------------------------------------- keys

        private void OnPreviewKeyDown(object sender, KeyEventArgs e)
        {
            var inText = Keyboard.FocusedElement is TextBox;
            if (Keyboard.Modifiers == ModifierKeys.None && HandleTabKeys(e)) { e.Handled = true; return; }
            if (e.Key == Key.Escape || e.Key == Key.BrowserBack || (e.Key == Key.Back && !inText))
            {
                e.Handled = true;
                Back();
                return;
            }
            var action = KeyMap.ActionFor(KeyArea.Lists, e);
            if (action == null) return;
            if (inText && Keyboard.Modifiers == ModifierKeys.None && !(e.Key >= Key.F1 && e.Key <= Key.F12)) return;
            e.Handled = true;
            switch (action)
            {
                case "list_live": ShowTab(Tab.Live); break;
                case "list_films": ShowTab(Tab.Films); break;
                case "list_series": ShowTab(Tab.Series); break;
                case "list_search": ShowTab(Tab.Search); break;
                case "list_link": ShowTab(Tab.Link); break;
                case "list_recent": ShowTab(Tab.Recent); break;
                case "list_downloads": ShowTab(Tab.Downloads); break;
                case "list_settings": ShowTab(Tab.Settings); break;
                case "list_reload": OnReload(null, null); break;
                case "list_last": PlayLastChannel(); break;
                default:
                    {
                        // Edit / sort belong to the zone on screen.
                        var zone = (_pages.Count > 0 ? null : TabHost.Content) as IZoneActions;
                        if (zone == null) { e.Handled = false; break; }
                        if (action == "list_edit") zone.ToggleEdit(); else if (action == "list_sort") zone.ChooseSort();
                        break;
                    }
            }
        }

        /// <summary>Plays the last channel you watched.</summary>
        public async void PlayLastChannel()
        {
            var last = App.Store.Recent.FirstOrDefault(r => r.Type == ItemType.Live);
            if (last == null) { Toast("No channel watched yet"); return; }
            var channels = App.Store.Recent.Where(r => r.Type == ItemType.Live).Select(r => PlayItem.Of(r.ToChannel())).ToList();
            await PlayerWindow.Play(this, channels, 0);
        }

        /// <summary>
        /// Remote-friendly moves between the top tabs and the screen: ▲ from the top of a list goes up
        /// to the tabs, ▼ comes back down, Enter on a tab opens it.
        /// </summary>
        private bool HandleTabKeys(KeyEventArgs e)
        {
            var focused = Keyboard.FocusedElement as DependencyObject;
            var tab = focused as RadioButton;
            if (tab != null && tab.GroupName == "tabs")
            {
                if (e.Key == Key.Down)
                {
                    var v = (_pages.Count > 0 ? _pages.Peek().Item1 : TabHost.Content) as IView;
                    if (v != null) v.FocusStart();
                    return true;
                }
                if (e.Key == Key.Enter) { tab.IsChecked = true; return true; }
                return false;
            }
            if (e.Key == Key.Up && _pages.Count == 0 && _tab != Tab.Search)
            {
                var item = focused as ListBoxItem;
                if (item != null)
                {
                    var list = ItemsControl.ItemsControlFromItemContainer(item);
                    if (list != null && list.ItemContainerGenerator.IndexFromContainer(item) == 0) { FocusTabs(); return true; }
                }
            }
            return false;
        }

        /// <summary>Puts the keyboard focus on the current tab in the top bar.</summary>
        public void FocusTabs()
        {
            RadioFor(_tab).Focus();
        }

        /// <summary>Back: closes a page, then returns to Live TV, then (twice) closes Nova.</summary>
        public void Back()
        {
            var page = (_pages.Count > 0 ? _pages.Peek().Item1 : TabHost.Content) as IBackHandler;
            if (page != null && page.HandleBack()) return;
            if (_pages.Count > 0) { PopPage(true); return; }
            if (_tab != Tab.Live) { ShowTab(Tab.Live); return; }
            if (!App.Store.Settings.DoubleBackExit) return;
            if ((DateTime.Now - _lastBack).TotalSeconds < 2) { Close(); return; }
            _lastBack = DateTime.Now;
            Toast("Press Back again to close Nova");
        }

        // ---------------------------------------------------------------- accounts

        private void OnWelcomeAdd(object sender, RoutedEventArgs e)
        {
            AccountDialog.Edit(this, null);
        }

        private void OnWelcomeRestore(object sender, RoutedEventArgs e)
        {
            BackupUi.Restore(this);
        }

        private void OnWelcomeLink(object sender, RoutedEventArgs e)
        {
            BackupUi.ConnectAccountsLink(this);
        }

        public void ShowWelcomeIfEmpty()
        {
            UpdateWelcome();
            if (App.Store.Accounts.Count == 0) WelcomeAdd.Focus();
        }

        private void OnAccountsChanged()
        {
            App.Catalog.Clear();
            UpdateWelcome();
            foreach (var kv in _views)
            {
                var r = kv.Value as IReloadable;
                if (r != null) r.Reload();
            }
        }

        private void UpdateWelcome()
        {
            Welcome.Visibility = App.Store.Accounts.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private void OnReload(object sender, RoutedEventArgs e)
        {
            App.Catalog.Clear();
            foreach (var kv in _views)
            {
                var r = kv.Value as IReloadable;
                if (r != null) r.Reload();
            }
            Toast("Downloading the lists again…");
        }

        // ---------------------------------------------------------------- messages

        public void Toast(string message)
        {
            ToastText.Text = message;
            ToastBox.Visibility = Visibility.Visible;
            _toastTimer.Stop();
            _toastTimer.Start();
        }

        private void OnClosing(object sender, CancelEventArgs e)
        {
            try
            {
                var maximized = WindowState == WindowState.Maximized;
                var b = RestoreBounds;
                App.Store.SaveWindow(maximized ? b.Width : ActualWidth, maximized ? b.Height : ActualHeight, maximized);
            }
            catch { }
            foreach (var kv in _views)
            {
                var v = kv.Value as IView;
                if (v != null) v.OnHidden();
            }
        }
    }

    /// <summary>A zone with edit mode and sorting (Live TV, Films, Series).</summary>
    public interface IZoneActions
    {
        void ToggleEdit();
        void ChooseSort();
    }

    /// <summary>A screen that downloads its lists again after an account change or Reload.</summary>
    public interface IReloadable
    {
        void Reload();
    }
}
