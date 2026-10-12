using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using Nova.Core;
using Nova.Core.Streaming;

namespace Nova.App
{
    /// <summary>Live TV: groups | channels | preview with programme guide (TiviMate style).</summary>
    public partial class LiveView : UserControl, IView, IReloadable
    {
        private readonly ObservableCollection<ChannelRow> _rows = new ObservableCollection<ChannelRow>();
        private readonly DispatcherTimer _epgTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        private readonly DispatcherTimer _nowTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(30) };
        private CancellationTokenSource _epgCts;
        private GroupRow _group;
        private int _groupLoadId;
        private bool _loaded;
        private bool _playerOpen;

        private MediaPlayer _preview;
        private ChannelRow _previewRow;
        private LiveChannel _previewChannel;
        private Task _stopping = Task.FromResult(0);
        private LiveLease _previewLease;
        private int _previewRetries;

        public LiveView()
        {
            InitializeComponent();
            Channels.ItemsSource = _rows;
            Groups.Type = ContentType.Live;
            Groups.Opened += OnGroupOpened;
            Groups.GoRight += FocusChannels;
            _epgTimer.Tick += (s, e) => { _epgTimer.Stop(); ShowInfo(Channels.SelectedItem as ChannelRow); };
            _nowTimer.Tick += (s, e) => ShowInfo(Channels.SelectedItem as ChannelRow);
            App.Store.FavoritesChanged += OnFavoritesChanged;
            App.Store.SettingsChanged += OnSettingsChanged;
            App.Recordings.Changed += OnRecordingsChanged;
            UpdatePreviewHint();
        }

        // ---------------------------------------------------------------- IView

        public void OnShown()
        {
            if (!_loaded) { _loaded = true; Groups.Load(); }
            _nowTimer.Start();
        }

        public void OnHidden()
        {
            _nowTimer.Stop();
            StopPreview();
        }

        public void FocusStart()
        {
            if (_rows.Count > 0) FocusChannels(); else Groups.FocusList();
        }

        public void Reload()
        {
            StopPreview();
            _rows.Clear();
            if (_loaded) Groups.Load();
        }

        /// <summary>Back on Live TV first stops the preview; returns false when there was nothing to stop.</summary>
        public bool HandleBack()
        {
            if (_previewRow != null || _previewChannel != null) { StopPreview(); return true; }
            return false;
        }

        // ---------------------------------------------------------------- groups and channels

        private async void OnGroupOpened(GroupRow row)
        {
            _group = row;
            var id = ++_groupLoadId;
            _rows.Clear();
            var multi = App.Store.Accounts.Count > 1;
            GroupTitle.Text = row.Kind == GroupKind.Favorites ? "★ Favorites"
                : row.Category.Name + (multi ? "  ·  " + row.Account.DisplayName : "");
            Count.Text = "";
            SetStatus("Loading channels…");
            List<LiveChannel> list;
            try
            {
                if (row.Kind == GroupKind.Favorites)
                    list = App.Store.FavoritesOf(ItemType.Live).Select(f => f.ToChannel()).ToList();
                else
                    list = await App.Catalog.Channels(row.Category);
            }
            catch (Exception e)
            {
                if (id == _groupLoadId) SetStatus("Couldn't load this group: " + e.Message + "\nPress F5 to try again.");
                return;
            }
            if (id != _groupLoadId) return;

            var favs = new HashSet<string>(App.Store.FavoritesOf(ItemType.Live).Select(f => f.Key));
            var logos = App.Store.Settings.LiveLogos ? Visibility.Visible : Visibility.Collapsed;
            var recording = new HashSet<string>(App.Recordings.RecordingKeys);
            foreach (var c in list)
            {
                var acc = multi ? App.Store.Account(c.AccountId) : null;
                _rows.Add(new ChannelRow
                {
                    Channel = c,
                    AccountTag = acc != null ? acc.DisplayName : null,
                    LogoVisibility = logos,
                    IsFavorite = favs.Contains(c.Key),
                    IsPlaying = _previewChannel != null && _previewChannel.Key == c.Key,
                    IsRecording = recording.Contains(c.Key),
                });
            }
            Count.Text = list.Count + (list.Count == 1 ? " channel" : " channels");
            SetStatus(list.Count == 0 ? (row.Kind == GroupKind.Favorites ? "No favorite channels yet. Press F on a channel to add it." : "No channels in this group.") : null);
            if (_rows.Count > 0)
            {
                Channels.SelectedIndex = 0;
                Channels.ScrollIntoView(_rows[0]);
            }
        }

        private void SetStatus(string text)
        {
            ListStatus.Text = text ?? "";
            ListStatus.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
        }

        private void FocusChannels()
        {
            if (_rows.Count == 0) return;
            var row = Channels.SelectedItem as ChannelRow ?? _rows[0];
            Channels.SelectedItem = row;
            Channels.ScrollIntoView(row);
            Channels.UpdateLayout();
            var c = Channels.ItemContainerGenerator.ContainerFromItem(row) as ListBoxItem;
            if (c != null) c.Focus(); else Channels.Focus();
        }

        private void OnChannelSelected(object sender, SelectionChangedEventArgs e)
        {
            _epgTimer.Stop();
            _epgTimer.Start();
        }

        private void OnChannelKeyDown(object sender, KeyEventArgs e)
        {
            var row = Channels.SelectedItem as ChannelRow;
            switch (e.Key)
            {
                case Key.Enter:
                    e.Handled = true;
                    Activate(row);
                    break;
                case Key.F:
                    e.Handled = true;
                    ToggleFavorite(row);
                    break;
                case Key.R:
                    e.Handled = true;
                    ToggleRecord(row);
                    break;
                case Key.Left:
                    e.Handled = true;
                    Groups.FocusList();
                    break;
            }
        }

        private void OnChannelClick(object sender, MouseButtonEventArgs e)
        {
            // A single click only selects; double-click opens full screen.
        }

        private void OnChannelDoubleClick(object sender, MouseButtonEventArgs e)
        {
            var item = ItemsControl.ContainerFromElement(Channels, e.OriginalSource as DependencyObject) as ListBoxItem;
            if (item == null) return;
            e.Handled = true;
            OpenFull(item.DataContext as ChannelRow);
        }

        private void ToggleFavorite(ChannelRow row)
        {
            if (row == null) return;
            var now = App.Store.ToggleFavorite(SavedItem.Of(row.Channel));
            row.IsFavorite = now;
            App.Home.Toast(now ? "Added to favorites: " + row.Name : "Removed from favorites: " + row.Name);
        }

        private void OnFavoritesChanged()
        {
            Groups.RefreshFavorites();
            var favs = new HashSet<string>(App.Store.FavoritesOf(ItemType.Live).Select(f => f.Key));
            foreach (var r in _rows) r.IsFavorite = favs.Contains(r.Channel.Key);
            if (_group != null && _group.Kind == GroupKind.Favorites)
            {
                // Keep the Favorites list in step (a channel was removed or added elsewhere).
                var gone = _rows.Where(r => !r.IsFavorite).ToList();
                foreach (var r in gone) _rows.Remove(r);
                Count.Text = _rows.Count + (_rows.Count == 1 ? " channel" : " channels");
            }
        }

        private void OnSettingsChanged()
        {
            var logos = App.Store.Settings.LiveLogos ? Visibility.Visible : Visibility.Collapsed;
            if (_rows.Count > 0 && _rows[0].LogoVisibility != logos && _group != null) OnGroupOpened(_group);
            UpdatePreviewHint();
            if (!App.Store.Settings.LivePreview) StopPreview();
        }

        // ---------------------------------------------------------------- programme guide

        private async void ShowInfo(ChannelRow row)
        {
            if (_epgCts != null) _epgCts.Cancel();
            if (row == null)
            {
                ChName.Text = ""; NowTitle.Text = ""; NowDesc.Text = ""; NowBar.Visibility = Visibility.Collapsed; Upcoming.ItemsSource = null;
                return;
            }
            var ch = row.Channel;
            ChName.Text = row.Name;
            var cts = new CancellationTokenSource();
            _epgCts = cts;
            var cached = App.Catalog.EpgIfLoaded(ch);
            if (cached != null) FillGuide(cached);
            else
            {
                NowTitle.Text = "Loading guide…";
                NowDesc.Text = "";
                NowBar.Visibility = Visibility.Collapsed;
                Upcoming.ItemsSource = null;
            }
            List<EpgEntry> list;
            try
            {
                list = await App.Catalog.Epg(ch, cts.Token);
            }
            catch (Exception)
            {
                if (cts.IsCancellationRequested) return;
                list = new List<EpgEntry>();
            }
            if (cts.IsCancellationRequested || Channels.SelectedItem != row) return;
            FillGuide(list);
        }

        private void FillGuide(List<EpgEntry> list)
        {
            var nowMs = XtreamApi.NowMs();
            EpgEntry now, next;
            XtreamApi.NowNext(list, nowMs, out now, out next);
            if (now == null && next == null)
            {
                NowTitle.Text = "No programme guide for this channel.";
                NowTitle.Foreground = (System.Windows.Media.Brush)FindResource("DimText");
                NowDesc.Text = "";
                NowBar.Visibility = Visibility.Collapsed;
                Upcoming.ItemsSource = null;
                return;
            }
            NowTitle.Foreground = (System.Windows.Media.Brush)FindResource("Accent");
            if (now != null)
            {
                NowTitle.Text = now.Title;
                NowTime.Text = now.TimeRange;
                NowProgress.Value = now.Progress(nowMs);
                NowBar.Visibility = Visibility.Visible;
                NowDesc.Text = now.Description;
            }
            else
            {
                NowTitle.Text = "Nothing on air now";
                NowBar.Visibility = Visibility.Collapsed;
                NowDesc.Text = "";
            }
            var after = now != null ? now.End : nowMs;
            Upcoming.ItemsSource = list.Where(x => x.Start >= after).Take(6).ToList();
        }

        // ---------------------------------------------------------------- playing

        private void UpdatePreviewHint()
        {
            PreviewHint.Text = App.Vlc == null ? "The video engine didn't start — see Settings › About."
                : App.Store.Settings.LivePreview ? "Enter: preview here · Enter again: full screen"
                : "Enter: full screen (preview is off in Settings)";
        }

        private void Activate(ChannelRow row)
        {
            if (row == null) return;
            if (App.Vlc == null) { App.Home.Toast("The video engine (VLC) didn't start, so videos can't play."); return; }
            if (!App.Store.Settings.LivePreview) { OpenFull(row); return; }
            if (_previewChannel != null && _previewChannel.Key == row.Channel.Key) OpenFull(row);
            else StartPreview(row.Channel);
        }

        private void EnsurePreviewPlayer()
        {
            if (_preview != null) return;
            _preview = new MediaPlayer(App.Vlc);
            _preview.EncounteredError += (s, e) => Dispatcher.BeginInvoke(new Action(OnPreviewStopped));
            _preview.EndReached += (s, e) => Dispatcher.BeginInvoke(new Action(OnPreviewStopped));
            _preview.Playing += (s, e) => Dispatcher.BeginInvoke(new Action(() => { _previewRetries = 0; UpdatePreviewStatus(); }));
        }

        private bool PreviewUsesBuffer
        {
            get { var s = App.Store.Settings; return s.UseDiskBuffer && s.LiveFormat != "m3u8"; }
        }

        private async void StartPreview(LiveChannel ch)
        {
            if (_playerOpen) return;
            if (!Vpn.Allow(Window.GetWindow(this))) return;
            if (!ConnectionGuard.Allow(ch.AccountId, ch.Key, Window.GetWindow(this))) return;
            await _stopping;
            // A different channel: close the old stream first (the account may allow one connection).
            if (_previewLease != null && _previewLease.Relay.Key != ch.Key) ReleasePreviewLease();
            Video.Visibility = Visibility.Visible;
            PreviewIdle.Visibility = Visibility.Collapsed;
            Video.UpdateLayout();
            EnsurePreviewPlayer();
            // Link the player to the video surface again: the surface is recreated when the tab is shown again.
            Video.MediaPlayer = null;
            Video.MediaPlayer = _preview;
            MarkPlaying(ch);
            _previewRetries = 0;
            PlayPreviewMedia(ch);
        }

        private void PlayPreviewMedia(LiveChannel ch)
        {
            try
            {
                string url;
                var local = PreviewUsesBuffer;
                if (local)
                {
                    if (_previewLease == null)
                    {
                        _previewLease = App.Hub.OpenLive(ch.Key, App.Catalog.ChannelUrl(ch));
                        _previewLease.Relay.Changed += OnPreviewRelayChanged;
                    }
                    url = _previewLease.Url;
                }
                else url = App.Catalog.ChannelUrl(ch);
                UpdatePreviewStatus();
                using (var media = Playback.MakeMedia(url, true, local))
                    _preview.Play(media);
            }
            catch (Exception e)
            {
                App.Log(e);
                StopPreview();
                PreviewHint.Text = "Couldn't play: " + e.Message;
            }
        }

        /// <summary>Preview stopped by itself: the watchdog starts it again (it never gives up while you watch).</summary>
        private async void OnPreviewStopped()
        {
            var ch = _previewChannel;
            if (ch == null || _playerOpen) return;
            _previewRetries++;
            UpdatePreviewStatus();
            await Task.Delay(Math.Min(30000, 1000 * (1 << Math.Min(5, _previewRetries - 1))));
            if (_previewChannel != ch || _playerOpen) return;
            PlayPreviewMedia(ch);
        }

        private void OnPreviewRelayChanged(LiveRelay r)
        {
            Dispatcher.BeginInvoke(new Action(UpdatePreviewStatus));
        }

        private void UpdatePreviewStatus()
        {
            string text = null;
            var r = _previewLease != null ? _previewLease.Relay : null;
            if (_previewChannel == null) text = null;
            else if (r != null && r.State == RelayState.Reconnecting)
                text = "⟳ " + (r.Message ?? "Connection lost.") + "  Reconnecting… (" + r.Attempt + ")";
            else if (r != null && !r.EverReceived) text = "Connecting…";
            else if (_previewRetries > 0) text = "⟳ Restarting the preview… (" + _previewRetries + ")";
            PreviewStatus.Text = text ?? "";
            PreviewStatus.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
        }

        private void ReleasePreviewLease()
        {
            if (_previewLease == null) return;
            _previewLease.Relay.Changed -= OnPreviewRelayChanged;
            _previewLease.Dispose();
            _previewLease = null;
        }

        private void MarkPlaying(LiveChannel ch)
        {
            _previewChannel = ch;
            foreach (var r in _rows) r.IsPlaying = ch != null && r.Channel.Key == ch.Key;
            _previewRow = ch == null ? null : _rows.FirstOrDefault(r => r.Channel.Key == ch.Key);
        }

        /// <summary>Stops the preview (and its connection unless keepStream, used when going full screen).</summary>
        private void StopPreview(bool keepStream = false)
        {
            MarkPlaying(null);
            Video.Visibility = Visibility.Hidden;
            PreviewIdle.Visibility = Visibility.Visible;
            UpdatePreviewHint();
            UpdatePreviewStatus();
            if (!keepStream) ReleasePreviewLease();
            var mp = _preview;
            if (mp != null && (mp.IsPlaying || mp.State == VLCState.Opening || mp.State == VLCState.Buffering || mp.State == VLCState.Paused))
                _stopping = Task.Run(() => { try { mp.Stop(); } catch { } });
        }

        private async void OpenFull(ChannelRow row)
        {
            if (row == null || _playerOpen) return;
            if (App.Vlc == null) { App.Home.Toast("The video engine (VLC) didn't start, so videos can't play."); return; }
            var index = _rows.IndexOf(row);
            if (index < 0) return;
            var resume = App.Store.Settings.LivePreview;
            _playerOpen = true;
            // The same channel keeps its stream: the player joins it (no new connection, no restart).
            var handoff = _previewLease != null && _previewLease.Relay.Key == row.Channel.Key && ExternalPlayers.ChosenFor("live") == null ? _previewLease : null;
            if (handoff != null) { handoff.Relay.Changed -= OnPreviewRelayChanged; _previewLease = null; }
            StopPreview();
            await _stopping;
            var items = _rows.Select(r => PlayItem.Of(r.Channel)).ToList();
            int last;
            try
            {
                var play = PlayerWindow.Play(Window.GetWindow(this), items, index);
                if (handoff != null)
                {
                    await Task.Delay(3000);
                    handoff.Dispose();
                    handoff = null;
                }
                last = await play;
            }
            finally
            {
                if (handoff != null) handoff.Dispose();
                _playerOpen = false;
            }
            if (last >= 0 && last < _rows.Count)
            {
                var back = _rows[last];
                Channels.SelectedItem = back;
                Channels.ScrollIntoView(back);
                if (resume && IsVisible) StartPreview(back.Channel);
                FocusChannels();
            }
        }

        // ---------------------------------------------------------------- recording

        private void ToggleRecord(ChannelRow row)
        {
            if (row == null) return;
            var ch = row.Channel;
            if (App.Recordings.IsRecording(ch.Key))
            {
                App.Recordings.Stop(ch.Key);
                App.Home.Toast("Recording saved: Downloads › NovaIPTV › Recordings › " + ch.Name);
                return;
            }
            if (App.Store.Settings.LiveFormat == "m3u8") { App.Home.Toast("Recording needs the MPEG-TS live format (Settings › Playback)."); return; }
            // One-connection account already busy with another channel (the preview)?
            if (App.Catalog.MaxConnections(ch.AccountId) == 1 && _previewChannel != null &&
                _previewChannel.AccountId == ch.AccountId && _previewChannel.Key != ch.Key)
            {
                var ok = MessageBox.Show(Window.GetWindow(this),
                    "Your account allows only one connection and it is showing \"" + _previewChannel.Name + "\".\n\nStop the preview and record \"" + ch.Name + "\"?",
                    "Nova IPTV", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes;
                if (!ok) return;
                StopPreview();
            }
            try
            {
                App.Recordings.Start(ch, App.Catalog.ChannelUrl(ch));
                App.Home.Toast("● Recording " + ch.Name + " — R again to stop. Saved in Downloads › NovaIPTV › Recordings");
            }
            catch (Exception e)
            {
                App.Log(e);
                App.Home.Toast("Couldn't start recording: " + e.Message);
            }
        }

        private void OnRecordingsChanged()
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                var keys = new HashSet<string>(App.Recordings.RecordingKeys);
                foreach (var r in _rows) r.IsRecording = keys.Contains(r.Channel.Key);
            }));
        }

        private void OnPreviewSize(object sender, SizeChangedEventArgs e)
        {
            var h = Math.Round(PreviewBox.ActualWidth * 9 / 16);
            if (Math.Abs(PreviewBox.Height - h) > 1 || double.IsNaN(PreviewBox.Height)) PreviewBox.Height = h;
        }
    }
}
