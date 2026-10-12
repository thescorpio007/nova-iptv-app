using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Nova.Core;

namespace Nova.App
{
    /// <summary>Settings: accounts, playback, Live TV, posters, general, about. Changes are saved at once.</summary>
    public partial class SettingsView : UserControl, IView, IReloadable
    {
        public class AccountCard : Notify
        {
            public Account Account { get; set; }
            public string Name { get { return Account.DisplayName; } }
            public string Server { get { return Account.Username + "  @  " + XtreamApi.NormalizeServer(Account.Server); } }
            private string _status = "Checking…";
            public string Status { get { return _status; } set { _status = value; Changed(); } }
            private Brush _brush = (Brush)Application.Current.Resources["DimText"];
            public Brush StatusBrush { get { return _brush; } set { _brush = value; Changed(); } }
        }

        private bool _filling;
        private readonly List<Action> _refresh = new List<Action>();

        public SettingsView()
        {
            InitializeComponent();
            BuildRows();
            Fill();
            About.Text = "Nova IPTV for Windows " + Brand.Version + "  ·  32-bit\n" +
                         "Video engine: " + (App.Vlc != null ? "VLC " + App.Vlc.Version : "not started") + "\n" +
                         "Data folder: " + App.Store.DataFolder;
            Signature.Text = Brand.Signature;
            PinGate.Changed += () => Dispatcher.BeginInvoke(new Action(() => { if (IsVisible) { FillLock(); FillPin(); } }));
            App.Store.OrgChanged += () => Dispatcher.BeginInvoke(new Action(() => { if (IsVisible) { FillPin(); FillHidden(); } }));
        }

        public void OnShown()
        {
            Fill();
            LoadAccounts();
        }

        public void OnHidden() { }

        public void FocusStart()
        {
            if (LockBar.Visibility == Visibility.Visible) { UnlockBtn.Focus(); return; }
            AddAccountBtn.Focus();
            var firstEdit = FindFirst<Button>(AccountList);
            if (firstEdit != null) firstEdit.Focus();
        }

        public void Reload()
        {
            if (IsVisible) { LoadAccounts(); FillLink(); FillConnHint(); }
        }

        // ---------------------------------------------------------------- accounts

        private async void LoadAccounts()
        {
            var cards = App.Store.Accounts.Select(a => new AccountCard { Account = a }).ToList();
            AccountList.ItemsSource = cards;
            foreach (var c in cards)
            {
                try
                {
                    var info = await App.Catalog.Api.Authenticate(c.Account);
                    c.Status = info.Summary;
                    c.StatusBrush = (Brush)FindResource("Accent");
                }
                catch (Exception e)
                {
                    c.Status = "Can't sign in: " + e.Message;
                    c.StatusBrush = (Brush)FindResource("ErrorBrush");
                }
            }
        }

        private void OnAddAccount(object sender, RoutedEventArgs e)
        {
            AccountDialog.Edit(Window.GetWindow(this), null); // the list refreshes through Reload()
        }

        private void OnEditAccount(object sender, RoutedEventArgs e)
        {
            var a = ((FrameworkElement)sender).Tag as Account;
            if (a != null) AccountDialog.Edit(Window.GetWindow(this), a);
        }

        private void OnRemoveAccount(object sender, RoutedEventArgs e)
        {
            var a = ((FrameworkElement)sender).Tag as Account;
            if (a == null) return;
            if (!NovaDialog.Ask(Window.GetWindow(this), "Remove this account?", a.DisplayName + "\nIts favorites are removed too.", "Yes, remove")) return;
            App.Store.RemoveAccount(a.Id);
        }

        // ---------------------------------------------------------------- settings

        private void Fill()
        {
            _filling = true;
            var s = App.Store.Settings;
            (s.LiveFormat == "m3u8" ? FmtHls : FmtTs).IsChecked = true;
            var buf = s.NetworkCachingMs;
            (buf <= 1500 ? Buf1 : buf <= 3500 ? Buf3 : buf <= 7000 ? Buf5 : Buf10).IsChecked = true;
            DiskBuffer.IsChecked = s.UseDiskBuffer;
            BufferPath.Text = KnownFolders.Buffer(s);
            DownloadPath.Text = KnownFolders.NovaDownloads(s);
            (s.LiveBufferMb <= 300 ? Live300 : s.LiveBufferMb <= 600 ? Live600 : s.LiveBufferMb <= 1000 ? Live1000 : Live2000).IsChecked = true;
            (s.FilmBufferMb <= 1024 ? Film1 : s.FilmBufferMb <= 2048 ? Film2 : s.FilmBufferMb <= 4096 ? Film4 : Film8).IsChecked = true;
            (s.CushionSeconds <= 3 ? Cush3 : s.CushionSeconds <= 6 ? Cush6 : s.CushionSeconds <= 10 ? Cush10 : Cush20).IsChecked = true;
            (s.StallSeconds <= 6 ? Stall6 : s.StallSeconds <= 10 ? Stall10 : Stall20).IsChecked = true;
            AutoSkip.IsChecked = s.AutoSkip;
            (s.DeadSeconds <= 10 ? Dead10 : s.DeadSeconds <= 15 ? Dead15 : Dead30).IsChecked = true;
            HwDecode.IsChecked = s.HardwareDecoding;
            FullScreen.IsChecked = s.PlayerFullScreen;
            Preview.IsChecked = s.LivePreview;
            Logos.IsChecked = s.LiveLogos;
            TvArrows.IsChecked = s.TvArrows;
            OpenOnFocus.IsChecked = s.OpenGroupOnFocus;
            (s.PosterSize == "small" ? PSmall : s.PosterSize == "large" ? PLarge : PMedium).IsChecked = true;
            PosterTitles.IsChecked = s.PosterTitles;
            DoubleBack.IsChecked = s.DoubleBackExit;
            AgentBox.Text = s.UserAgent;
            NumberKeys.IsChecked = s.NumberKeys;
            AutoPlayLast.IsChecked = s.AutoPlayLast;
            ExternalOnError.IsChecked = s.ExternalOnError;
            SubBackground.IsChecked = s.SubtitleBackground;
            NowLine.IsChecked = s.LiveNowLine;
            RequireVpn.IsChecked = s.RequireVpn;
            bool shell;
            try { shell = ShellMenu.IsAdded(); } catch { shell = false; }
            ShellMenuBox.IsChecked = shell;
            foreach (var r in _refresh) r();
            _filling = false;
            FillLink();
            FillPin();
            FillHidden();
            FillLock();
            FillVpn();
            FillConnHint();
        }

        private void OnChanged(object sender, RoutedEventArgs e)
        {
            if (_filling || !IsLoaded) return;
            var s = App.Store.Settings.Copy();
            s.LiveFormat = FmtHls.IsChecked == true ? "m3u8" : "ts";
            s.NetworkCachingMs = Buf1.IsChecked == true ? 1000 : Buf3.IsChecked == true ? 3000 : Buf5.IsChecked == true ? 5000 : 10000;
            s.UseDiskBuffer = DiskBuffer.IsChecked == true;
            s.LiveBufferMb = Live300.IsChecked == true ? 300 : Live600.IsChecked == true ? 600 : Live1000.IsChecked == true ? 1000 : 2000;
            s.FilmBufferMb = Film1.IsChecked == true ? 1024 : Film2.IsChecked == true ? 2048 : Film4.IsChecked == true ? 4096 : 8192;
            s.CushionSeconds = Cush3.IsChecked == true ? 3 : Cush6.IsChecked == true ? 6 : Cush10.IsChecked == true ? 10 : 20;
            s.StallSeconds = Stall6.IsChecked == true ? 6 : Stall10.IsChecked == true ? 10 : 20;
            s.AutoSkip = AutoSkip.IsChecked == true;
            s.DeadSeconds = Dead10.IsChecked == true ? 10 : Dead15.IsChecked == true ? 15 : 30;
            s.HardwareDecoding = HwDecode.IsChecked == true;
            s.PlayerFullScreen = FullScreen.IsChecked == true;
            s.LivePreview = Preview.IsChecked == true;
            s.LiveLogos = Logos.IsChecked == true;
            s.TvArrows = TvArrows.IsChecked == true;
            s.OpenGroupOnFocus = OpenOnFocus.IsChecked == true;
            s.PosterSize = PSmall.IsChecked == true ? "small" : PLarge.IsChecked == true ? "large" : "medium";
            s.PosterTitles = PosterTitles.IsChecked == true;
            s.DoubleBackExit = DoubleBack.IsChecked == true;
            s.NumberKeys = NumberKeys.IsChecked == true;
            s.AutoPlayLast = AutoPlayLast.IsChecked == true;
            s.ExternalOnError = ExternalOnError.IsChecked == true;
            s.SubtitleBackground = SubBackground.IsChecked == true;
            s.LiveNowLine = NowLine.IsChecked == true;
            s.RequireVpn = RequireVpn.IsChecked == true;
            App.Store.SaveSettings(s);
        }

        // ---------------------------------------------------------------- folders

        private static string PickFolder(string title, string current)
        {
            using (var dlg = new System.Windows.Forms.FolderBrowserDialog { Description = title, ShowNewFolderButton = true, SelectedPath = current })
            {
                return dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK ? dlg.SelectedPath : null;
            }
        }

        private static bool Writable(string folder)
        {
            try
            {
                Directory.CreateDirectory(folder);
                var test = Path.Combine(folder, ".nova-test");
                File.WriteAllText(test, "ok");
                File.Delete(test);
                return true;
            }
            catch { return false; }
        }

        private void SetFolder(bool buffer, string path)
        {
            if (path != null && !Writable(path)) { App.Home.Toast("Nova can't write in that folder. Choose another one."); return; }
            var s = App.Store.Settings.Copy();
            if (buffer) s.BufferFolder = path ?? ""; else s.DownloadFolder = path ?? "";
            App.Store.SaveSettings(s);
            BufferPath.Text = KnownFolders.Buffer(s);
            DownloadPath.Text = KnownFolders.NovaDownloads(s);
            App.Home.Toast(buffer ? "Buffer folder: " + BufferPath.Text + " (used from the next stream)" : "Downloads go to " + DownloadPath.Text + " from now on");
        }

        private void OnPickBuffer(object sender, RoutedEventArgs e)
        {
            var p = PickFolder("Folder for Nova's disk buffer (any drive)", KnownFolders.Buffer(App.Store.Settings));
            if (p != null) SetFolder(true, p);
        }

        private void OnPickDownloads(object sender, RoutedEventArgs e)
        {
            var p = PickFolder("Folder for downloads and recordings", KnownFolders.NovaDownloads(App.Store.Settings));
            if (p != null) SetFolder(false, p);
        }

        private void OnDefaultBuffer(object sender, RoutedEventArgs e) { SetFolder(true, null); }
        private void OnDefaultDownloads(object sender, RoutedEventArgs e) { SetFolder(false, null); }
        private void OnOpenBuffer(object sender, RoutedEventArgs e) { OpenFolder(KnownFolders.Buffer(App.Store.Settings)); }
        private void OnOpenDownloads(object sender, RoutedEventArgs e) { OpenFolder(KnownFolders.NovaDownloads(App.Store.Settings)); }

        private static void OpenFolder(string path)
        {
            try { Directory.CreateDirectory(path); Process.Start("explorer.exe", "\"" + path + "\""); }
            catch (Exception ex) { App.Home.Toast(ex.Message); }
        }

        private void OnAgentDone(object sender, KeyboardFocusChangedEventArgs e)
        {
            var text = AgentBox.Text.Trim();
            if (text.Length == 0) text = Store.DefaultUserAgent;
            if (text == App.Store.Settings.UserAgent) return;
            var s = App.Store.Settings.Copy();
            s.UserAgent = text;
            App.Store.SaveSettings(s);
            App.Home.Toast("User agent saved");
        }

        private void OnResetAgent(object sender, RoutedEventArgs e)
        {
            AgentBox.Text = Store.DefaultUserAgent;
            var s = App.Store.Settings.Copy();
            s.UserAgent = Store.DefaultUserAgent;
            App.Store.SaveSettings(s);
            App.Home.Toast("User agent reset");
        }

        private void OnClearImages(object sender, RoutedEventArgs e)
        {
            Img.ClearCache();
            App.Home.Toast("Picture cache cleared");
        }

        private void OnOpenData(object sender, RoutedEventArgs e)
        {
            try { Process.Start("explorer.exe", "\"" + App.Store.DataFolder + "\""); }
            catch (Exception ex) { App.Home.Toast(ex.Message); }
        }

        // ---------------------------------------------------------------- choice rows

        private static Tuple<string, string> T(string code, string label) { return Tuple.Create(code, label); }

        /// <summary>A line "Label ……… [current value]"; the button opens the list of choices (easy with a remote).</summary>
        private void Pick(Panel host, string label, Func<string> current, Func<List<Tuple<string, string>>> options, Action<string> apply)
        {
            var row = new DockPanel { Margin = new Thickness(0, 4, 0, 4) };
            var btn = new Button { Style = (Style)FindResource("NovaButton"), MinWidth = 240, HorizontalContentAlignment = HorizontalAlignment.Left };
            DockPanel.SetDock(btn, Dock.Right);
            var text = new TextBlock { Text = label, VerticalAlignment = VerticalAlignment.Center, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 12, 0) };
            row.Children.Add(btn);
            row.Children.Add(text);
            host.Children.Add(row);
            Action refresh = () =>
            {
                var cur = current();
                var o = options().FirstOrDefault(x => x.Item1 == cur);
                btn.Content = (o != null ? o.Item2 : cur) + "  ▾";
            };
            _refresh.Add(refresh);
            btn.Click += (s, e) =>
            {
                var list = options();
                var i = NovaDialog.Choose(Window.GetWindow(this), label, null, list.Select(x => x.Item2).ToList(), list.FindIndex(x => x.Item1 == current()));
                if (i < 0) return;
                apply(list[i].Item1);
                refresh();
                btn.Focus();
            };
        }

        private void Change(Action<Settings> change)
        {
            var s = App.Store.Settings.Copy();
            change(s);
            App.Store.SaveSettings(s);
        }

        private static int Int(string s) { int n; return int.TryParse(s, out n) ? n : 0; }

        private void BuildRows()
        {
            // ---- watchdog
            Pick(WatchdogRows, "When a channel's picture gets stuck", () => App.Store.Settings.WatchdogMode,
                () => new List<Tuple<string, string>> { T("live", "Jump back to live"), T("full", "Full repair (safer settings, then the other account)") },
                v => Change(s => s.WatchdogMode = v));
            Pick(WatchdogRows, "Full repair may use the same channel on another account", () => App.Store.Settings.WatchdogOtherAccount ? "yes" : "no",
                () => new List<Tuple<string, string>> { T("yes", "Yes"), T("no", "No") },
                v => Change(s => s.WatchdogOtherAccount = v == "yes"));

            // ---- downloads
            var conns = new List<Tuple<string, string>> { T("1", "1 connection"), T("2", "2 connections"), T("4", "4 connections"), T("8", "8 connections"), T("16", "16 connections") };
            var speeds = new List<Tuple<string, string>> { T("0", "No limit"), T("1", "1 MB/s"), T("2", "2 MB/s"), T("5", "5 MB/s"), T("10", "10 MB/s"), T("20", "20 MB/s") };
            Pick(DownloadRows, "Connections per download from your IPTV servers", () => App.Store.Settings.DlConnServers.ToString(), () => conns,
                v => { Change(s => s.DlConnServers = Int(v)); FillConnHint(); });
            Pick(DownloadRows, "Speed limit, IPTV servers", () => App.Store.Settings.DlSpeedServers.ToString(), () => speeds, v => Change(s => s.DlSpeedServers = Int(v)));
            Pick(DownloadRows, "Connections per download from other links", () => App.Store.Settings.DlConnLinks.ToString(), () => conns, v => Change(s => s.DlConnLinks = Int(v)));
            Pick(DownloadRows, "Speed limit, other links", () => App.Store.Settings.DlSpeedLinks.ToString(), () => speeds, v => Change(s => s.DlSpeedLinks = Int(v)));

            // ---- players
            PlayerPick("Live TV", () => App.Store.Settings.PlayerLive, (s, v) => s.PlayerLive = v);
            PlayerPick("Films", () => App.Store.Settings.PlayerFilms, (s, v) => s.PlayerFilms = v);
            PlayerPick("Series", () => App.Store.Settings.PlayerSeries, (s, v) => s.PlayerSeries = v);
            PlayerPick("Catch-up", () => App.Store.Settings.PlayerCatchup, (s, v) => s.PlayerCatchup = v);
            PlayerPick("Files and links", () => App.Store.Settings.PlayerFiles, (s, v) => s.PlayerFiles = v);
            Pick(PlayerRows, "External player (E key, and when Nova can't play)", () => string.IsNullOrEmpty(App.Store.Settings.ExternalPlayer) ? "" : App.Store.Settings.ExternalPlayer,
                () =>
                {
                    var l = new List<Tuple<string, string>> { T("", "First one found") };
                    l.AddRange(ExternalPlayers.Installed().Select(p => T(p.Path, p.Name)));
                    l.Add(T("?", "Choose a program…"));
                    return l;
                },
                v =>
                {
                    if (v == "?") { v = ChooseProgram(); if (v == null) return; }
                    Change(s => s.ExternalPlayer = v);
                });

            // ---- subtitles
            Pick(SubtitleRows, "Subtitle size", () => App.Store.Settings.SubtitleSize.ToString(),
                () => new List<Tuple<string, string>> { T("14", "Small"), T("20", "Normal"), T("26", "Large"), T("32", "Very large"), T("40", "Huge") },
                v => Change(s => s.SubtitleSize = Int(v)));
            Pick(SubtitleRows, "Subtitle colour", () => App.Store.Settings.SubtitleColor,
                () => new List<Tuple<string, string>> { T("white", "White"), T("yellow", "Yellow"), T("green", "Green"), T("cyan", "Cyan") },
                v => Change(s => s.SubtitleColor = v));

            // ---- live TV
            Pick(LiveRows, "Channel row height", () => App.Store.Settings.LiveRowSize,
                () => new List<Tuple<string, string>> { T("compact", "Compact"), T("normal", "Normal"), T("large", "Large") },
                v => Change(s => s.LiveRowSize = v));
            Pick(LiveRows, "Clock in the player", () => App.Store.Settings.ClockPosition,
                () => new List<Tuple<string, string>> { T("off", "Off"), T("tl", "Top left"), T("tr", "Top right"), T("bl", "Bottom left"), T("br", "Bottom right") },
                v => Change(s => s.ClockPosition = v));
            Pick(LiveRows, "Lists over the video: transparency", () => App.Store.Settings.OverlayTransparency.ToString(),
                () => new List<Tuple<string, string>> { T("0", "Solid"), T("15", "15 %"), T("25", "25 %"), T("40", "40 %"), T("60", "60 %") },
                v => Change(s => s.OverlayTransparency = Int(v)));
            Pick(LiveRows, "Sort Live TV", () => App.Store.Settings.SortLive, () => Organize.SortChoices("live"), v => Change(s => s.SortLive = v));

            // ---- films & series
            Pick(VodRows, "Posters per row", () => App.Store.Settings.PosterColumns.ToString(),
                () => new List<Tuple<string, string>> { T("0", "Automatic (fits the window)"), T("4", "4"), T("5", "5"), T("6", "6"), T("7", "7"), T("8", "8"), T("10", "10") },
                v => Change(s => s.PosterColumns = Int(v)));
            Pick(VodRows, "Sort films", () => App.Store.Settings.SortMovie, () => Organize.SortChoices("movie"), v => Change(s => s.SortMovie = v));
            Pick(VodRows, "Sort series", () => App.Store.Settings.SortSeries, () => Organize.SortChoices("series"), v => Change(s => s.SortSeries = v));
        }

        private void PlayerPick(string label, Func<string> current, Action<Settings, string> set)
        {
            Pick(PlayerRows, label, () => string.IsNullOrEmpty(current()) ? ExternalPlayers.Builtin : current(),
                () =>
                {
                    var l = new List<Tuple<string, string>> { T(ExternalPlayers.Builtin, "Nova (built-in)") };
                    l.AddRange(ExternalPlayers.Installed().Select(p => T(p.Path, p.Name)));
                    l.Add(T("?", "Choose a program…"));
                    return l;
                },
                v =>
                {
                    if (v == "?") { v = ChooseProgram(); if (v == null) return; }
                    Change(s => set(s, v));
                });
        }

        private string ChooseProgram()
        {
            var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Choose a video player program", Filter = "Programs|*.exe" };
            return dlg.ShowDialog(Window.GetWindow(this)) == true ? dlg.FileName : null;
        }

        private void FillConnHint()
        {
            var parts = App.Store.Accounts.Select(a =>
            {
                var n = App.Catalog.MaxConnections(a.Id);
                return a.DisplayName + ": " + (n > 0 ? n + (n == 1 ? " connection" : " connections") + " allowed" : "not known yet");
            }).ToList();
            ConnHint.Text = "Nova never uses more connections than your server allows and always keeps one free for watching." +
                            (parts.Count > 0 ? "\n" + string.Join("  ·  ", parts) : "");
        }

        // ---------------------------------------------------------------- lock

        private void FillLock()
        {
            var locked = App.Store.HasPin && !PinGate.IsUnlocked;
            LockBar.Visibility = locked ? Visibility.Visible : Visibility.Collapsed;
            Body.IsEnabled = !locked;
            Body.Opacity = locked ? 0.45 : 1;
        }

        private void OnUnlock(object sender, RoutedEventArgs e)
        {
            if (PinGate.Unlock(Window.GetWindow(this), "Settings are protected by your PIN.")) { FillLock(); FillPin(); AddAccountBtn.Focus(); }
        }

        // ---------------------------------------------------------------- accounts link

        private void FillLink()
        {
            var st = App.Store.State;
            var on = !string.IsNullOrEmpty(st.AccountsLink);
            if (on)
            {
                var when = st.AccountsLinkChecked > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(st.AccountsLinkChecked).LocalDateTime.ToString("d MMM HH:mm") : "never";
                LinkStatus.Text = "Connected: " + st.AccountsLink + (st.AccountsLinkPassword != null ? "  (protected)" : "") +
                                  "\nChecked every day · last check " + when + (string.IsNullOrEmpty(st.AccountsLinkNote) ? "" : " · " + st.AccountsLinkNote);
            }
            else LinkStatus.Text = "Keep your accounts in one text file on OneDrive, Google Drive or Dropbox; Nova reads it every day (same as the box).";
            LinkConnectBtn.Content = on ? "Change the link…" : "Connect an accounts link…";
            LinkSyncBtn.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
            LinkStopBtn.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
        }

        private void OnLinkConnect(object sender, RoutedEventArgs e) { BackupUi.ConnectAccountsLink(Window.GetWindow(this)); }

        private async void OnLinkSync(object sender, RoutedEventArgs e)
        {
            LinkStatus.Text = "Checking…";
            var note = await BackupUi.SyncAccountsLink();
            App.Home.Toast(note);
            FillLink();
        }

        private void OnLinkStop(object sender, RoutedEventArgs e) { BackupUi.DisconnectAccountsLink(Window.GetWindow(this)); FillLink(); }
        private void OnSaveList(object sender, RoutedEventArgs e) { BackupUi.SaveAccountsList(Window.GetWindow(this)); }

        // ---------------------------------------------------------------- backup

        private void OnBackup(object sender, RoutedEventArgs e) { BackupUi.Save(Window.GetWindow(this)); }
        private void OnRestore(object sender, RoutedEventArgs e) { BackupUi.Restore(Window.GetWindow(this)); Fill(); }
        private void OnOpenBackups(object sender, RoutedEventArgs e) { OpenFolder(KnownFolders.Backups(App.Store.Settings)); }

        // ---------------------------------------------------------------- PIN and hidden items

        private void FillPin()
        {
            if (!App.Store.HasPin)
            {
                PinStatus.Text = "No PIN. With a PIN, hiding, reordering, hidden items and these settings need the PIN (then stay open 5 minutes).";
                PinSetBtn.Content = "Set a PIN…";
                PinRemoveBtn.Visibility = Visibility.Collapsed;
                PinLockBtn.Visibility = Visibility.Collapsed;
            }
            else
            {
                PinStatus.Text = PinGate.IsUnlocked ? "PIN is on · unlocked for a few minutes." : "PIN is on · locked.";
                PinSetBtn.Content = "Change the PIN…";
                PinRemoveBtn.Visibility = Visibility.Visible;
                PinLockBtn.Visibility = PinGate.IsUnlocked ? Visibility.Visible : Visibility.Collapsed;
            }
        }

        private void OnPinSet(object sender, RoutedEventArgs e)
        {
            var w = Window.GetWindow(this);
            if (App.Store.HasPin && !PinGate.Unlock(w, "Enter your current PIN first.")) return;
            var pin = NovaDialog.AskText(w, App.Store.HasPin ? "New PIN" : "Choose a PIN", "4 to 8 digits.", null, true, "Next",
                p => p.Length >= 4 && p.Length <= 8 && p.All(char.IsDigit) ? null : "Use 4 to 8 digits.");
            if (pin == null) return;
            var again = NovaDialog.AskText(w, "Confirm the PIN", "Type the PIN again.", null, true, "Save", p => p == pin ? null : "The two PINs are not the same.");
            if (again == null) return;
            App.Store.SetPin(pin);
            PinGate.Grant();
            App.Home.Toast("PIN saved");
            FillPin();
            FillHidden();
        }

        private void OnPinRemove(object sender, RoutedEventArgs e)
        {
            var w = Window.GetWindow(this);
            if (!PinGate.Unlock(w, "Enter your PIN to remove it.")) return;
            if (!NovaDialog.Ask(w, "Remove the PIN?", "Hiding, reordering and the settings will no longer need a PIN.", "Yes, remove")) return;
            App.Store.SetPin(null);
            PinGate.Lock();
            App.Home.Toast("PIN removed");
            FillPin();
            FillHidden();
            FillLock();
        }

        private void OnPinLock(object sender, RoutedEventArgs e)
        {
            PinGate.Lock();
            App.Home.Toast("Locked");
            FillPin();
            FillLock();
            if (LockBar.Visibility == Visibility.Visible) UnlockBtn.Focus();
        }

        private static string ZoneName(string zone)
        {
            return zone == "live" ? "Live TV" : zone == "movie" ? "Films" : zone == "series" ? "Series" : zone;
        }

        private void FillHidden()
        {
            HiddenList.Children.Clear();
            var org = App.Store.Org;
            var all = org.HiddenGroups.Select(k => Tuple.Create(k, true)).Concat(org.HiddenItems.Select(k => Tuple.Create(k, false))).ToList();
            HiddenTitle.Text = all.Count == 0 ? "Nothing hidden. Hide groups, channels and posters with ✎ Edit (or Enter twice on a poster)."
                : "Hidden items (" + all.Count + ")";
            if (all.Count == 0) return;
            if (App.Store.HasPin && !PinGate.IsUnlocked)
            {
                var b = new Button { Style = (Style)FindResource("NovaButton"), Content = "Show the list (PIN)", HorizontalAlignment = HorizontalAlignment.Left };
                b.Click += (s, e) => { if (PinGate.Unlock(Window.GetWindow(this), "The hidden list is protected by your PIN.")) FillHidden(); };
                HiddenList.Children.Add(b);
                return;
            }
            foreach (var h in all.OrderBy(x => x.Item1))
            {
                var key = h.Item1;
                var group = h.Item2;
                var bar = key.IndexOf('|');
                var zone = bar > 0 ? key.Substring(0, bar) : "";
                string name;
                if (!org.Names.TryGetValue(key, out name) || string.IsNullOrEmpty(name)) name = key.Substring(bar + 1);
                var row = new DockPanel { Margin = new Thickness(0, 2, 0, 2) };
                var btn = new Button { Style = (Style)FindResource("NovaButton"), Content = "Show again" };
                DockPanel.SetDock(btn, Dock.Right);
                btn.Click += (s, e) =>
                {
                    var w = Window.GetWindow(this);
                    if (!NovaDialog.Ask(w, "Show it again?", name, "Yes, show it")) return;
                    if (!PinGate.Unlock(w, "Your PIN protects hidden items.")) return;
                    App.Store.UpdateOrg(o =>
                    {
                        if (group) o.HiddenGroups.Remove(key); else o.HiddenItems.Remove(key);
                        o.Names.Remove(key);
                    });
                    App.Home.Toast("Shown again: " + name);
                    FillHidden();
                };
                row.Children.Add(btn);
                row.Children.Add(new TextBlock
                {
                    Text = name + "   ·  " + ZoneName(zone) + (group ? " group" : ""),
                    VerticalAlignment = VerticalAlignment.Center,
                    TextTrimming = TextTrimming.CharacterEllipsis
                });
                HiddenList.Children.Add(row);
            }
        }

        // ---------------------------------------------------------------- VPN

        private void FillVpn()
        {
            var v = Vpn.Active();
            VpnStatus.Text = v != null ? "✓ VPN connected: " + v : "No VPN connection found.";
            VpnStatus.Foreground = (Brush)FindResource(v != null ? "Accent" : "SubText");
        }

        private void OnVpnCheck(object sender, RoutedEventArgs e) { FillVpn(); }

        // ---------------------------------------------------------------- right-click menu

        private void OnShellMenu(object sender, RoutedEventArgs e)
        {
            if (_filling || !IsLoaded) return;
            try
            {
                if (ShellMenuBox.IsChecked == true) ShellMenu.Add(Process.GetCurrentProcess().MainModule.FileName);
                else ShellMenu.Remove();
                App.Home.Toast(ShellMenuBox.IsChecked == true ? "“Open with Nova” added to the right-click menu" : "“Open with Nova” removed from the right-click menu");
            }
            catch (Exception ex) { App.Home.Toast("Couldn't change the right-click menu: " + ex.Message); }
        }

        // ---------------------------------------------------------------- remote control

        private void OnKeysToggle(object sender, RoutedEventArgs e)
        {
            var show = KeyRows.Visibility != Visibility.Visible;
            KeyRows.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
            KeysToggle.Content = show ? "Hide the buttons ▴" : "Show the buttons ▾";
            if (show) FillKeys();
        }

        private void OnKeysReset(object sender, RoutedEventArgs e)
        {
            if (!NovaDialog.Ask(Window.GetWindow(this), "Reset all buttons?", "Every action goes back to its usual buttons.", "Yes, reset")) return;
            KeyMap.ResetAll();
            if (KeyRows.Visibility == Visibility.Visible) FillKeys();
            App.Home.Toast("Buttons reset");
        }

        private void FillKeys()
        {
            KeyRows.Children.Clear();
            foreach (var area in new[] { KeyArea.Live, KeyArea.Vod, KeyArea.Lists })
            {
                KeyRows.Children.Add(new TextBlock
                {
                    Text = area == KeyArea.Live ? "Live TV player" : area == KeyArea.Vod ? "Films & series player" : "Lists and menus",
                    FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 12, 0, 4), Foreground = (Brush)FindResource("Accent")
                });
                foreach (var a in KeyMap.Actions.Where(x => x.Area == area))
                {
                    var action = a;
                    var row = new DockPanel { Margin = new Thickness(0, 2, 0, 2) };
                    var add = new Button { Style = (Style)FindResource("NovaButton"), Content = "Add a button", Margin = new Thickness(8, 0, 0, 0) };
                    var clear = new Button { Style = (Style)FindResource("NovaButton"), Content = "Clear", Margin = new Thickness(8, 0, 0, 0) };
                    DockPanel.SetDock(clear, Dock.Right);
                    DockPanel.SetDock(add, Dock.Right);
                    var keys = KeyMap.KeysFor(action.Id);
                    var label = new TextBlock { VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
                    label.Inlines.Add(new System.Windows.Documents.Run(action.Label));
                    label.Inlines.Add(new System.Windows.Documents.Run("   " + (keys.Count == 0 ? "(no button)" : string.Join("  ·  ", keys.Select(KeyMap.Pretty))))
                    { Foreground = (Brush)FindResource("SubText") });
                    add.Click += (s, e) =>
                    {
                        var key = CaptureKey(action.Label);
                        if (key == null) return;
                        var taken = KeyMap.Assign(action.Id, key);
                        App.Home.Toast(KeyMap.Pretty(key) + " → " + action.Label + (taken.Count > 0 ? "  (taken from: " + string.Join(", ", taken) + ")" : ""));
                        FillKeys();
                    };
                    clear.Click += (s, e) =>
                    {
                        KeyMap.ClearKeys(action.Id);
                        FillKeys();
                    };
                    row.Children.Add(clear);
                    row.Children.Add(add);
                    row.Children.Add(label);
                    KeyRows.Children.Add(row);
                }
            }
        }

        /// <summary>A small window that waits for one button press (Esc twice cancels).</summary>
        private string CaptureKey(string action)
        {
            string result = null;
            var escapes = 0;
            var text = new TextBlock
            {
                Text = "Press the button for:\n" + action + "\n\n(Esc twice to cancel)",
                FontSize = 17, TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(30), Foreground = (Brush)FindResource("TextBrush")
            };
            var w = new Window
            {
                Owner = Window.GetWindow(this), Title = "Remote button", Width = 420, SizeToContent = SizeToContent.Height,
                WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize, ShowInTaskbar = false,
                Background = (Brush)FindResource("Surface"), Content = text
            };
            w.PreviewKeyDown += (s, e) =>
            {
                e.Handled = true;
                var k = e.Key == Key.System ? e.SystemKey : e.Key;
                if (k == Key.Escape && Keyboard.Modifiers == ModifierKeys.None)
                {
                    escapes++;
                    if (escapes >= 2) { w.Close(); return; }
                    text.Text = "Esc pressed.\nPress Esc again to cancel — otherwise Esc becomes the button in 2 seconds.";
                    var t = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
                    t.Tick += (a, b) => { t.Stop(); if (w.IsVisible && escapes == 1) { result = "Escape"; w.Close(); } };
                    t.Start();
                    return;
                }
                var name = KeyMap.NameOf(e);
                if (name == null) return;
                result = name;
                w.Close();
            };
            w.ShowDialog();
            return result;
        }

        private void OnScrollKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.PageDown) { e.Handled = true; Scroll.PageDown(); }
            else if (e.Key == Key.PageUp) { e.Handled = true; Scroll.PageUp(); }
        }

        private static T FindFirst<T>(DependencyObject root) where T : DependencyObject
        {
            var n = VisualTreeHelper.GetChildrenCount(root);
            for (int i = 0; i < n; i++)
            {
                var c = VisualTreeHelper.GetChild(root, i);
                var t = c as T;
                if (t != null) return t;
                var f = FindFirst<T>(c);
                if (f != null) return f;
            }
            return null;
        }
    }
}
