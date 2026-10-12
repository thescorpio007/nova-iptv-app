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
    public partial class LiveView : UserControl, IView, IReloadable, IBackHandler, IZoneActions
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
        private bool _edit;
        private ChannelRow _moving;
        private List<LiveChannel> _groupList = new List<LiveChannel>();
        private CancellationTokenSource _nowCts;

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
            App.Store.OrgChanged += () => Dispatcher.BeginInvoke(new Action(() => { if (!_edit) Groups.Build(); }));
            UpdatePreviewHint();
            UpdateSortText();
            Channels.ContextMenu = new ContextMenu();
            Channels.ContextMenuOpening += OnChannelMenu;
        }

        // ---------------------------------------------------------------- sort and edit

        private void UpdateSortText()
        {
            SortText.Text = "⇅ " + Organize.SortLabel("live", App.Store.Settings.SortLive);
        }

        public void ChooseSort()
        {
            var choices = Organize.SortChoices("live");
            var current = choices.FindIndex(c => c.Item1 == App.Store.Settings.SortLive);
            var i = NovaDialog.Choose(Window.GetWindow(this), "Sort Live TV", "“My order” keeps the order you set with ✎ Edit.", choices.Select(c => c.Item2).ToList(), current);
            if (i < 0) return;
            var st = App.Store.Settings.Copy();
            st.SortLive = choices[i].Item1;
            App.Store.SaveSettings(st);
            UpdateSortText();
            if (_group != null) Fill(_group, null);
        }

        public void ToggleEdit()
        {
            if (!_edit && !PinGate.Unlock(Window.GetWindow(this), "Your PIN protects changes to the lists.")) return;
            _edit = !_edit;
            if (_moving != null) { _moving.Moving = false; _moving = null; }
            EditText.Text = _edit ? "✓ Done" : "✎ Edit";
            EditText.Foreground = (System.Windows.Media.Brush)FindResource(_edit ? "StarBrush" : "SubText");
            EditHint.Visibility = _edit ? Visibility.Visible : Visibility.Collapsed;
            Groups.EditMode = _edit;
            if (_group != null) Fill(_group, Channels.SelectedItem is ChannelRow ? ((ChannelRow)Channels.SelectedItem).Channel.Key : null);
            App.Home.Toast(_edit ? "Edit: hide and reorder groups and channels — Esc when done" : "Changes saved");
        }

        private void OnSort(object sender, RoutedEventArgs e) { ChooseSort(); }
        private void OnEdit(object sender, RoutedEventArgs e) { ToggleEdit(); }

        private void MoveChannel(ChannelRow row, int delta)
        {
            if (row == null || _group == null) return;
            if (_group.Kind == GroupKind.Favorites)
            {
                // Favorites keep their own order.
                var favs = App.Store.FavoritesOf(ItemType.Live);
                var keys = favs.Select(f => f.Key).ToList();
                var moved = delta == int.MinValue ? Nova.Core.Org.MoveTo(keys, row.Channel.Key, true) : delta == int.MaxValue ? Nova.Core.Org.MoveTo(keys, row.Channel.Key, false) : Nova.Core.Org.Move(keys, row.Channel.Key, delta);
                App.Store.ReorderFavorites(ItemType.Live, moved.Select(k => favs.First(f => f.Key == k)).ToList());
            }
            else if (_group.Kind == GroupKind.Group)
            {
                Organize.MoveItem("live", _group.Key, _rows.Select(r => r.Channel.Key).ToList(), row.Channel.Key, delta);
            }
            else return;
            Fill(_group, row.Channel.Key);
        }

        private void ToggleChannelHidden(ChannelRow row)
        {
            if (row == null || _group == null || _group.Kind != GroupKind.Group) return;
            var hiding = !Organize.IsItemHidden("live", row.Channel.Key);
            var index = _rows.IndexOf(row);
            Organize.ToggleItemHidden("live", row.Channel.Key, row.Name);
            App.Home.Toast(hiding ? "Hidden: " + row.Name : "Shown again: " + row.Name);
            Fill(_group, Organize.ShowsHidden(_edit) ? row.Channel.Key : null, index);
        }

        private void OnChannelMenu(object sender, ContextMenuEventArgs e)
        {
            var row = Channels.SelectedItem as ChannelRow;
            var menu = Channels.ContextMenu;
            menu.Items.Clear();
            if (row == null) { e.Handled = true; return; }
            Action<string, Action> add = (text, act) => { var mi = new MenuItem { Header = text }; mi.Click += (s, a) => act(); menu.Items.Add(mi); };
            add("▶ Play full screen", () => OpenFull(row));
            add(row.IsFavorite ? "Remove from favorites" : "Add to favorites", () => ToggleFavorite(row));
            add(App.Recordings.IsRecording(row.Channel.Key) ? "■ Stop recording" : "● Record", () => ToggleRecord(row));
            if (row.Channel.HasArchive) add("⟲ Catch-up", () => ShowCatchup(row));
            if (_edit || PinGate.IsUnlocked)
            {
                menu.Items.Add(new Separator());
                if (_group != null && _group.Kind == GroupKind.Group) add(Organize.IsItemHidden("live", row.Channel.Key) ? "Show again" : "Hide this channel", () => { if (PinGate.Unlock(Window.GetWindow(this), "Your PIN protects hiding channels.")) ToggleChannelHidden(row); });
                if (_group != null && _group.Kind != GroupKind.New)
                {
                    add("Move up", () => { if (PinGate.Unlock(Window.GetWindow(this), "Your PIN protects changes.")) MoveChannel(row, -1); });
                    add("Move down", () => { if (PinGate.Unlock(Window.GetWindow(this), "Your PIN protects changes.")) MoveChannel(row, 1); });
                    add("Move to the top", () => { if (PinGate.Unlock(Window.GetWindow(this), "Your PIN protects changes.")) MoveChannel(row, int.MinValue); });
                    add("Move to the bottom", () => { if (PinGate.Unlock(Window.GetWindow(this), "Your PIN protects changes.")) MoveChannel(row, int.MaxValue); });
                }
            }
        }

        // ---------------------------------------------------------------- catch-up

        private void OnCatchupClick(object sender, RoutedEventArgs e) { ShowCatchup(Channels.SelectedItem as ChannelRow); }

        private async void ShowCatchup(ChannelRow row)
        {
            if (row == null) return;
            if (!row.Channel.HasArchive) { App.Home.Toast("This channel has no catch-up on your server."); return; }
            App.Home.Toast("Loading past programmes…");
            List<CatchupEntry> list;
            try { list = await App.Catalog.Catchup(row.Channel, CancellationToken.None); }
            catch (Exception ex) { App.Home.Toast("Couldn't load catch-up: " + ex.Message); return; }
            if (list.Count == 0) { NovaDialog.Info(Window.GetWindow(this), "Catch-up · " + row.Name, "No past programmes are available for this channel."); return; }
            var shown = list.Take(60).ToList();
            var i = NovaDialog.Choose(Window.GetWindow(this), "Catch-up · " + row.Name, "Past programmes the server keeps (newest first).",
                shown.Select(x => x.When + "   " + x.Title).ToList(), 0);
            if (i < 0) return;
            var entry = shown[i];
            StopPreview();
            await _stopping;
            var item = new PlayItem
            {
                Title = row.Name + "  ·  " + entry.Title,
                Subtitle = entry.When,
                Url = App.Catalog.CatchupUrl(row.Channel, entry),
                AccountId = row.Channel.AccountId,
                Kind = "catchup",
                Ext = "ts",
            };
            await PlayerWindow.Play(Window.GetWindow(this), new List<PlayItem> { item }, 0);
            FocusChannels();
        }

        // ---------------------------------------------------------------- programme on air under each channel

        private async void FillNowLines(List<ChannelRow> rows)
        {
            if (_nowCts != null) _nowCts.Cancel();
            if (!App.Store.Settings.LiveNowLine) return;
            var cts = new CancellationTokenSource();
            _nowCts = cts;
            foreach (var r in rows.Take(80))
            {
                if (cts.IsCancellationRequested) return;
                try
                {
                    var list = App.Catalog.EpgIfLoaded(r.Channel) ?? await App.Catalog.Epg(r.Channel, cts.Token);
                    EpgEntry now, next;
                    XtreamApi.NowNext(list, XtreamApi.NowMs(), out now, out next);
                    if (now != null) r.NowText = now.Title;
                }
                catch { }
            }
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
            if (_edit) { ToggleEdit(); return true; }
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
            _groupList = list;
            Fill(row, null);
        }

        /// <summary>Shows the group's channels in your order / sort, hidden ones only in edit mode.</summary>
        private void Fill(GroupRow row, string keepKey, int keepIndex = -1)
        {
            if (row.Kind == GroupKind.Favorites) _groupList = App.Store.FavoritesOf(ItemType.Live).Select(f => f.ToChannel()).ToList();
            var multi = App.Store.Accounts.Count > 1;
            var shown = Organize.Items("live", row.Key, _groupList, c => c.Key, _edit, App.Store.Settings.SortLive, Organize.Info);
            var favs = new HashSet<string>(App.Store.FavoritesOf(ItemType.Live).Select(f => f.Key));
            var logos = App.Store.Settings.LiveLogos ? Visibility.Visible : Visibility.Collapsed;
            var recording = new HashSet<string>(App.Recordings.RecordingKeys);
            var size = App.Store.Settings.LiveRowSize;
            var height = size == "compact" ? 30 : size == "large" ? 54 : 40;
            var movingKey = _moving != null ? _moving.Channel.Key : null;
            _rows.Clear();
            foreach (var c in shown)
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
                    Hidden = Organize.IsItemHidden("live", c.Key),
                    RowHeight = height,
                    Moving = movingKey == c.Key,
                });
            }
            _moving = _rows.FirstOrDefault(r => r.Moving);
            var hiddenCount = _groupList.Count - shown.Count;
            Count.Text = shown.Count + (shown.Count == 1 ? " channel" : " channels") + (hiddenCount > 0 && !_edit ? "  ·  " + hiddenCount + " hidden" : "");
            SetStatus(shown.Count == 0 ? (row.Kind == GroupKind.Favorites ? "No favorite channels yet. Press F on a channel to add it." : "No channels in this group.") : null);
            if (_rows.Count > 0)
            {
                var target = keepKey != null ? _rows.FirstOrDefault(r => r.Channel.Key == keepKey) : null;
                if (target == null && keepIndex >= 0) target = _rows[Math.Min(keepIndex, _rows.Count - 1)];
                if (target == null) target = _rows[0];
                var hadFocus = Channels.IsKeyboardFocusWithin;
                Channels.SelectedItem = target;
                Channels.ScrollIntoView(target);
                if (hadFocus || keepKey != null || keepIndex >= 0) Dispatcher.BeginInvoke(new Action(FocusChannels), DispatcherPriority.Loaded);
            }
            FillNowLines(_rows.ToList());
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
            if (_edit && row != null)
            {
                if (_moving == row)
                {
                    if (e.Key == Key.Up || e.Key == Key.Down) { e.Handled = true; MoveChannel(row, e.Key == Key.Up ? -1 : 1); return; }
                    if (e.Key == Key.Home || e.Key == Key.End) { e.Handled = true; MoveChannel(row, e.Key == Key.Home ? int.MinValue : int.MaxValue); return; }
                    if (e.Key == Key.Left || e.Key == Key.Right) { e.Handled = true; return; }
                }
                if (e.Key == Key.Enter)
                {
                    e.Handled = true;
                    if (_group == null || _group.Kind == GroupKind.New) return;
                    if (_moving == row) { row.Moving = false; _moving = null; }
                    else { if (_moving != null) _moving.Moving = false; _moving = row; row.Moving = true; }
                    return;
                }
                if (e.Key == Key.H || e.Key == Key.Delete) { e.Handled = true; ToggleChannelHidden(row); return; }
            }
            switch (e.Key)
            {
                case Key.Enter:
                    e.Handled = true;
                    Activate(row);
                    break;
                case Key.C:
                    e.Handled = true;
                    ShowCatchup(row);
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
                Fill(_group, Channels.SelectedItem is ChannelRow ? ((ChannelRow)Channels.SelectedItem).Channel.Key : null);
        }

        private void OnSettingsChanged()
        {
            UpdateSortText();
            if (_group != null && !Channels.IsKeyboardFocusWithin) Fill(_group, Channels.SelectedItem is ChannelRow ? ((ChannelRow)Channels.SelectedItem).Channel.Key : null);
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
                CatchupBtn.Visibility = Visibility.Collapsed;
                return;
            }
            var ch = row.Channel;
            ChName.Text = row.Name;
            CatchupBtn.Visibility = ch.HasArchive ? Visibility.Visible : Visibility.Collapsed;
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
