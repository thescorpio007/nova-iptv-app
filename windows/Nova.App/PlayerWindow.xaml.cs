using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using Nova.Core;
using Nova.Core.Streaming;
using MediaPlayer = LibVLCSharp.Shared.MediaPlayer;

namespace Nova.App
{
    /// <summary>
    /// The player. Streams are downloaded to the disk buffer first and played from there; the watchdog
    /// reconnects for ever, dead channels are skipped, live TV can be paused, rewound and recorded,
    /// films can be downloaded (also while watching) and resume where you stopped. Every button can be
    /// changed in Settings → Remote control.
    /// </summary>
    public partial class PlayerWindow : Window
    {
        private enum Source { Direct, LiveBuffer, FilmBuffer, LocalFile, Growing }

        private List<PlayItem> _items;
        private readonly TaskCompletionSource<int> _done = new TaskCompletionSource<int>();
        private readonly DispatcherTimer _tick = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        private readonly DispatcherTimer _hide = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };
        private readonly DispatcherTimer _osd = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2.4) };
        private readonly DispatcherTimer _retry = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        private readonly DispatcherTimer _switch = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(450) };
        private readonly DispatcherTimer _digitsTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.6) };
        private readonly DispatcherTimer _overlayOpen = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(300) };
        private LibVLC _vlc;
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
        private string _digits = "";
        private PlayItem _previous;
        private bool _recentSaved;
        private DateTime _lastResumeSave = DateTime.MinValue;
        private Action _afterClose;

        private Source _source;
        private LiveLease _live;
        private VodLease _vod;
        private long _livePausedAt = -1;
        private long _lastTime = -1;
        private DateTime _lastTimeChange = DateTime.UtcNow;
        private long _resumeAt;

        // Full repair (Settings › Watchdog): 0 = normal, 1 = safer settings, 2 = other account tried.
        private int _repairStage;
        private int _repairFailures;
        private bool _softwareOnly;

        private static readonly string[] AspectNames = { "Fit", "Fill (zoom)", "Stretch", "16:9", "4:3" };

        private PlayerWindow(List<PlayItem> items, int index)
        {
            InitializeComponent();
            _items = items;
            _index = Math.Max(0, Math.Min(index, items.Count - 1));
            Signature.Text = Brand.Signature;
            Title = "Nova IPTV";
            _tick.Tick += (s, e) => OnTick();
            _hide.Tick += (s, e) => { _hide.Stop(); SetControlsVisible(false); };
            _osd.Tick += (s, e) => { _osd.Stop(); OsdBox.Visibility = Visibility.Collapsed; };
            _retry.Tick += (s, e) => { _retry.Stop(); Restart(); };
            _switch.Tick += (s, e) => { _switch.Stop(); PlayCurrent(true); };
            _digitsTimer.Tick += (s, e) => { _digitsTimer.Stop(); JumpToNumber(); };
            _overlayOpen.Tick += (s, e) => { _overlayOpen.Stop(); LoadOverlayChannels(); };
            App.Recordings.Changed += OnRecordingsChanged;
            ApplyClockPosition();
        }

        /// <summary>
        /// Opens the player (or the external player chosen in Settings for this kind of content). Finishes with the
        /// index of the last item played once the player has fully stopped.
        /// </summary>
        public static Task<int> Play(Window owner, List<PlayItem> items, int index)
        {
            if (items == null || items.Count == 0) return Task.FromResult(-1);
            var item = items[Math.Max(0, Math.Min(index, items.Count - 1))];
            if (item.LocalPath == null && !Vpn.Allow(owner)) return Task.FromResult(-1);
            var external = ExternalPlayers.ChosenFor(item.LocalPath != null ? "file" : item.Kind ?? "file");
            if (external != null)
            {
                if (item.Recent != null) App.Store.AddRecent(item.Recent);
                if (ExternalPlayers.Launch(external, item.LocalPath ?? item.Url, item.Title, item.Headers)) return Task.FromResult(index);
                App.Home.Toast("The external player couldn't start — playing in Nova instead.");
            }
            if (App.Vlc == null) { App.Home.Toast("The video engine (VLC) didn't start, so videos can't play."); return Task.FromResult(-1); }
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
            try { _vlc = new LibVLC(Playback.VlcOptions(true)); }
            catch (Exception ex) { App.Log(ex); _vlc = null; }
            _mp = new MediaPlayer(_vlc ?? App.Vlc);
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

        private LibVLC Vlc { get { return _vlc ?? App.Vlc; } }

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

        private void ApplyClockPosition()
        {
            var p = App.Store.Settings.ClockPosition;
            ClockBox.Visibility = p == "off" ? Visibility.Collapsed : Visibility.Visible;
            ClockBox.HorizontalAlignment = p == "tr" || p == "br" ? HorizontalAlignment.Right : HorizontalAlignment.Left;
            ClockBox.VerticalAlignment = p == "tl" || p == "tr" ? VerticalAlignment.Top : VerticalAlignment.Bottom;
            ClockBox.Margin = p == "tr" ? new Thickness(18, 14, 24, 14) : new Thickness(18, 14, 18, 14);
        }

        // ---------------------------------------------------------------- choosing the source

        private bool BufferLive(PlayItem item)
        {
            var s = App.Store.Settings;
            if (!s.UseDiskBuffer || item.Hls) return false;
            if (item.Channel != null) return s.LiveFormat != "m3u8";
            return item.LiveKey != null && !(item.Url ?? "").ToLowerInvariant().Split('?')[0].EndsWith(".m3u8");
        }

        /// <summary>Starts the current item: the disk buffer when possible, otherwise directly.</summary>
        private async void PlayCurrent(bool fresh)
        {
            if (_closing || _mp == null) return;
            var item = Current;
            var id = ++_playId;
            if (fresh)
            {
                _attempts = 0;
                _resumeAt = 0;
                _recentSaved = false;
                _repairStage = 0;
                _repairFailures = 0;
                _softwareOnly = false;
            }
            _playingSince = DateTime.MaxValue;
            _livePausedAt = -1;
            ShowHeader(item);
            SetControlsVisible(true);
            NetBox.Visibility = Visibility.Collapsed;

            // Close the streams of the previous item first: the account may allow only one connection.
            ReleaseStreamsExcept(item);

            var done = item.IsLive ? null : item.LocalPath ?? (item.Url != null ? App.Downloads.DoneFile(item.Url) : null);
            if (done == null && !ConnectionGuard.Allow(item.AccountId, item.LiveId, this))
            {
                ShowStatus("Not playing: the recording keeps the account's only connection.\nEsc to close.");
                return;
            }

            // Resume where you stopped (films, episodes, files).
            if (fresh && !item.IsLive && item.ResumeKey != null)
            {
                var pos = App.Store.GetPosition(item.ResumeKey);
                if (pos > 30000) { _resumeAt = pos; Osd("Resumed at " + Playback.Time(pos) + "  ·  " + K("vod_start") + " = start over"); }
            }

            if (item.IsLive)
            {
                if (BufferLive(item))
                {
                    if (_live == null)
                    {
                        _live = App.Hub.OpenLive(item.LiveId, item.Url, item.Headers);
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

            // Films, episodes, files and links.
            if (done != null)
            {
                _source = Source.LocalFile;
                ShowStatus(null);
                if (item.LocalPath == null) Osd("Playing your downloaded file");
                StartMedia(new Uri(done).AbsoluteUri, false, true);
                return;
            }
            if (item.Hls)
            {
                // HLS: Download & Watch reads the parts as they arrive; otherwise VLC plays the link itself.
                var hls = item.DownloadWatch ? App.Downloads.ActiveHls(item.Url) : null;
                if (hls == null && item.DownloadWatch)
                {
                    ShowStatus("Starting the download…");
                    for (int i = 0; i < 40 && hls == null && !_closing && id == _playId; i++) { await Task.Delay(250); hls = App.Downloads.ActiveHls(item.Url); }
                    if (_closing || id != _playId) return;
                }
                if (hls != null)
                {
                    _source = Source.Growing;
                    ShowStatus("Waiting for the first parts…");
                    StartMedia(App.Hub.ServeGrowing(StreamHub.Id(item.Url), hls), true, true);
                    return;
                }
                _source = Source.Direct;
                ShowStatus("Connecting…");
                StartMedia(item.Url, false, false);
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
                var useDownload = item.DownloadWatch || (dl != null && dl.Status != DownloadStatus.Failed && !dl.IsDone && !dl.Hls);
                if (useDownload && dl != null)
                {
                    App.Downloads.Prioritize(dl.Id);
                    _vod = App.Hub.OpenVod(item.Url, item.Ext, dl.PartsFolder, true, item.AccountId, item.Headers, App.Downloads.ConnectionsFor(dl));
                }
                else
                {
                    _vod = App.Hub.OpenVod(item.Url, item.Ext, null, false, item.AccountId, item.Headers);
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
                using (var media = Playback.MakeMedia(Vlc, url, live, local, _softwareOnly, local ? null : Current.Headers))
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

        private static string K(string action)
        {
            var keys = KeyMap.KeysFor(action);
            return keys.Count == 0 ? "—" : KeyMap.Pretty(keys[0]);
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
            RecButton.Visibility = item.IsLive && item.Channel != null ? Visibility.Visible : Visibility.Collapsed;
            ListButton.Visibility = item.IsLive ? Visibility.Visible : Visibility.Collapsed;
            DownloadButton.Visibility = !item.IsLive && item.LocalPath == null && (item.Movie != null || item.Episode != null || item.Kind == "link") ? Visibility.Visible : Visibility.Collapsed;
            KeysHint.Text = item.IsLive
                ? "▲ ▼ channel  ·  " + K("live_list") + " channels list  ·  " + K("live_recent") + " recent  ·  " +
                  (buffered ? K("live_rewind") + " −30 s  ·  " + K("live_golive") + " live  ·  " : "") +
                  (item.Channel != null ? K("live_record") + " record  ·  " : "") + K("live_tracks") + " audio & subtitles"
                : K("vod_back") + " / " + K("vod_forward") + " 10 s  ·  " + K("vod_play_pause") + " pause  ·  " + K("vod_tracks") + " audio & subtitles" +
                  (_items.Count > 1 ? "  ·  " + K("vod_next") + " next" : "");
            GuideBox.Visibility = Visibility.Collapsed;
            LiveBadge.Visibility = Visibility.Collapsed;
            DiskText.Text = "";
            PlayPauseButton.Content = "❚❚";
            UpdateRecBadge();
            UpdateFavBadge();
            if (item.IsLive) LoadGuide(item);
        }

        // ---------------------------------------------------------------- streams

        private void ReleaseStreamsExcept(PlayItem item)
        {
            if (_live != null && (item.LiveId == null || _live.Relay.Key != item.LiveId || !BufferLive(item))) ReleaseLive();
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
                    TryRepair();
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
                if (r.Attempt > 0 && r.Attempt % 3 == 0) TryRepair();
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

        // ---------------------------------------------------------------- full repair

        /// <summary>
        /// Watchdog "Full repair": a channel that keeps failing first gets safer player settings (no graphics-card
        /// decoding), then the same channel on your other account.
        /// </summary>
        private async void TryRepair()
        {
            var s = App.Store.Settings;
            if (s.WatchdogMode != "full" || !Current.IsLive) return;
            _repairFailures++;
            if (_repairFailures < 2) return;
            _repairFailures = 0;
            if (_repairStage == 0)
            {
                _repairStage = 1;
                if (s.HardwareDecoding)
                {
                    _softwareOnly = true;
                    Osd("Watchdog: trying safer player settings");
                    Restart();
                    return;
                }
            }
            if (_repairStage <= 1 && s.WatchdogOtherAccount && Current.Channel != null && App.Store.Accounts.Count > 1)
            {
                _repairStage = 2;
                var ch = Current.Channel;
                var name = Normalize(ch.Name);
                foreach (var a in App.Store.Accounts.Where(x => x.Id != ch.AccountId).ToList())
                {
                    try
                    {
                        var list = await App.Catalog.AllChannels(a);
                        var same = list.FirstOrDefault(c => Normalize(c.Name) == name);
                        if (same == null || _closing) continue;
                        _items[_index] = PlayItem.Of(same);
                        Osd("Watchdog: same channel on " + a.DisplayName);
                        PlayCurrent(false);
                        return;
                    }
                    catch { }
                }
            }
        }

        private static string Normalize(string name)
        {
            var sb = new StringBuilder();
            foreach (var c in (name ?? "").ToLowerInvariant()) if (char.IsLetterOrDigit(c)) sb.Append(c);
            var s = sb.ToString();
            foreach (var tag in new[] { "fhd", "uhd", "hd", "sd", "4k", "hevc" }) if (s.EndsWith(tag)) s = s.Substring(0, s.Length - tag.Length);
            return s;
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
            if (!_recentSaved && Current.Recent != null)
            {
                _recentSaved = true;
                try { App.Store.AddRecent(Current.Recent); } catch { }
            }
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
                if (item.ResumeKey != null) App.Store.SetPosition(item.ResumeKey, 0);
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
            if (_source == Source.Growing && ended && App.Downloads.ActiveHls(item.Url) == null) { Close(); return; }

            // Watchdog: never give up; wait a little longer each time (1 s … 30 s).
            _attempts++;
            if (!item.IsLive && _mp.Time > 0) _resumeAt = _mp.Time;

            // A film the built-in player can't play: the external player, if you chose that (Settings › Player selection).
            if (!item.IsLive && _attempts >= 3 && !ended && App.Store.Settings.ExternalOnError && ExternalPlayers.Fallback() != null)
            {
                OpenExternal("The built-in player can't play this — opening the external player");
                return;
            }

            var wait = Math.Min(30, (int)Math.Pow(2, Math.Min(5, _attempts - 1)));
            if (_source == Source.LiveBuffer || _source == Source.Growing) wait = 1;
            if (_source == Source.Direct || !(_live != null && _live.Relay.State == RelayState.Reconnecting))
                ShowStatus((item.IsLive ? "Connection lost." : "The video stopped.") + "\nWatchdog: reconnecting… (attempt " + _attempts + ")");
            if (item.IsLive && _attempts % 2 == 0) TryRepair();
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
                if (_source == Source.LiveBuffer && App.Store.Settings.WatchdogMode != "full")
                {
                    Osd("The picture froze — watchdog jumps back to live");
                    BackToLive();
                }
                else
                {
                    Osd("The picture froze — watchdog restarts it");
                    Restart();
                    if (item.IsLive) TryRepair();
                }
                return;
            }
            if (_playingSince != DateTime.MaxValue && (DateTime.Now - _playingSince).TotalSeconds > 60) { _repairFailures = 0; }

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
            else DiskText.Text = _source == Source.LocalFile ? "From your PC" : _source == Source.Growing ? "Downloading while you watch" : "";

            // Remember where you are (every 10 s).
            if (item.ResumeKey != null && len > 0 && (DateTime.UtcNow - _lastResumeSave).TotalSeconds > 10 && _mp.IsPlaying)
            {
                _lastResumeSave = DateTime.UtcNow;
                SaveResume();
            }
        }

        private void SaveResume()
        {
            var item = Current;
            if (_mp == null || item.ResumeKey == null || item.IsLive) return;
            var len = _mp.Length;
            var t = _mp.Time;
            if (len <= 0) return;
            if (t > 30000 && t < len * 0.95) App.Store.SetPosition(item.ResumeKey, t);
            else if (t >= len * 0.95) App.Store.SetPosition(item.ResumeKey, 0);
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
                LiveText.Foreground = (Brush)FindResource("Accent");
            }
            else
            {
                LiveText.Text = (_livePausedAt >= 0 ? "❚❚ " : "⏪ ") + Playback.Time((long)(behind * 1000)) + " behind live  ·  " + K("live_golive") + " = live";
                LiveText.Foreground = (Brush)FindResource("StarBrush");
            }
        }

        private void LiveJump(double seconds)
        {
            if (_source != Source.LiveBuffer || _live == null) { Osd("Rewind needs the disk buffer (MPEG-TS format)"); return; }
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
            if (_source != Source.LiveBuffer || _live == null) { Restart(); return; }
            _livePausedAt = -1;
            Osd("● Back to live");
            StartMedia(_live.Url, true, true);
            KeepControls();
        }

        private void OnBackToLive(object sender, RoutedEventArgs e) { BackToLive(); }

        // ---------------------------------------------------------------- recording, downloads, favorites, external

        private void ToggleRecord()
        {
            var item = Current;
            if (!item.IsLive || item.Channel == null) { Osd("Recording works on your channels"); return; }
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
            RecButton.Content = rec ? "■ Stop rec (" + K("live_record") + ")" : "● Rec (" + K("live_record") + ")";
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
            if (item.IsLive || item.LocalPath != null) return;
            var existing = App.Downloads.Find(item.Url);
            if (existing != null && existing.IsDone && File.Exists(existing.FinalPath)) { Osd("Already downloaded"); return; }
            var d = item.StartDownload() ?? (item.Kind == "link" ? App.Downloads.AddLink(item.Url, item.Title, item.Ext, item.Hls, item.Headers) : null);
            if (d == null) return;
            if (_vod != null && _vod.File.Url == item.Url && _vod.File.Full) { Osd("Already downloading — it continues while you watch"); return; }
            Osd(App.Catalog.MaxConnections(item.AccountId) == 1
                ? "⬇ Added to Downloads — starts when you close the player (1-connection account)"
                : "⬇ Downloading to " + Path.GetDirectoryName(d.FinalPath));
        }

        private SavedItem FavoriteTarget()
        {
            var item = Current;
            if (item.Channel != null) return SavedItem.Of(item.Channel);
            if (item.Movie != null) return SavedItem.Of(item.Movie);
            if (item.Series != null) return SavedItem.Of(item.Series);
            return null;
        }

        private void ToggleFavorite()
        {
            var f = FavoriteTarget();
            if (f == null) { Osd("This can't be a favorite"); return; }
            var now = App.Store.ToggleFavorite(f);
            Osd(now ? "★ Added to favorites" : "Removed from favorites");
            UpdateFavBadge();
        }

        private void UpdateFavBadge()
        {
            var f = FavoriteTarget();
            FavBadge.Visibility = f != null && App.Store.IsFavorite(f) ? Visibility.Visible : Visibility.Collapsed;
        }

        /// <summary>Opens the current item in the external player (after Nova's own connection is closed).</summary>
        private void OpenExternal(string message)
        {
            var player = ExternalPlayers.Fallback();
            if (player == null)
            {
                Osd("No external player found. Install VLC, MPC-HC or PotPlayer, or choose one in Settings › Player selection.");
                return;
            }
            var item = Current;
            var url = item.LocalPath ?? item.Url;
            SaveResume();
            if (message != null) App.Home.Toast(message);
            _afterClose = () => ExternalPlayers.Launch(player, url, item.Title, item.Headers);
            Close();
        }

        private void OnRec(object sender, RoutedEventArgs e) { ToggleRecord(); }
        private void OnDownload(object sender, RoutedEventArgs e) { DownloadCurrent(); }
        private void OnExternal(object sender, RoutedEventArgs e) { OpenExternal(null); }

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

        // ---------------------------------------------------------------- keys

        private bool PanelOpen
        {
            get { return ListOverlay.Visibility == Visibility.Visible || RecentBar.Visibility == Visibility.Visible || TracksPanel.Visibility == Visibility.Visible; }
        }

        private void ClosePanels()
        {
            ListOverlay.Visibility = Visibility.Collapsed;
            RecentBar.Visibility = Visibility.Collapsed;
            TracksPanel.Visibility = Visibility.Collapsed;
            Focus();
        }

        private void OnKey(object sender, KeyEventArgs e)
        {
            var key = e.Key == Key.System ? e.SystemKey : e.Key;
            if (PanelOpen)
            {
                // The lists and panels use the arrows themselves; Esc / Back closes them.
                if (key == Key.Escape || key == Key.Back || key == Key.BrowserBack) { e.Handled = true; ClosePanels(); }
                return;
            }
            var live = Current.IsLive;

            // Typing a channel number.
            if (live && App.Store.Settings.NumberKeys && Keyboard.Modifiers == ModifierKeys.None)
            {
                var digit = key >= Key.D0 && key <= Key.D9 ? key - Key.D0 : key >= Key.NumPad0 && key <= Key.NumPad9 ? key - Key.NumPad0 : -1;
                if (digit >= 0)
                {
                    e.Handled = true;
                    if (_digits.Length < 5) _digits += digit;
                    NumberText.Text = _digits + "_";
                    NumberBox.Visibility = Visibility.Visible;
                    _digitsTimer.Stop();
                    _digitsTimer.Start();
                    return;
                }
            }

            var action = KeyMap.ActionFor(live ? KeyArea.Live : KeyArea.Vod, e);
            if (action == null) return;
            e.Handled = true;
            switch (action)
            {
                case "live_close":
                case "vod_close": Close(); break;
                case "live_next": Step(1); break;
                case "live_prev": Step(-1); break;
                case "live_list": OpenListOverlay(); break;
                case "live_recent": OpenRecentBar(); break;
                case "live_info":
                case "vod_controls": ShowInfo(); break;
                case "live_recall": Recall(); break;
                case "live_pause":
                case "vod_play_pause": TogglePause(); break;
                case "live_rewind": LiveJump(-30); break;
                case "live_forward": LiveJump(30); break;
                case "live_golive": BackToLive(); break;
                case "live_record": ToggleRecord(); break;
                case "live_favorite":
                case "vod_favorite": ToggleFavorite(); break;
                case "live_aspect":
                case "vod_aspect": _aspect = (_aspect + 1) % AspectNames.Length; ApplyAspect(true); break;
                case "live_tracks":
                case "vod_tracks": OpenTracks(); break;
                case "live_audio":
                case "vod_audio": NextAudio(); break;
                case "live_subs":
                case "vod_subs": NextSubtitle(); break;
                case "live_external":
                case "vod_external": OpenExternal(null); break;
                case "live_fullscreen":
                case "vod_fullscreen": SetFullScreen(!_fullScreen); break;
                case "live_mute":
                case "vod_mute": ToggleMute(); break;
                case "live_volup":
                case "vod_volup": Volume(+5); break;
                case "live_voldown":
                case "vod_voldown": Volume(-5); break;
                case "vod_back": SeekBy(-10000); break;
                case "vod_forward": SeekBy(10000); break;
                case "vod_back_long": SeekBy(-60000); break;
                case "vod_forward_long": SeekBy(60000); break;
                case "vod_next": if (_index + 1 < _items.Count) Step(1); else Osd("This is the last one"); break;
                case "vod_prev": if (_index > 0) Step(-1); else Osd("This is the first one"); break;
                case "vod_start": if (_mp != null && _mp.IsSeekable) { _mp.Time = 0; Osd("From the beginning"); } break;
                case "vod_download": DownloadCurrent(); break;
                default: e.Handled = false; break;
            }
        }

        /// <summary>Next / previous channel (or episode). Quick presses are gathered before tuning.</summary>
        private void Step(int delta)
        {
            if (_items.Count < 2) return;
            _previous = Current;
            _lastDirection = delta >= 0 ? 1 : -1;
            SaveResume();
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

        /// <summary>Plays another item now (list overlay, recent bar, recall, channel number).</summary>
        private void PlayItemNow(List<PlayItem> items, int index)
        {
            if (index < 0 || index >= items.Count) return;
            _previous = Current;
            SaveResume();
            _items = items;
            _index = index;
            _retry.Stop();
            _switch.Stop();
            PlayCurrent(true);
        }

        private void Recall()
        {
            if (_previous == null) { Osd("No earlier channel yet"); return; }
            var prev = _previous;
            var i = _items.FindIndex(x => x.LiveId != null && x.LiveId == prev.LiveId);
            if (i < 0) { _items = _items.ToList(); _items.Insert(_index + 1, prev); i = _index + 1; }
            PlayItemNow(_items, i);
        }

        private void JumpToNumber()
        {
            NumberBox.Visibility = Visibility.Collapsed;
            int n;
            var text = _digits;
            _digits = "";
            if (!int.TryParse(text, out n)) return;
            var i = _items.FindIndex(x => x.Channel != null && x.Channel.Num == n);
            if (i < 0) { Osd("No channel " + n + " in this list"); return; }
            if (i != _index) PlayItemNow(_items, i);
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
                if (_mp.CanPause) { _mp.Pause(); Osd("❚❚  Paused"); SaveResume(); }
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
            if (tracks.Count <= 1) { Osd("No subtitles — " + K(Current.IsLive ? "live_tracks" : "vod_tracks") + " to load a subtitle file"); return; }
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
            if (PanelOpen && e.OriginalSource == Overlay) { ClosePanels(); return; }
            if (e.ClickCount == 2) { SetFullScreen(!_fullScreen); e.Handled = true; }
            else KeepControls();
        }

        private void OnWheel(object sender, MouseWheelEventArgs e)
        {
            if (PanelOpen) return;
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
            BottomBar.Visibility = PanelOpen && RecentBar.Visibility == Visibility.Visible ? Visibility.Collapsed : v;
            Overlay.Cursor = on || PanelOpen ? null : Cursors.None;
            _hide.Stop();
            if (on && !PanelOpen && _mp != null && (_mp.IsPlaying || _mp.State == VLCState.Opening || _mp.State == VLCState.Buffering)) _hide.Start();
        }

        private void OnPlayPause(object sender, RoutedEventArgs e) { TogglePause(); }
        private void OnBack10(object sender, RoutedEventArgs e) { SeekBy(-10000); }
        private void OnFwd10(object sender, RoutedEventArgs e) { SeekBy(10000); }
        private void OnAspect(object sender, RoutedEventArgs e) { _aspect = (_aspect + 1) % AspectNames.Length; ApplyAspect(true); }
        private void OnFullScreen(object sender, RoutedEventArgs e) { SetFullScreen(!_fullScreen); }
        private void OnCloseClick(object sender, RoutedEventArgs e) { Close(); }
        private void OnTracks(object sender, RoutedEventArgs e) { OpenTracks(); }
        private void OnListButton(object sender, RoutedEventArgs e) { OpenListOverlay(); }

        /// <summary>Gives the keyboard to a panel drawn over the video (it lives in the overlay window).</summary>
        private void FocusPanel(UIElement target)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                if (_overlayWindow != null) _overlayWindow.Activate();
                target.Focus();
            }), DispatcherPriority.Loaded);
        }

        // ---------------------------------------------------------------- groups & channels over the video

        public class OverlayChannel
        {
            public LiveChannel Channel;
            public PlayItem Item;
            public string Number { get; set; }
            public string Name { get; set; }
        }

        private List<GroupRow> _overlayGroupRows = new List<GroupRow>();

        private async void OpenListOverlay()
        {
            if (!Current.IsLive) return;
            ListOverlay.Opacity = 1;
            ListOverlay.Background = new SolidColorBrush(Color.FromArgb((byte)(255 * (100 - Math.Max(0, Math.Min(90, App.Store.Settings.OverlayTransparency))) / 100), 0x10, 0x13, 0x17));
            ListOverlay.Visibility = Visibility.Visible;
            SetControlsVisible(false);
            if (Current.Channel == null)
            {
                // A link's playlist: just this list.
                OverlayGroups.ItemsSource = null;
                OverlayGroupTitle.Text = "This list";
                FillOverlayChannels(_items.Select(i => new OverlayChannel { Item = i, Number = "", Name = i.Title }).ToList());
                FocusOverlayChannels();
                return;
            }
            var rows = new List<GroupRow>();
            if (App.Store.FavoritesOf(ItemType.Live).Count > 0) rows.Add(new GroupRow { Kind = GroupKind.Favorites });
            try
            {
                var sections = await App.Catalog.Sections(ContentType.Live);
                var ordered = Organize.OrderedGroups("live", sections);
                var multi = sections.Count > 1;
                foreach (var s in sections)
                    foreach (var c in Organize.Groups("live", ordered, s.Account.Id, false))
                        rows.Add(new GroupRow { Kind = GroupKind.Group, Category = c, Account = s.Account, ShowSections = multi });
            }
            catch { }
            _overlayGroupRows = rows;
            OverlayGroups.ItemsSource = rows;
            var ch = Current.Channel;
            var current = rows.FirstOrDefault(r => r.Category != null && r.Category.AccountId == ch.AccountId && r.Category.Id == ch.CategoryId)
                          ?? rows.FirstOrDefault();
            if (current != null)
            {
                OverlayGroups.SelectedItem = current;
                OverlayGroups.ScrollIntoView(current);
                await LoadOverlayChannelsAsync(current);
                FocusOverlayChannels();
            }
        }

        private void OnOverlayGroupSelected(object sender, SelectionChangedEventArgs e)
        {
            if (!OverlayGroups.IsKeyboardFocusWithin) return;
            _overlayOpen.Stop();
            _overlayOpen.Start();
        }

        private void LoadOverlayChannels()
        {
            var row = OverlayGroups.SelectedItem as GroupRow;
            if (row != null) { var ignored = LoadOverlayChannelsAsync(row); }
        }

        private async Task LoadOverlayChannelsAsync(GroupRow row)
        {
            OverlayGroupTitle.Text = row.Text.Trim();
            List<LiveChannel> list;
            try
            {
                if (row.Kind == GroupKind.Favorites) list = App.Store.FavoritesOf(ItemType.Live).Select(f => f.ToChannel()).ToList();
                else list = await App.Catalog.Channels(row.Category);
            }
            catch { list = new List<LiveChannel>(); }
            if (!ReferenceEquals(OverlayGroups.SelectedItem, row)) return;
            var shown = Organize.Items("live", row.Key, list, c => c.Key, false, App.Store.Settings.SortLive, Organize.Info);
            FillOverlayChannels(shown.Select(c => new OverlayChannel { Channel = c, Number = c.Num > 0 ? c.Num.ToString() : "", Name = c.Name }).ToList());
        }

        private void FillOverlayChannels(List<OverlayChannel> list)
        {
            OverlayChannels.ItemsSource = list;
            var cur = Current.LiveId;
            var here = list.FirstOrDefault(x => (x.Channel != null ? x.Channel.Key : x.Item != null ? x.Item.LiveId : null) == cur) ?? list.FirstOrDefault();
            if (here != null) { OverlayChannels.SelectedItem = here; OverlayChannels.ScrollIntoView(here); }
        }

        private void FocusOverlayChannels()
        {
            FocusPanel(OverlayChannels);
            Dispatcher.BeginInvoke(new Action(() =>
            {
                OverlayChannels.UpdateLayout();
                var c = OverlayChannels.ItemContainerGenerator.ContainerFromItem(OverlayChannels.SelectedItem) as ListBoxItem;
                if (c != null) c.Focus();
            }), DispatcherPriority.ContextIdle);
        }

        private void FocusOverlayGroups()
        {
            OverlayGroups.UpdateLayout();
            var c = OverlayGroups.ItemContainerGenerator.ContainerFromItem(OverlayGroups.SelectedItem) as ListBoxItem;
            if (c != null) c.Focus(); else OverlayGroups.Focus();
        }

        private void OnOverlayGroupsKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Right || e.Key == Key.Enter)
            {
                e.Handled = true;
                _overlayOpen.Stop();
                var row = OverlayGroups.SelectedItem as GroupRow;
                if (row == null) return;
                var t = LoadOverlayChannelsAsync(row);
                t.ContinueWith(_ => Dispatcher.BeginInvoke(new Action(FocusOverlayChannels)));
            }
        }

        private void OnOverlayGroupClick(object sender, MouseButtonEventArgs e)
        {
            var row = OverlayGroups.SelectedItem as GroupRow;
            if (row != null) { var ignored = LoadOverlayChannelsAsync(row); }
        }

        private void OnOverlayChannelsKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Left && OverlayGroups.ItemsSource != null) { e.Handled = true; FocusOverlayGroups(); }
            else if (e.Key == Key.Enter) { e.Handled = true; PlayOverlayChannel(); }
        }

        private void OnOverlayChannelClick(object sender, MouseButtonEventArgs e) { PlayOverlayChannel(); }

        private void PlayOverlayChannel()
        {
            var list = OverlayChannels.ItemsSource as List<OverlayChannel>;
            var sel = OverlayChannels.SelectedItem as OverlayChannel;
            if (list == null || sel == null) return;
            ClosePanels();
            if (sel.Item != null) { PlayItemNow(_items, _items.IndexOf(sel.Item)); return; }
            var items = list.Select(x => PlayItem.Of(x.Channel)).ToList();
            PlayItemNow(items, list.IndexOf(sel));
        }

        // ---------------------------------------------------------------- recently watched bar

        private void OpenRecentBar()
        {
            var recent = App.Store.Recent.Where(r => r.Type == ItemType.Live).Take(15).ToList();
            if (recent.Count == 0) { Osd("No recently watched channels yet"); return; }
            RecentBar.Background = new SolidColorBrush(Color.FromArgb((byte)(255 * (100 - Math.Max(0, Math.Min(90, App.Store.Settings.OverlayTransparency))) / 100), 0x10, 0x13, 0x17));
            RecentList.ItemsSource = recent;
            RecentList.SelectedIndex = recent.Count > 1 && Current.LiveId == recent[0].Key ? 1 : 0;
            RecentBar.Visibility = Visibility.Visible;
            BottomBar.Visibility = Visibility.Collapsed;
            FocusPanel(RecentList);
            Dispatcher.BeginInvoke(new Action(() =>
            {
                RecentList.UpdateLayout();
                var c = RecentList.ItemContainerGenerator.ContainerFromIndex(RecentList.SelectedIndex) as ListBoxItem;
                if (c != null) c.Focus();
            }), DispatcherPriority.ContextIdle);
        }

        private void OnRecentKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) { e.Handled = true; PlayRecent(); }
        }

        private void OnRecentClick(object sender, MouseButtonEventArgs e) { PlayRecent(); }

        private void PlayRecent()
        {
            var list = RecentList.ItemsSource as List<SavedItem>;
            var sel = RecentList.SelectedItem as SavedItem;
            if (list == null || sel == null) return;
            ClosePanels();
            var i = _items.FindIndex(x => x.LiveId == sel.Key);
            if (i >= 0) { PlayItemNow(_items, i); return; }
            var items = list.Select(r => PlayItem.Of(r.ToChannel())).ToList();
            PlayItemNow(items, list.IndexOf(sel));
        }

        // ---------------------------------------------------------------- audio, subtitles & stream info

        private void OpenTracks()
        {
            if (_mp == null) return;
            BuildTracks();
            TracksPanel.Visibility = Visibility.Visible;
            SetControlsVisible(false);
            var first = TracksContent.Children.OfType<Button>().FirstOrDefault();
            if (first != null) FocusPanel(first);
        }

        private TextBlock Section(string text)
        {
            return new TextBlock { Text = text, Foreground = (Brush)FindResource("Accent"), FontWeight = FontWeights.Bold, FontSize = 15, Margin = new Thickness(0, 16, 0, 6) };
        }

        private Button Option(string text, bool selected, Action click)
        {
            var b = new Button
            {
                Style = (Style)FindResource("NovaButton"),
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Margin = new Thickness(0, 2, 0, 2),
                Content = new TextBlock { Text = (selected ? "●  " : "○  ") + text, TextWrapping = TextWrapping.Wrap },
            };
            if (selected) b.BorderBrush = (Brush)FindResource("Accent");
            b.Click += (s, e) => click();
            return b;
        }

        private void BuildTracks()
        {
            TracksContent.Children.Clear();
            TracksContent.Children.Add(new TextBlock { Text = "Audio & subtitles", FontSize = 20, FontWeight = FontWeights.Bold });
            TracksContent.Children.Add(new TextBlock { Text = "↑ ↓ choose · Enter select · Esc close", Foreground = (Brush)FindResource("DimText"), Margin = new Thickness(0, 4, 0, 0) });

            TracksContent.Children.Add(Section("🔊  Audio"));
            var audio = _mp.AudioTrackDescription.Where(t => t.Id >= 0).ToList();
            if (audio.Count == 0) TracksContent.Children.Add(new TextBlock { Text = "No audio track found", Foreground = (Brush)FindResource("DimText") });
            foreach (var t in audio)
            {
                var id = t.Id;
                TracksContent.Children.Add(Option(t.Name, _mp.AudioTrack == id, () => { _mp.SetAudioTrack(id); BuildTracksKeepFocus(); Osd("Audio: " + t.Name); }));
            }

            TracksContent.Children.Add(Section("CC  Subtitles"));
            foreach (var t in _mp.SpuDescription)
            {
                var id = t.Id;
                var name = id < 0 ? "Off" : t.Name;
                TracksContent.Children.Add(Option(name, _mp.Spu == id, () => { _mp.SetSpu(id); BuildTracksKeepFocus(); Osd(id < 0 ? "Subtitles off" : "Subtitles: " + name); }));
            }
            if (_mp.SpuDescription.Length == 0) TracksContent.Children.Add(new TextBlock { Text = "No subtitle found in this video", Foreground = (Brush)FindResource("DimText") });
            TracksContent.Children.Add(Option("＋ Load subtitle file (.srt, .vtt, .ass, .ssa, .sub)", false, LoadSubtitleFile));

            TracksContent.Children.Add(Section("⚙  Subtitle style (from the next video)"));
            var s = App.Store.Settings;
            TracksContent.Children.Add(Option("Size: " + s.SubtitleSize + "   −  smaller", false, () => ChangeSubs(x => x.SubtitleSize = Math.Max(10, x.SubtitleSize - 2))));
            TracksContent.Children.Add(Option("Size: " + s.SubtitleSize + "   +  bigger", false, () => ChangeSubs(x => x.SubtitleSize = Math.Min(48, x.SubtitleSize + 2))));
            foreach (var c in new[] { Tuple.Create("white", "White"), Tuple.Create("yellow", "Yellow"), Tuple.Create("green", "Green"), Tuple.Create("cyan", "Cyan") })
            {
                var code = c.Item1;
                TracksContent.Children.Add(Option("Colour: " + c.Item2, s.SubtitleColor == code, () => ChangeSubs(x => x.SubtitleColor = code)));
            }
            TracksContent.Children.Add(Option("Dark background behind the text", s.SubtitleBackground, () => ChangeSubs(x => x.SubtitleBackground = !x.SubtitleBackground)));

            TracksContent.Children.Add(Section("ℹ  Stream info"));
            TracksContent.Children.Add(new TextBlock { Text = StreamInfo(), TextWrapping = TextWrapping.Wrap, LineHeight = 21, Foreground = (Brush)FindResource("SubText") });
        }

        private void BuildTracksKeepFocus()
        {
            var focused = Keyboard.FocusedElement as Button;
            var index = focused != null ? TracksContent.Children.IndexOf(focused) : -1;
            BuildTracks();
            if (index >= 0 && index < TracksContent.Children.Count)
            {
                var b = TracksContent.Children[index] as Button;
                if (b != null) b.Focus();
            }
        }

        private void ChangeSubs(Action<Settings> change)
        {
            var s = App.Store.Settings.Copy();
            change(s);
            App.Store.SaveSettings(s);
            BuildTracksKeepFocus();
            Osd("Subtitle style saved — used from the next video");
        }

        private void LoadSubtitleFile()
        {
            var dlg = new Microsoft.Win32.OpenFileDialog
            {
                Title = "Choose a subtitle file",
                Filter = "Subtitles|*.srt;*.vtt;*.ass;*.ssa;*.sub;*.txt|All files|*.*",
            };
            var folder = Current.LocalPath != null ? Path.GetDirectoryName(Current.LocalPath) : KnownFolders.Downloads();
            if (Directory.Exists(folder)) dlg.InitialDirectory = folder;
            var was = _fullScreen;
            if (dlg.ShowDialog(this) == true)
            {
                var ok = _mp.AddSlave(MediaSlaveType.Subtitle, new Uri(dlg.FileName).AbsoluteUri, true);
                Osd(ok ? "Subtitles: " + Path.GetFileName(dlg.FileName) : "This subtitle file couldn't be loaded");
                Dispatcher.BeginInvoke(new Action(BuildTracksKeepFocus), DispatcherPriority.Background);
            }
            if (was) Activate();
        }

        /// <summary>Resolution, codecs, frame rate, audio and the data speed from the server.</summary>
        private string StreamInfo()
        {
            var sb = new StringBuilder();
            try
            {
                uint w = 0, h = 0;
                if (_mp.Size(0, ref w, ref h) && w > 0) sb.AppendLine("Picture: " + w + " × " + h + (h >= 2000 ? "  (4K)" : h >= 1000 ? "  (Full HD)" : h >= 700 ? "  (HD)" : "  (SD)"));
                var media = _mp.Media;
                if (media != null)
                {
                    foreach (var t in media.Tracks)
                    {
                        if (t.TrackType == TrackType.Video)
                        {
                            var fps = t.Data.Video.FrameRateDen > 0 ? (double)t.Data.Video.FrameRateNum / t.Data.Video.FrameRateDen : 0;
                            sb.AppendLine("Video: " + Playback.FourCC(t.Codec) + (fps > 0 ? "  ·  " + fps.ToString("0.##") + " fps" : ""));
                        }
                        else if (t.TrackType == TrackType.Audio)
                        {
                            sb.AppendLine("Audio: " + Playback.FourCC(t.Codec) + "  ·  " + t.Data.Audio.Channels + " ch  ·  " + t.Data.Audio.Rate + " Hz" +
                                          (string.IsNullOrEmpty(t.Language) ? "" : "  ·  " + t.Language));
                        }
                        else if (t.TrackType == TrackType.Text)
                        {
                            sb.AppendLine("Subtitle: " + Playback.FourCC(t.Codec) + (string.IsNullOrEmpty(t.Language) ? "" : "  ·  " + t.Language));
                        }
                    }
                    var stats = media.Statistics;
                    if (stats.InputBitrate > 0) sb.AppendLine("Data speed: " + (stats.InputBitrate * 8000).ToString("0") + " kbit/s");
                    if (stats.LostPictures > 0) sb.AppendLine("Pictures dropped: " + stats.LostPictures);
                }
            }
            catch (Exception e) { App.Log(e); }
            sb.AppendLine("Source: " + (_source == Source.LiveBuffer ? "disk buffer (live)" : _source == Source.FilmBuffer ? "disk buffer" :
                _source == Source.LocalFile ? "file on this PC" : _source == Source.Growing ? "download in progress" : "direct from the server"));
            if (_live != null && _live.Relay.BytesPerSecond > 0) sb.AppendLine("Channel speed: " + (_live.Relay.BytesPerSecond * 8 / 1000).ToString("0") + " kbit/s");
            if (_softwareOnly) sb.AppendLine("Watchdog: safer settings (no graphics-card decoding)");
            return sb.ToString().TrimEnd();
        }

        private void OnTracksKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Up || e.Key == Key.Down)
            {
                var buttons = TracksContent.Children.OfType<Button>().ToList();
                var i = buttons.IndexOf(Keyboard.FocusedElement as Button);
                var j = Math.Max(0, Math.Min(buttons.Count - 1, i + (e.Key == Key.Down ? 1 : -1)));
                if (buttons.Count > 0) { buttons[j].Focus(); buttons[j].BringIntoView(); }
                if (e.Key == Key.Down && j == buttons.Count - 1) TracksScroll.ScrollToEnd();
                e.Handled = true;
            }
        }

        // ---------------------------------------------------------------- closing

        private void OnClosing(object sender, CancelEventArgs e)
        {
            if (_stopped) return;
            // Stop VLC before its window disappears (closing a network stream can take a moment).
            e.Cancel = true;
            if (_closing) return;
            SaveResume();
            _closing = true;
            _tick.Stop(); _hide.Stop(); _retry.Stop(); _switch.Stop(); _osd.Stop(); _digitsTimer.Stop(); _overlayOpen.Stop();
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
                    if (Current.Hls) App.Hub.StopServing(StreamHub.Id(Current.Url));
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
            try { if (_vlc != null) _vlc.Dispose(); } catch { }
            _done.TrySetResult(_index);
            var after = _afterClose;
            _afterClose = null;
            if (after != null) Task.Delay(800).ContinueWith(t => after());
        }
    }
}
