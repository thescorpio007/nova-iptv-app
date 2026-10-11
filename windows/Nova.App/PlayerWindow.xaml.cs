using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using Nova.Core;
using Nova.Core.Streaming;

namespace Nova.App
{
    /// <summary>
    /// The player. Streams are downloaded to the disk buffer first and played from there; the watchdog
    /// reconnects for ever, dead channels are skipped, live TV can be paused, rewound and recorded,
    /// films can be downloaded (also while watching). Keyboard and air-mouse friendly.
    /// </summary>
    public partial class PlayerWindow : Window
    {
        private enum Source { Direct, LiveBuffer, FilmBuffer, LocalFile }

        private readonly List<PlayItem> _items;
        private readonly TaskCompletionSource<int> _done = new TaskCompletionSource<int>();
        private readonly DispatcherTimer _tick = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        private readonly DispatcherTimer _hide = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
        private readonly DispatcherTimer _osd = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.2) };
        private readonly DispatcherTimer _retry = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        private readonly DispatcherTimer _switch = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(450) };
        private MediaPlayer _mp;
        private int _index;
        private int _attempts;
        private int _skips;
        private int _lastDirection = 1;
        private DateTime _playingSince = DateTime.MaxValue;
        private bool _fullScreen;
        private bool _stopped;
        private bool _closing;
        private int _aspect;
        private Point _lastMouse;
        private Window _overlayWindow;
        private CancellationTokenSource _guideCts;
        private int _playId;

        private Source _source;
        private LiveLease _live;
        private VodLease _vod;
        private long _livePausedAt = -1;
        private long _lastTime = -1;
        private DateTime _lastTimeChange = DateTime.UtcNow;
        private long _resumeAt;

        private static readonly string[] AspectNames = { "Fit", "Fill (zoom)", "Stretch", "16:9", "4:3" };

        private PlayerWindow(List<PlayItem> items, int index)
        {
            InitializeComponent();
            _items = items;
            _index = Math.Max(0, Math.Min(index, items.Count - 1));
            _tick.Tick += (s, e) => OnTick();
            _hide.Tick += (s, e) => { _hide.Stop(); SetControlsVisible(false); };
            _osd.Tick += (s, e) => { _osd.Stop(); OsdBox.Visibility = Visibility.Collapsed; };
            _retry.Tick += (s, e) => { _retry.Stop(); Restart(); };
            _switch.Tick += (s, e) => { _switch.Stop(); PlayCurrent(true); };
            App.Recordings.Changed += OnRecordingsChanged;
        }

        /// <summary>Opens the player; finishes with the index of the last item played once the player has fully stopped.</summary>
        public static Task<int> Play(Window owner, List<PlayItem> items, int index)
        {
            if (items == null || items.Count == 0 || App.Vlc == null) return Task.FromResult(-1);
            var w = new PlayerWindow(items, index);
            if (owner != null && owner.IsVisible) w.Owner = owner;
            w.Show();
            w.Activate();
            return w._done.Task;
        }

        private PlayItem Current { get { return _items[_index]; } }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            SetFullScreen(App.Store.Settings.PlayerFullScreen);
            _mp = new MediaPlayer(App.Vlc);
            _mp.Playing += (s, a) => Ui(OnPlaying);
            _mp.EncounteredError += (s, a) => Ui(() => OnFailed(false));
            _mp.EndReached += (s, a) => Ui(() => OnFailed(true));
            _mp.Buffering += (s, a) => { var c = a.Cache; Ui(() => OnBuffering(c)); };
            _mp.Paused += (s, a) => Ui(() => PlayPauseButton.Content = "▶");
            Video.MediaPlayer = _mp;
            _tick.Start();
            ClockText.Text = DateTime.Now.ToString("HH:mm");
            PlayCurrent(true);
            Focus();
        }

        private void OnOverlayLoaded(object sender, RoutedEventArgs e)
        {
            // The overlay lives in its own see-through window above the video: keys pressed there count too.
            var w = GetWindow(Overlay);
            if (w != null && !ReferenceEquals(w, this) && _overlayWindow == null)
            {
                _overlayWindow = w;
                w.PreviewKeyDown += OnKey;
            }
        }

        private void Ui(Action a)
        {
            if (_closing) return;
            Dispatcher.BeginInvoke(new Action(() => { if (!_closing) a(); }));
        }

        // ---------------------------------------------------------------- choosing the source

        private bool BufferLive(PlayItem item)
        {
            var s = App.Store.Settings;
            return s.UseDiskBuffer && s.LiveFormat != "m3u8" && item.Channel != null;
        }

        /// <summary>Starts the current item: the disk buffer when possible, otherwise directly.</summary>
        private async void PlayCurrent(bool fresh)
        {
            if (_closing || _mp == null) return;
            var item = Current;
            var id = ++_playId;
            if (fresh) { _attempts = 0; _resumeAt = 0; }
            _playingSince = DateTime.MaxValue;
            _livePausedAt = -1;
            ShowHeader(item);
            SetControlsVisible(true);
            NetBox.Visibility = Visibility.Collapsed;

            // Close the streams of the previous item first: the account may allow only one connection.
            ReleaseStreamsExcept(item);

            var done = item.IsLive ? null : item.LocalPath ?? (item.Url != null ? App.Downloads.DoneFile(item.Url) : null);
            if (done == null && !ConnectionGuard.Allow(item.AccountId, item.Channel != null ? item.Channel.Key : null, this))
            {
                ShowStatus("Not playing: the recording keeps the account's only connection.\nEsc to close.");
                return;
            }

            if (item.IsLive)
            {
                if (BufferLive(item))
                {
                    if (_live == null)
                    {
                        _live = App.Hub.OpenLive(item.Channel.Key, item.Url);
                        _live.Relay.Changed += OnRelayChanged;
                        _live.Relay.Dead += OnRelayDead;
                    }
                    _source = Source.LiveBuffer;
                    ShowStatus(_live.Relay.EverReceived ? "Starting…" : "Connecting…");
                    UpdateRelayStatus();
                    StartMedia(_live.Url, true, true);
                }
                else
                {
                    _source = Source.Direct;
                    ShowStatus(_attempts > 0 ? "Reconnecting… (" + _attempts + ")" : "Connecting…");
                    StartMedia(item.Url, true, false);
                }
                return;
            }

            // Films and episodes.
            if (done != null)
            {
                _source = Source.LocalFile;
                ShowStatus(null);
                Osd(item.LocalPath != null ? "Playing from your PC" : "Playing your downloaded file");
                StartMedia(new Uri(done).AbsoluteUri, false, true);
                return;
            }
            if (!App.Store.Settings.UseDiskBuffer)
            {
                _source = Source.Direct;
                ShowStatus("Connecting…");
                StartMedia(item.Url, false, false);
                return;
            }
            if (_vod == null)
            {
                var dl = App.Downloads.Find(item.Url);
                var useDownload = item.DownloadWatch || (dl != null && dl.Status != DownloadStatus.Failed && !dl.IsDone);
                if (useDownload && dl != null)
                {
                    App.Downloads.Prioritize(dl.Id);
                    _vod = App.Hub.OpenVod(item.Url, item.Ext, dl.PartsFolder, true, item.AccountId);
                }
                else
                {
                    _vod = App.Hub.OpenVod(item.Url, item.Ext, null, false, item.AccountId);
                }
                _vod.File.Changed += OnVodChanged;
            }
            _source = Source.FilmBuffer;
            ShowStatus("Connecting…");
            var file = _vod.File;
            var ready = await Task.Run(() => file.WaitReady(25000));
            if (_closing || id != _playId) return;
            if (!ready)
            {
                // The server doesn't allow reading in parts: play directly (still watched by the watchdog).
                ReleaseVod();
                _source = Source.Direct;
                Osd("Playing directly (this server doesn't allow buffering)");
                StartMedia(item.Url, false, false);
                return;
            }
            StartMedia(_vod.Url, false, true);
        }

        private void StartMedia(string url, bool live, bool local)
        {
            _lastTime = -1;
            _lastTimeChange = DateTime.UtcNow;
            try
            {
                using (var media = Playback.MakeMedia(url, live, local))
                    _mp.Play(media);
                if (!live && _resumeAt > 0) { var at = _resumeAt; var id = _playId; var ignored = SeekWhenReady(at, id); }
            }
            catch (Exception e)
            {
                App.Log(e);
                ShowStatus("Couldn't open this stream: " + e.Message);
            }
        }

        /// <summary>Starts the same item again (after a failure or a frozen picture).</summary>
        private void Restart()
        {
            if (_closing || _mp == null) return;
            if (_source == Source.LiveBuffer && _live != null) { StartMedia(_live.Url, true, true); return; }
            if (_source == Source.FilmBuffer && _vod != null) { _resumeAt = Math.Max(_resumeAt, _mp.Time); StartMedia(_vod.Url, false, true); return; }
            if (!Current.IsLive) _resumeAt = Math.Max(_resumeAt, _mp.Time);
            PlayCurrent(false);
        }

        private void ShowHeader(PlayItem item)
        {
            TitleText.Text = item.Title;
            SubText.Text = item.Subtitle ?? "";
            SubText.Visibility = string.IsNullOrEmpty(item.Subtitle) ? Visibility.Collapsed : Visibility.Visible;
            SeekRow.Visibility = item.IsLive ? Visibility.Collapsed : Visibility.Visible;
            BackButton.Visibility = FwdButton.Visibility = item.IsLive ? Visibility.Collapsed : Visibility.Visible;
            var buffered = item.IsLive && BufferLive(item);
            LiveButton.Visibility = buffered ? Visibility.Visible : Visibility.Collapsed;
            RecButton.Visibility = item.IsLive ? Visibility.Visible : Visibility.Collapsed;
            DownloadButton.Visibility = !item.IsLive && (item.Movie != null || item.Episode != null) ? Visibility.Visible : Visibility.Collapsed;
            KeysHint.Text = item.IsLive
                ? (buffered ? "← → 30 s back / forward  ·  L live  ·  " : "") + (_items.Count > 1 ? "▲ ▼ channel" : "")
                : "← → 10 s  ·  Space pause  ·  ▲ ▼ volume" + (_items.Count > 1 ? "  ·  PgUp/PgDn episode" : "");
            GuideBox.Visibility = Visibility.Collapsed;
            LiveBadge.Visibility = Visibility.Collapsed;
            DiskText.Text = "";
            PlayPauseButton.Content = "❚❚";
            UpdateRecBadge();
            if (item.IsLive) LoadGuide(item);
        }

        // ---------------------------------------------------------------- streams

        private void ReleaseStreamsExcept(PlayItem item)
        {
            if (_live != null && (item.Channel == null || _live.Relay.Key != item.Channel.Key || !BufferLive(item))) ReleaseLive();
            if (_vod != null && (item.IsLive || _vod.File.Url != item.Url)) ReleaseVod();
        }

        private void ReleaseLive()
        {
            if (_live == null) return;
            _live.Relay.Changed -= OnRelayChanged;
            _live.Relay.Dead -= OnRelayDead;
            _live.Dispose();
            _live = null;
        }

        private void ReleaseVod()
        {
            if (_vod == null) return;
            _vod.File.Changed -= OnVodChanged;
            _vod.Dispose();
            _vod = null;
        }

        private void OnRelayChanged(LiveRelay r) { Ui(UpdateRelayStatus); }

        private void OnRelayDead(LiveRelay r)
        {
            Ui(() =>
            {
                if (_live == null || _live.Relay != r) return;
                var s = App.Store.Settings;
                if (s.AutoSkip && _items.Count > 1 && _skips < 15)
                {
                    _skips++;
                    Osd("No video on " + Current.Title + " — next channel");
                    Step(_lastDirection);
                }
                else
                {
                    ShowStatus("This channel isn't sending any video. Still trying…" + (_items.Count > 1 ? "\n▲ ▼ for another channel" : ""));
                }
            });
        }

        private void UpdateRelayStatus()
        {
            if (_live == null) { NetBox.Visibility = Visibility.Collapsed; return; }
            var r = _live.Relay;
            if (r.State == RelayState.Reconnecting)
            {
                var text = (r.Message ?? "Connection lost.") + "\nWatchdog: reconnecting… (attempt " + r.Attempt + ")";
                if (_mp != null && _mp.IsPlaying) { NetText.Text = text; NetBox.Visibility = Visibility.Visible; }
                else ShowStatus(text);
            }
            else
            {
                NetBox.Visibility = Visibility.Collapsed;
                if (StatusText.Text.Contains("Watchdog")) ShowStatus(_mp != null && _mp.IsPlaying ? null : "Starting…");
            }
        }

        private void OnVodChanged(VodFile f)
        {
            Ui(() =>
            {
                if (_vod == null || _vod.File != f) return;
                if (f.State == VodState.Reconnecting)
                {
                    NetText.Text = (f.Message ?? "Connection lost.") + "\nWatchdog: reconnecting… (attempt " + f.Attempt + ")";
                    NetBox.Visibility = Visibility.Visible;
                }
                else NetBox.Visibility = Visibility.Collapsed;
            });
        }

        // ---------------------------------------------------------------- VLC events

        private void OnPlaying()
        {
            ShowStatus(null);
            PlayPauseButton.Content = "❚❚";
            _playingSince = DateTime.Now;
            _attempts = 0;
            if (_live != null && _live.Relay.EverReceived) _skips = 0;
            ApplyAspect(false);
            if (!_hide.IsEnabled) _hide.Start();
        }

        private void OnBuffering(float cache)
        {
            if (_mp == null) return;
            if (cache < 100f && _playingSince != DateTime.MaxValue && _source == Source.Direct) ShowStatus("Buffering " + (int)cache + "%");
            else if (cache >= 100f && StatusText.Text.StartsWith("Buffering")) ShowStatus(null);
        }

        /// <summary>VLC stopped: live channels start again, films continue where they were or move on.</summary>
        private void OnFailed(bool ended)
        {
            var item = Current;
            if (_livePausedAt >= 0) return; // paused live TV: VLC was stopped on purpose

            if (!item.IsLive && ended && _mp.Length > 0 && (_mp.Time >= _mp.Length - 15000 || _mp.Position > 0.97f))
            {
                if (_index + 1 < _items.Count)
                {
                    ShowStatus("Next: " + _items[_index + 1].Title);
                    _index++;
                    _switch.Stop();
                    _switch.Interval = TimeSpan.FromSeconds(2);
                    _switch.Start();
                }
                else Close();
                return;
            }
            if (_source == Source.LocalFile && ended) { Close(); return; }

            // Watchdog: never give up; wait a little longer each time (1 s … 30 s).
            _attempts++;
            if (!item.IsLive && _mp.Time > 0) _resumeAt = _mp.Time;
            var wait = Math.Min(30, (int)Math.Pow(2, Math.Min(5, _attempts - 1)));
            if (_source == Source.LiveBuffer) wait = 1;
            if (_source == Source.Direct || !(_live != null && _live.Relay.State == RelayState.Reconnecting))
                ShowStatus((item.IsLive ? "Connection lost." : "The video stopped.") + "\nWatchdog: reconnecting… (attempt " + _attempts + ")");
            _retry.Stop();
            _retry.Interval = TimeSpan.FromSeconds(wait);
            _retry.Start();
            SetControlsVisible(true);
        }

        private async Task SeekWhenReady(long ms, int id)
        {
            for (int i = 0; i < 80; i++)
            {
                await Task.Delay(250);
                if (_closing || id != _playId || _mp == null) return;
                if (_mp.IsPlaying && _mp.IsSeekable && _mp.Length > 0) { _mp.Time = Math.Min(ms, _mp.Length - 3000); _resumeAt = 0; return; }
            }
        }

        private void ShowStatus(string text)
        {
            StatusText.Text = text ?? "";
            StatusBox.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
        }

        private void Osd(string text)
        {
            OsdText.Text = text;
            OsdBox.Visibility = Visibility.Visible;
            _osd.Stop();
            _osd.Start();
        }

        // ---------------------------------------------------------------- every half second

        private void OnTick()
        {
            if (_mp == null) return;
            ClockText.Text = DateTime.Now.ToString("HH:mm");
            var item = Current;

            // Frozen-picture watchdog: VLC says it plays but the time doesn't move.
            var t = _mp.Time;
            if (t != _lastTime) { _lastTime = t; _lastTimeChange = DateTime.UtcNow; }
            else if (_mp.State == VLCState.Playing && _livePausedAt < 0 &&
                     (DateTime.UtcNow - _lastTimeChange).TotalSeconds > App.Store.Settings.StallSeconds + 5 &&
                     !(_live != null && _live.Relay.State != RelayState.Playing))
            {
                _lastTimeChange = DateTime.UtcNow;
                Osd("The picture froze — watchdog restarts it");
                Restart();
                return;
            }

            if (item.IsLive) { UpdateLiveBadge(); UpdateRecBadge(); return; }
            var len = _mp.Length;
            if (len > 0 && !Seek.IsMouseCaptureWithin)
            {
                Seek.Maximum = len;
                Seek.Value = Math.Max(0, Math.Min(len, t));
            }
            PosText.Text = Playback.Time(t);
            LenText.Text = len > 0 ? Playback.Time(len) : "";
            if (_vod != null && _vod.File.Length > 0)
            {
                var pct = (int)(100.0 * _vod.File.Downloaded / _vod.File.Length);
                DiskText.Text = (_vod.File.Full ? "Downloaded " : "On disk ") + pct + "%";
            }
            else DiskText.Text = _source == Source.LocalFile ? "From your downloads" : "";
        }

        // ---------------------------------------------------------------- live: pause, rewind, back to live

        private double LiveBytesPerSecond
        {
            get { var bps = _live != null ? _live.Relay.BytesPerSecond : 0; return bps > 0 ? bps : 500 * 1024; }
        }

        /// <summary>Approximate stream position on screen (what VLC has read, minus what it keeps in memory).</summary>
        private long LiveScreenOffset()
        {
            var r = _live.Relay;
            var read = r.ReaderPosition >= 0 ? r.ReaderPosition : r.End;
            return Math.Max(r.Start, read - (long)(LiveBytesPerSecond * 1.5));
        }

        private void UpdateLiveBadge()
        {
            if (_source != Source.LiveBuffer || _live == null || !_live.Relay.EverReceived) { LiveBadge.Visibility = Visibility.Collapsed; return; }
            var r = _live.Relay;
            var at = _livePausedAt >= 0 ? _livePausedAt : LiveScreenOffset();
            var behind = (r.End - at) / LiveBytesPerSecond;
            var cushion = App.Store.Settings.CushionSeconds;
            LiveBadge.Visibility = Visibility.Visible;
            if (behind <= cushion + 8 && _livePausedAt < 0)
            {
                LiveText.Text = "● LIVE";
                LiveText.Foreground = (System.Windows.Media.Brush)FindResource("Accent");
            }
            else
            {
                LiveText.Text = (_livePausedAt >= 0 ? "❚❚ " : "⏪ ") + Playback.Time((long)(behind * 1000)) + " behind live  ·  L = live";
                LiveText.Foreground = (System.Windows.Media.Brush)FindResource("StarBrush");
            }
        }

        private void LiveJump(double seconds)
        {
            if (_source != Source.LiveBuffer || _live == null) return;
            var r = _live.Relay;
            var from = _livePausedAt >= 0 ? _livePausedAt : LiveScreenOffset();
            var target = from + (long)(seconds * LiveBytesPerSecond);
            var liveEdge = r.End - (long)(LiveBytesPerSecond * App.Store.Settings.CushionSeconds);
            if (target >= liveEdge) { BackToLive(); return; }
            if (target <= r.Start) { target = r.Start; Osd("Start of the buffer (" + Playback.Time((long)((r.End - r.Start) / LiveBytesPerSecond * 1000)) + " kept)"); }
            else Osd(seconds < 0 ? "⏪ 30 s" : "⏩ 30 s");
            _livePausedAt = -1;
            StartMedia(_live.UrlAt(target), true, true);
            KeepControls();
        }

        private void BackToLive()
        {
            if (_source != Source.LiveBuffer || _live == null) return;
            _livePausedAt = -1;
            Osd("● Back to live");
            StartMedia(_live.Url, true, true);
            KeepControls();
        }

        private void OnBackToLive(object sender, RoutedEventArgs e) { BackToLive(); }

        // ---------------------------------------------------------------- recording and downloads

        private void ToggleRecord()
        {
            var item = Current;
            if (!item.IsLive || item.Channel == null) return;
            if (App.Store.Settings.LiveFormat == "m3u8") { Osd("Recording needs the MPEG-TS live format (Settings › Playback)"); return; }
            var key = item.Channel.Key;
            if (App.Recordings.IsRecording(key))
            {
                App.Recordings.Stop(key);
                Osd("Recording saved in Downloads › NovaIPTV › Recordings");
            }
            else
            {
                try
                {
                    App.Recordings.Start(item.Channel, item.Url);
                    Osd("● Recording " + item.Channel.Name + " (same connection)");
                }
                catch (Exception e)
                {
                    App.Log(e);
                    Osd("Couldn't start recording: " + e.Message);
                }
            }
            UpdateRecBadge();
        }

        private void OnRecordingsChanged() { Ui(UpdateRecBadge); }

        private void UpdateRecBadge()
        {
            var item = Current;
            var rec = item.IsLive && item.Channel != null && App.Recordings.IsRecording(item.Channel.Key);
            RecBadge.Visibility = rec ? Visibility.Visible : Visibility.Collapsed;
            RecButton.Content = rec ? "■ Stop rec (R)" : "● Rec (R)";
            if (rec)
            {
                var relay = App.Hub.FindLive(item.Channel.Key);
                var since = relay != null && relay.IsRecording ? relay.RecordingSince : DateTime.Now;
                RecText.Text = "● REC " + Playback.Time((long)(DateTime.Now - since).TotalMilliseconds);
            }
        }

        private void DownloadCurrent()
        {
            var item = Current;
            if (item.IsLive) return;
            var existing = App.Downloads.Find(item.Url);
            if (existing != null && existing.IsDone && File.Exists(existing.FinalPath)) { Osd("Already downloaded"); return; }
            var d = item.StartDownload();
            if (d == null) return;
            if (_vod != null && _vod.File.Url == item.Url && _vod.File.Full) { Osd("Already downloading — it continues while you watch"); return; }
            Osd(App.Catalog.MaxConnections(item.AccountId) == 1
                ? "⬇ Added to Downloads — starts when you close the player (1-connection account)"
                : "⬇ Downloading to " + Path.GetDirectoryName(d.FinalPath));
        }

        private void OnRec(object sender, RoutedEventArgs e) { ToggleRecord(); }
        private void OnDownload(object sender, RoutedEventArgs e) { DownloadCurrent(); }

        // ---------------------------------------------------------------- live guide

        private async void LoadGuide(PlayItem item)
        {
            if (_guideCts != null) _guideCts.Cancel();
            if (item.Channel == null) return;
            var cts = new CancellationTokenSource();
            _guideCts = cts;
            List<EpgEntry> list;
            try { list = await App.Catalog.Epg(item.Channel, cts.Token); }
            catch { return; }
            if (cts.IsCancellationRequested || _closing || !ReferenceEquals(item, Current)) return;
            var nowMs = XtreamApi.NowMs();
            EpgEntry now, next;
            XtreamApi.NowNext(list, nowMs, out now, out next);
            if (now == null && next == null) { GuideBox.Visibility = Visibility.Collapsed; return; }
            NowTime.Text = now != null ? now.TimeRange : "";
            NowText.Text = now != null ? now.Title : "Nothing on air now";
            NowProgress.Value = now != null ? now.Progress(nowMs) : 0;
            NowProgress.Visibility = now != null ? Visibility.Visible : Visibility.Collapsed;
            NextTime.Text = next != null ? next.StartLocal.ToString("HH:mm") : "";
            NextText.Text = next != null ? "Next: " + next.Title : "";
            GuideBox.Visibility = Visibility.Visible;
        }

        // ---------------------------------------------------------------- position bar

        private void OnSeekRelease(object sender, MouseButtonEventArgs e)
        {
            if (_mp == null || Current.IsLive || !_mp.IsSeekable) return;
            _mp.Time = (long)Seek.Value;
            KeepControls();
        }

        private void SeekBy(long deltaMs)
        {
            if (_mp == null || Current.IsLive || !_mp.IsSeekable) return;
            var len = _mp.Length;
            var t = Math.Max(0, _mp.Time + deltaMs);
            if (len > 0) t = Math.Min(len - 2000, t);
            _mp.Time = t;
            Osd((deltaMs > 0 ? "» " : "« ") + Playback.Time(t) + (len > 0 ? " / " + Playback.Time(len) : ""));
            KeepControls();
        }

        // ---------------------------------------------------------------- keys and mouse

        private void OnKey(object sender, KeyEventArgs e)
        {
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            var live = Current.IsLive;
            var ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
            var handled = true;
            switch (key)
            {
                case Key.Escape:
                case Key.Back:
                case Key.BrowserBack:
                    Close();
                    break;
                case Key.Up:
                case Key.PageUp:
                case Key.MediaNextTrack:
                    if (live || key != Key.Up) Step(live && !App.Store.Settings.TvArrows && key == Key.Up ? -1 : 1);
                    else Volume(+5);
                    break;
                case Key.Down:
                case Key.PageDown:
                case Key.MediaPreviousTrack:
                    if (live || key != Key.Down) Step(live && !App.Store.Settings.TvArrows && key == Key.Down ? 1 : -1);
                    else Volume(-5);
                    break;
                case Key.Left:
                    if (live) { if (_source == Source.LiveBuffer) LiveJump(-30); else ShowInfo(); }
                    else SeekBy(ctrl ? -60000 : -10000);
                    break;
                case Key.Right:
                    if (live) { if (_source == Source.LiveBuffer) LiveJump(30); else ShowInfo(); }
                    else SeekBy(ctrl ? 60000 : 10000);
                    break;
                case Key.L:
                case Key.End:
                    if (live) BackToLive(); else handled = false;
                    break;
                case Key.Space:
                case Key.MediaPlayPause:
                case Key.Play:
                case Key.Pause:
                    TogglePause();
                    break;
                case Key.Enter:
                    if (live) ShowInfo(); else TogglePause();
                    break;
                case Key.I:
                case Key.Apps:
                    ShowInfo();
                    break;
                case Key.R:
                    ToggleRecord();
                    break;
                case Key.D:
                    DownloadCurrent();
                    break;
                case Key.A:
                    NextAudio();
                    break;
                case Key.S:
                    NextSubtitle();
                    break;
                case Key.Z:
                    _aspect = (_aspect + 1) % AspectNames.Length;
                    ApplyAspect(true);
                    break;
                case Key.F:
                case Key.F11:
                    SetFullScreen(!_fullScreen);
                    break;
                case Key.M:
                case Key.VolumeMute:
                    ToggleMute();
                    break;
                case Key.OemPlus:
                case Key.Add:
                case Key.VolumeUp:
                    Volume(+5);
                    break;
                case Key.OemMinus:
                case Key.Subtract:
                case Key.VolumeDown:
                    Volume(-5);
                    break;
                default:
                    handled = false;
                    break;
            }
            if (handled) e.Handled = true;
        }

        /// <summary>Next / previous channel (or episode). Quick presses are gathered before tuning.</summary>
        private void Step(int delta)
        {
            if (_items.Count < 2) return;
            _lastDirection = delta >= 0 ? 1 : -1;
            _index = (_index + delta + _items.Count) % _items.Count;
            _retry.Stop();
            ShowHeader(Current);
            ShowStatus(null);
            NetBox.Visibility = Visibility.Collapsed;
            SetControlsVisible(true);
            _switch.Stop();
            _switch.Interval = TimeSpan.FromMilliseconds(450);
            _switch.Start();
        }

        private void ShowInfo()
        {
            SetControlsVisible(true);
            if (Current.IsLive) LoadGuide(Current);
        }

        private void TogglePause()
        {
            if (_mp == null) return;
            if (_source == Source.LiveBuffer && _live != null)
            {
                // Live TV keeps downloading to the disk buffer while paused.
                if (_livePausedAt < 0)
                {
                    _livePausedAt = LiveScreenOffset();
                    var mp = _mp;
                    Task.Run(() => { try { mp.Stop(); } catch { } });
                    PlayPauseButton.Content = "▶";
                    Osd("❚❚  Paused — live TV keeps recording to the buffer");
                }
                else
                {
                    var at = _livePausedAt;
                    _livePausedAt = -1;
                    PlayPauseButton.Content = "❚❚";
                    Osd("▶  Play");
                    StartMedia(_live.UrlAt(at), true, true);
                }
                KeepControls();
                return;
            }
            if (_mp.IsPlaying)
            {
                if (_mp.CanPause) { _mp.Pause(); Osd("❚❚  Paused"); }
            }
            else
            {
                _mp.Play();
                PlayPauseButton.Content = "❚❚";
                Osd("▶  Play");
            }
            KeepControls();
        }

        private void Volume(int delta)
        {
            if (_mp == null) return;
            var v = Math.Max(0, Math.Min(150, _mp.Volume + delta));
            _mp.Volume = v;
            if (_mp.Mute && delta > 0) _mp.Mute = false;
            Osd("Volume " + v + "%");
        }

        private void ToggleMute()
        {
            if (_mp == null) return;
            _mp.Mute = !_mp.Mute;
            Osd(_mp.Mute ? "Sound off" : "Sound on");
        }

        private void NextAudio()
        {
            if (_mp == null) return;
            var tracks = _mp.AudioTrackDescription.Where(t => t.Id >= 0).ToList();
            if (tracks.Count == 0) { Osd("No audio choice"); return; }
            var i = tracks.FindIndex(t => t.Id == _mp.AudioTrack);
            var next = tracks[(i + 1) % tracks.Count];
            _mp.SetAudioTrack(next.Id);
            Osd("Audio: " + next.Name);
        }

        private void NextSubtitle()
        {
            if (_mp == null) return;
            var tracks = _mp.SpuDescription.ToList(); // includes "Disable" (-1)
            if (tracks.Count <= 1) { Osd("No subtitles"); return; }
            var i = tracks.FindIndex(t => t.Id == _mp.Spu);
            var next = tracks[(i + 1) % tracks.Count];
            _mp.SetSpu(next.Id);
            Osd(next.Id < 0 ? "Subtitles off" : "Subtitles: " + next.Name);
        }

        private void ApplyAspect(bool announce)
        {
            if (_mp == null) return;
            var w = Math.Max(1, (int)ActualWidth);
            var h = Math.Max(1, (int)ActualHeight);
            switch (_aspect)
            {
                case 1: _mp.AspectRatio = null; _mp.CropGeometry = w + ":" + h; break;
                case 2: _mp.CropGeometry = null; _mp.AspectRatio = w + ":" + h; break;
                case 3: _mp.CropGeometry = null; _mp.AspectRatio = "16:9"; break;
                case 4: _mp.CropGeometry = null; _mp.AspectRatio = "4:3"; break;
                default: _mp.CropGeometry = null; _mp.AspectRatio = null; break;
            }
            if (announce) Osd("Picture: " + AspectNames[_aspect]);
        }

        private void SetFullScreen(bool on)
        {
            _fullScreen = on;
            if (on)
            {
                WindowStyle = WindowStyle.None;
                ResizeMode = ResizeMode.NoResize;
                if (WindowState == WindowState.Maximized) WindowState = WindowState.Normal; // re-maximize to cover the taskbar
                WindowState = WindowState.Maximized;
            }
            else
            {
                WindowStyle = WindowStyle.SingleBorderWindow;
                ResizeMode = ResizeMode.CanResize;
                WindowState = WindowState.Normal;
            }
            if (_aspect == 1 || _aspect == 2) Dispatcher.BeginInvoke(new Action(() => ApplyAspect(false)), DispatcherPriority.Background);
        }

        private void OnMouseMove(object sender, MouseEventArgs e)
        {
            var p = e.GetPosition(Overlay);
            if (Math.Abs(p.X - _lastMouse.X) < 3 && Math.Abs(p.Y - _lastMouse.Y) < 3) return;
            _lastMouse = p;
            KeepControls();
        }

        private void OnMouseDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ClickCount == 2) { SetFullScreen(!_fullScreen); e.Handled = true; }
            else KeepControls();
        }

        private void OnWheel(object sender, MouseWheelEventArgs e)
        {
            Volume(e.Delta > 0 ? 5 : -5);
            e.Handled = true;
        }

        private void KeepControls()
        {
            SetControlsVisible(true);
        }

        private void SetControlsVisible(bool on)
        {
            var v = on ? Visibility.Visible : Visibility.Collapsed;
            TopBar.Visibility = v;
            BottomBar.Visibility = v;
            Overlay.Cursor = on ? null : Cursors.None;
            _hide.Stop();
            if (on && _mp != null && (_mp.IsPlaying || _mp.State == VLCState.Opening || _mp.State == VLCState.Buffering)) _hide.Start();
        }

        private void OnPlayPause(object sender, RoutedEventArgs e) { TogglePause(); }
        private void OnBack10(object sender, RoutedEventArgs e) { SeekBy(-10000); }
        private void OnFwd10(object sender, RoutedEventArgs e) { SeekBy(10000); }
        private void OnAudio(object sender, RoutedEventArgs e) { NextAudio(); }
        private void OnSubs(object sender, RoutedEventArgs e) { NextSubtitle(); }
        private void OnAspect(object sender, RoutedEventArgs e) { _aspect = (_aspect + 1) % AspectNames.Length; ApplyAspect(true); }
        private void OnFullScreen(object sender, RoutedEventArgs e) { SetFullScreen(!_fullScreen); }
        private void OnCloseClick(object sender, RoutedEventArgs e) { Close(); }

        // ---------------------------------------------------------------- closing

        private void OnClosing(object sender, CancelEventArgs e)
        {
            if (_stopped) return;
            // Stop VLC before its window disappears (closing a network stream can take a moment).
            e.Cancel = true;
            if (_closing) return;
            _closing = true;
            _tick.Stop(); _hide.Stop(); _retry.Stop(); _switch.Stop(); _osd.Stop();
            if (_guideCts != null) _guideCts.Cancel();
            App.Recordings.Changed -= OnRecordingsChanged;
            var recording = Current.IsLive && Current.Channel != null && App.Recordings.IsRecording(Current.Channel.Key);
            Hide();
            if (Owner != null) Owner.Activate();
            if (recording && App.Home != null) App.Home.Toast("● Recording continues — stop it in Downloads, or with R on the channel");
            var mp = _mp;
            Task.Run(() => { try { if (mp != null) mp.Stop(); } catch { } })
                .ContinueWith(t => Dispatcher.BeginInvoke(new Action(() =>
                {
                    ReleaseLive();
                    ReleaseVod();
                    _stopped = true;
                    Close();
                })));
        }

        private void OnClosed(object sender, EventArgs e)
        {
            if (_overlayWindow != null) _overlayWindow.PreviewKeyDown -= OnKey;
            ReleaseLive();
            ReleaseVod();
            var mp = _mp;
            _mp = null;
            try { var d = Video as IDisposable; if (d != null) d.Dispose(); } catch { }
            try { if (mp != null) mp.Dispose(); } catch { }
            _done.TrySetResult(_index);
        }
    }
}
