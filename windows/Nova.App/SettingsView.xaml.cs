using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Nova.Core;

namespace Nova.App
{
    /// <summary>
    /// Settings in the same 7 groups as the box: 👤 Accounts &amp; safety · 📡 Live TV · ▶ Player · 🎬 Films &amp; downloads ·
    /// 💾 Storage · 🖥 Screen &amp; remote · 🛠 System. Two columns on a wide window. Changes are saved at once.
    /// </summary>
    public partial class SettingsView : UserControl, IView, IReloadable
    {
        private readonly List<Action> _refresh = new List<Action>();
        private readonly List<Border> _groups = new List<Border>();
        private bool _filling;
        private bool _twoColumns;

        // Parts that are filled again when something changes.
        private StackPanel _accounts, _hiddenList, _keyRows, _extList;
        private TextBlock _linkStatus, _pinStatus, _hiddenTitle, _vpnStatus, _connHint, _indexStatus, _filterStatus, _buffers, _glance, _lastProblem, _about;
        private Button _linkConnect, _linkSync, _linkStop, _pinSet, _pinRemove, _pinLock, _keysToggle;
        private TextBox _agentBox;

        public SettingsView()
        {
            InitializeComponent();
            Build();
            Fill();
            PinGate.Changed += () => Dispatcher.BeginInvoke(new Action(() => { if (IsVisible) { FillLock(); FillPin(); FillHidden(); } }));
            App.Store.OrgChanged += () => Dispatcher.BeginInvoke(new Action(() => { if (IsVisible) { FillPin(); FillHidden(); } }));
            App.Filter.StatusChanged += () => Dispatcher.BeginInvoke(new Action(() => { if (IsVisible) FillFilterStatus(); }));
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
            var first = FindFirst<Button>(LeftCol);
            if (first != null) first.Focus();
        }

        public void Reload()
        {
            if (IsVisible) { LoadAccounts(); FillLink(); FillConnHint(); }
        }

        // ================================================================== building blocks

        private static Tuple<string, string> T(string code, string label) { return Tuple.Create(code, label); }
        private static int Int(string s) { int n; return int.TryParse(s, out n) ? n : 0; }
        private Window Owner { get { return Window.GetWindow(this); } }

        private StackPanel Group(string title)
        {
            var body = new StackPanel();
            body.Children.Add(new TextBlock { Text = title, FontSize = 17, FontWeight = FontWeights.Bold, Foreground = (Brush)FindResource("Accent"), Margin = new Thickness(0, 0, 0, 4) });
            var card = new Border
            {
                Background = (Brush)FindResource("Surface"), CornerRadius = new CornerRadius(10), Padding = new Thickness(18, 14, 18, 16),
                Margin = new Thickness(0, 10, 0, 6), Child = body,
            };
            _groups.Add(card);
            return body;
        }

        private static void Title(Panel p, string text)
        {
            p.Children.Add(new TextBlock { Text = text, FontSize = 15, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 16, 0, 4) });
        }

        private TextBlock Hint(Panel p, string text)
        {
            var t = new TextBlock { Text = text, Style = (Style)FindResource("Hint"), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 2, 0, 6) };
            p.Children.Add(t);
            return t;
        }

        private Button Btn(string text, Action click, bool accent = false)
        {
            var b = new Button { Style = (Style)FindResource(accent ? "AccentButton" : "NovaButton"), Content = text, Margin = new Thickness(0, 0, 8, 8) };
            b.Click += (s, e) => click();
            return b;
        }

        private WrapPanel Buttons(Panel p, params Button[] buttons)
        {
            var w = new WrapPanel { Margin = new Thickness(0, 4, 0, 0) };
            foreach (var b in buttons) w.Children.Add(b);
            p.Children.Add(w);
            return w;
        }

        private void Check(Panel p, string label, Func<bool> get, Action<bool> set)
        {
            var c = new CheckBox { Content = new TextBlock { Text = label, TextWrapping = TextWrapping.Wrap }, Margin = new Thickness(0, 3, 0, 3) };
            _refresh.Add(() => c.IsChecked = get());
            RoutedEventHandler h = (s, e) => { if (_filling || !IsLoaded) return; set(c.IsChecked == true); };
            c.Checked += h;
            c.Unchecked += h;
            p.Children.Add(c);
        }

        private void Change(Action<Settings> change)
        {
            var s = App.Store.Settings.Copy();
            change(s);
            App.Store.SaveSettings(s);
        }

        /// <summary>A line "Label ……… [value ▾]": the button opens the list of choices (easy with a remote).</summary>
        private void Pick(Panel host, string label, Func<string> current, Func<List<Tuple<string, string>>> options, Action<string> apply, string note = null)
        {
            var row = new DockPanel { Margin = new Thickness(0, 4, 0, 4) };
            var btn = new Button { Style = (Style)FindResource("NovaButton"), MinWidth = 220, HorizontalContentAlignment = HorizontalAlignment.Left };
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
                var i = NovaDialog.Choose(Owner, label, note, list.Select(x => x.Item2).ToList(), list.FindIndex(x => x.Item1 == current()));
                if (i < 0) return;
                apply(list[i].Item1);
                refresh();
                btn.Focus();
            };
        }

        private void FolderRow(Panel p, string label, Func<string> path, Action change, Action open, Action reset)
        {
            Title(p, label);
            var t = new TextBlock { TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center, Foreground = (Brush)FindResource("SubText") };
            _refresh.Add(() => t.Text = path());
            p.Children.Add(t);
            var w = Buttons(p, Btn("Change…", change), Btn("Open", open));
            if (reset != null) w.Children.Add(Btn("Default", reset));
        }

        private void OnSize(object sender, SizeChangedEventArgs e) { Layout(); }

        /// <summary>Two columns on a wide window (groups 1–3 left, 4–7 right), one column otherwise.</summary>
        private void Layout()
        {
            var two = ActualWidth >= 1250;
            if (two == _twoColumns && LeftCol.Children.Count + RightCol.Children.Count == _groups.Count) return;
            _twoColumns = two;
            LeftCol.Children.Clear();
            RightCol.Children.Clear();
            for (int i = 0; i < _groups.Count; i++) (two && i >= 3 ? RightCol : LeftCol).Children.Add(_groups[i]);
            RightWidth.Width = two ? new GridLength(1, GridUnitType.Star) : new GridLength(0);
            GapWidth.Width = two ? new GridLength(24) : new GridLength(0);
            LeftCol.HorizontalAlignment = HorizontalAlignment.Left;
            if (!two) LeftCol.Width = Math.Min(900, Math.Max(400, ActualWidth - 70)); else LeftCol.Width = double.NaN;
        }

        // ================================================================== the 7 groups

        private void Build()
        {
            BuildAccounts();
            BuildLive();
            BuildPlayer();
            BuildFilms();
            BuildStorage();
            BuildScreen();
            BuildSystem();
            Layout();
        }

        // ------------------------------------------------------------------ 1 · accounts & safety

        private void BuildAccounts()
        {
            var g = Group("👤  ACCOUNTS & SAFETY");
            Title(g, "Accounts");
            _accounts = new StackPanel();
            g.Children.Add(_accounts);
            Buttons(g, Btn("+  Add account", () => AccountDialog.Edit(Owner, null), true));

            Title(g, "Accounts link");
            _linkStatus = Hint(g, "");
            _linkConnect = Btn("Connect an accounts link…", () => BackupUi.ConnectAccountsLink(Owner));
            _linkSync = Btn("Check now", OnLinkSync);
            _linkStop = Btn("Stop using the link", () => { BackupUi.DisconnectAccountsLink(Owner); FillLink(); });
            Buttons(g, _linkConnect, _linkSync, _linkStop, Btn("Save accounts list…", () => BackupUi.SaveAccountsList(Owner)));

            Title(g, "🗂  Search index");
            Hint(g, "Every channel, film and series used by Search and the filters — no usernames or passwords. Same .novaindex file as the box: " +
                    "a new install (or a new PC) finds it in Downloads\\NovaIPTV\\Index and uses it straight away for the servers you add.");
            _indexStatus = Hint(g, "");
            Check(g, "Save index-latest.novaindex automatically after each update", () => App.Store.State.AutoExportIndex,
                v => { App.Store.State.AutoExportIndex = v; App.Store.SaveState(); });
            Buttons(g, Btn("Export now", () => ExportIndex(false)), Btn("Export to a file…", () => ExportIndex(true)),
                Btn("Import…", ImportIndex), Btn("Open Index folder", () => OpenFolder(KnownFolders.Index(App.Store.Settings))));

            Title(g, "💾  Backup & restore");
            Hint(g, "Same .bak file as the box: a backup made there restores here, and the other way round. Saved in Downloads\\NovaIPTV\\Backups. " +
                    "“My lists only” (_list.bakL) keeps just your order, favorites and hidden items.");
            Buttons(g, Btn("Save backup…", () => { BackupUi.Save(Owner); }, true), Btn("Restore…", () => { BackupUi.Restore(Owner); Fill(); }),
                Btn("📋 Save my lists only", () => BackupUi.SaveLists(Owner)), Btn("Restore my lists…", () => BackupUi.RestoreLists(Owner)),
                Btn("Open backups folder", () => OpenFolder(KnownFolders.Backups(App.Store.Settings))));

            Title(g, "🔒  Parental PIN & hidden items");
            _pinStatus = Hint(g, "");
            _pinSet = Btn("Set a PIN…", OnPinSet);
            _pinRemove = Btn("Remove the PIN", OnPinRemove);
            _pinLock = Btn("Lock now", OnPinLock);
            Buttons(g, _pinSet, _pinRemove, _pinLock);
            _hiddenTitle = new TextBlock { Foreground = (Brush)FindResource("SubText"), Margin = new Thickness(0, 10, 0, 6), TextWrapping = TextWrapping.Wrap };
            g.Children.Add(_hiddenTitle);
            _hiddenList = new StackPanel();
            g.Children.Add(_hiddenList);

            BuildFilter(g);

            Title(g, "🛡  VPN");
            var vpnRow = new DockPanel();
            var check = Btn("Check", FillVpn);
            DockPanel.SetDock(check, Dock.Right);
            vpnRow.Children.Add(check);
            _vpnStatus = new TextBlock { VerticalAlignment = VerticalAlignment.Center, TextWrapping = TextWrapping.Wrap };
            vpnRow.Children.Add(_vpnStatus);
            g.Children.Add(vpnRow);
            Check(g, "Only play when a VPN is connected", () => App.Store.Settings.RequireVpn, v => Change(s => s.RequireVpn = v));
        }

        private bool FilterUnlocked(string why) { return PinGate.Unlock(Owner, why); }

        private void BuildFilter(Panel g)
        {
            Title(g, "🔞  Age filter (USA ratings)");
            Hint(g, "Hides films and series above the age you choose, and adult channels. Ratings come from simple genre & name rules, " +
                    "or from the TMDB film database. Changes need your PIN when one is set.");
            Pick(g, "Highest rating allowed", () => Filtering.Settings.Level.ToString(),
                () => AgeFilter.Levels.Select((l, i) => T(i.ToString(), l + " — " + AgeFilter.LevelNotes[i])).ToList(),
                v => { if (FilterUnlocked("Your PIN protects the age filter.")) Filtering.Update(f => f.Level = Int(v)); });
            Pick(g, "Ratings from", () => Filtering.Settings.Source,
                () => new List<Tuple<string, string>> { T("rules", "Genre & name rules (fast, no internet)"), T("tmdb", "TMDB film database (most accurate)") },
                v => { if (FilterUnlocked("Your PIN protects the age filter.")) Filtering.Update(f => f.Source = v); });
            Pick(g, "Titles with no age found", () => Filtering.Settings.HideUnknown ? "hide" : "show",
                () => new List<Tuple<string, string>> { T("show", "Show them"), T("hide", "Hide them") },
                v => { if (FilterUnlocked("Your PIN protects the age filter.")) Filtering.Update(f => f.HideUnknown = v == "hide"); });
            Pick(g, "Live channels", () => Filtering.Settings.LiveMode,
                () => new List<Tuple<string, string>> { T("name", "Hide adult channels by name"), T("allow", "Only the groups I allow") },
                v => { if (FilterUnlocked("Your PIN protects the age filter.")) Filtering.Update(f => f.LiveMode = v); });
            Buttons(g, Btn("Groups I allow…", ChooseAllowedGroups), Btn("TMDB key…", OnTmdbKey), Btn("Check ages now", () => Filtering.CheckSoon()),
                Btn("Hidden by the filter…", ShowFilterHidden), Btn("Open Filter folder", () => OpenFolder(Path.Combine(KnownFolders.NovaDownloads(App.Store.Settings), "Filter"))));
            _filterStatus = Hint(g, "");

            Title(g, "🔤  Word filter");
            Hint(g, "Hides films and series with these words in their name (whole words, French and English, accents ignored). " +
                    "Separate from the age filter. The word lists are locked when a PIN is set.");
            Check(g, "Word filter on", () => Filtering.Settings.WordsOn,
                v => { if (FilterUnlocked("Your PIN protects the word filter.")) Filtering.Update(f => f.WordsOn = v); else Fill(); });
            foreach (var wg in AgeFilter.WordGroups)
            {
                var group = wg;
                var row = new DockPanel { Margin = new Thickness(0, 2, 0, 2) };
                var edit = Btn("Words…", () => EditWords(group));
                DockPanel.SetDock(edit, Dock.Right);
                row.Children.Add(edit);
                var c = new CheckBox { VerticalAlignment = VerticalAlignment.Center };
                _refresh.Add(() =>
                {
                    c.IsChecked = AgeFilter.GroupOn(Filtering.Settings, group);
                    c.Content = group.Title + "   (" + AgeFilter.WordsOf(Filtering.Settings, group).Count + " words)";
                });
                RoutedEventHandler h = (s, e) =>
                {
                    if (_filling || !IsLoaded) return;
                    if (!FilterUnlocked("Your PIN protects the word filter.")) { Fill(); return; }
                    var on = c.IsChecked == true;
                    Filtering.Update(f =>
                    {
                        var list = AgeFilter.WordGroups.Where(x => AgeFilter.GroupOn(f, x)).Select(x => x.Id).ToList();
                        if (on && !list.Contains(group.Id)) list.Add(group.Id);
                        if (!on) list.Remove(group.Id);
                        f.WordGroupsOn = list;
                    });
                };
                c.Checked += h;
                c.Unchecked += h;
                row.Children.Add(c);
                g.Children.Add(row);
            }
        }

        private void EditWords(AgeFilter.WordGroup group)
        {
            if (!FilterUnlocked("Your PIN protects the word lists.")) return;
            var words = AgeFilter.WordsOf(Filtering.Settings, group);
            var text = NovaDialog.AskText(Owner, group.Title, "Words separated by commas. Empty = the built-in list" + (group.Defaults.Length == 0 ? " (none)." : "."),
                string.Join(", ", words), false, "Save");
            if (text == null) return;
            var list = text.Split(new[] { ',', '\n', ';' }, StringSplitOptions.RemoveEmptyEntries).Select(w => w.Trim()).Where(w => w.Length > 0).Distinct().ToList();
            Filtering.Update(f =>
            {
                if (list.Count == 0 && group.Defaults.Length > 0) f.Words.Remove(group.Id);
                else f.Words[group.Id] = list;
            });
            Fill();
            App.Home.Toast(group.Title + ": " + (list.Count == 0 ? "built-in list" : list.Count + " words"));
        }

        private void OnTmdbKey()
        {
            if (!FilterUnlocked("Your PIN protects the age filter.")) return;
            var key = NovaDialog.AskText(Owner, "Your TMDB key (optional)",
                "Nova has its own key. If the check fails often, make a free key at themoviedb.org (Settings › API) and paste it here. Empty = Nova's key.",
                Filtering.Settings.TmdbKey, false, "Save");
            if (key == null) return;
            Filtering.Update(f => f.TmdbKey = key.Trim());
            App.Filter.Forget();
            App.Home.Toast(string.IsNullOrWhiteSpace(key) ? "Using Nova's key" : "Your key is used — checking again");
        }

        private async void ChooseAllowedGroups()
        {
            if (!FilterUnlocked("Your PIN protects the age filter.")) return;
            List<GroupSection> sections;
            try { sections = await App.Catalog.Sections(ContentType.Live); }
            catch (Exception e) { App.Home.Toast("The groups didn't load: " + e.Message); return; }
            var groups = sections.SelectMany(s => s.Groups.Select(c => Tuple.Create(c, s.Account))).ToList();
            if (groups.Count == 0) return;
            var multi = sections.Count > 1;
            var at = 0;
            while (true)
            {
                var allowed = new HashSet<string>(Filtering.Settings.AllowedGroups);
                var labels = groups.Select(x => (allowed.Contains(x.Item1.Key) ? "☑  " : "☐  ") + x.Item1.Name + (multi ? "   ·  " + x.Item2.DisplayName : "")).ToList();
                var i = NovaDialog.Choose(Owner, "Live groups you allow", "Used when “Live channels” is “Only the groups I allow”. Pick a group to tick / untick it; Cancel when done.", labels, at);
                if (i < 0) break;
                at = i;
                var key = groups[i].Item1.Key;
                Filtering.Update(f => { if (!f.AllowedGroups.Remove(key)) f.AllowedGroups.Add(key); });
            }
        }

        private async void ShowFilterHidden()
        {
            if (!FilterUnlocked("The hidden list is protected by your PIN.")) return;
            App.Home.Toast("Reading the search index…");
            var index = await Filtering.Snapshot();
            var lists = App.Filter.HiddenList(index);
            var parts = new[] { Tuple.Create("movie", "Films"), Tuple.Create("series", "Series"), Tuple.Create("live", "Channels") };
            var p = NovaDialog.Choose(Owner, "Hidden by the filters", "☑ hidden · ☐ allowed anyway (exception). The whole list is also in Filter\\hidden-list.txt.",
                parts.Select(x => x.Item2 + " (" + lists[x.Item1].Count + ")").ToList(), 0);
            if (p < 0) return;
            var rows = lists[parts[p].Item1].Take(400).ToList();
            if (rows.Count == 0) { NovaDialog.Info(Owner, "Nothing hidden", "Nothing is hidden here by the filters."); return; }
            var at = 0;
            while (true)
            {
                var allowed = new HashSet<string>(Filtering.Settings.Allowed);
                var labels = rows.Select(r => (allowed.Contains(r.ExKey) ? "☐  " : "☑  ") + r.Name + "   —  " + r.Reason).ToList();
                var i = NovaDialog.Choose(Owner, parts[p].Item2 + " hidden by the filters", "Pick one to allow it anyway (or hide it again). Cancel when done.", labels, at);
                if (i < 0) break;
                at = i;
                var key = rows[i].ExKey;
                Filtering.Update(f => { if (!f.Allowed.Remove(key)) f.Allowed.Add(key); });
            }
        }

        private void FillFilterStatus()
        {
            if (_filterStatus == null) return;
            var st = App.Filter.Status;
            var diag = App.Filter.Source == "tmdb" ? App.Filter.Diagnosis : null;
            _filterStatus.Text = (string.IsNullOrEmpty(st) ? "Open Search once so the index is ready; the filters then count what they hide." : st) +
                                 (string.IsNullOrEmpty(diag) ? "" : "\n" + diag);
        }

        private void ExportIndex(bool chooseFile)
        {
            var list = App.Catalog.Exportable();
            if (list.Count == 0) { NovaDialog.Info(Owner, "The index isn't ready yet", "Open Search once and wait for it to finish, then export."); return; }
            string path;
            if (chooseFile)
            {
                var dlg = new Microsoft.Win32.SaveFileDialog { Title = "Save the search index", Filter = "Nova search index|*.novaindex", FileName = "index_" + DateTime.Now.ToString("yyyy-MM-dd") + ".novaindex" };
                if (dlg.ShowDialog(Owner) != true) return;
                path = dlg.FileName;
            }
            else path = Path.Combine(KnownFolders.Index(App.Store.Settings), "index_" + DateTime.Now.ToString("yyyy-MM-dd") + "." + Catalog.IndexExt);
            try
            {
                var n = App.Catalog.ExportIndex(path, list, Brand.Version);
                App.Home.Toast("Index saved (" + n.ToString("N0") + " items): " + path);
                FillIndexStatus();
            }
            catch (Exception e) { NovaDialog.Info(Owner, "Could not save the index", e.Message); }
        }

        private void ImportIndex()
        {
            var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Import a search index", Filter = "Nova search index|*.novaindex|All files|*.*" };
            var folder = KnownFolders.Index(App.Store.Settings);
            if (Directory.Exists(folder)) dlg.InitialDirectory = folder;
            if (dlg.ShowDialog(Owner) != true) return;
            try
            {
                var r = App.Catalog.ImportIndex(dlg.FileName);
                if (r.Item1.Count == 0)
                    NovaDialog.Info(Owner, "No matching server", "This index is for: " + string.Join(", ", r.Item2) + ".\nAdd that server, and Nova uses it straight away.");
                else
                {
                    App.Home.Toast("Index imported for " + string.Join(", ", r.Item1) + " (" + r.Item3.ToString("N0") + " items)");
                    Filtering.CheckSoon();
                }
            }
            catch (Exception e) { NovaDialog.Info(Owner, "Can't import", e.Message); }
        }

        private void FillIndexStatus()
        {
            if (_indexStatus == null) return;
            var at = App.Store.State.IndexExportedAt;
            var names = App.Catalog.Exportable().Select(a => a.DisplayName).ToList();
            _indexStatus.Text = (at > 0 ? "Last saved: " + DateTimeOffset.FromUnixTimeMilliseconds(at).LocalDateTime.ToString("d MMM yyyy HH:mm") : "Not saved yet") +
                                (names.Count > 0 ? "  ·  ready: " + string.Join(", ", names) : "  ·  not built yet (open Search once)");
        }

        // ------------------------------------------------------------------ 2 · live TV

        private void BuildLive()
        {
            var g = Group("📡  LIVE TV");
            Title(g, "Live TV buffer");
            Check(g, "Download every stream to the disk first, then play it from there (pause, rewind, recording)",
                () => App.Store.Settings.UseDiskBuffer, v => Change(s => s.UseDiskBuffer = v));
            Pick(g, "Disk buffer length (what you can pause / rewind)", () => App.Store.Settings.LiveDiskMinutes.ToString(),
                () => new List<Tuple<string, string>> { T("5", "5 min"), T("10", "10 min"), T("15", "15 min") }, v => Change(s => s.LiveDiskMinutes = Int(v)));

            Title(g, "Safety cushion");
            Pick(g, "Safety cushion behind live", () => App.Store.Settings.CushionSeconds.ToString(),
                () => new[] { 3, 6, 10, 15, 20, 25 }.Select(n => T(n.ToString(), n + " s")).ToList(), v => Change(s => s.CushionSeconds = Int(v)));
            Pick(g, "Channel start", () => App.Store.Settings.LiveStart,
                () => new List<Tuple<string, string>> { T("fast", "Fast — start at live, build the cushion while watching"), T("safe", "Safe — wait for the whole cushion first") },
                v => Change(s => s.LiveStart = v));
            Pick(g, "Grow the cushion while watching (plays 4 % slower)", () => App.Store.Settings.LiveGrowTarget.ToString(),
                () => new List<Tuple<string, string>> { T("0", "Off"), T("60", "Up to 1 min"), T("120", "Up to 2 min"), T("180", "Up to 3 min") },
                v => Change(s => s.LiveGrowTarget = Int(v)));

            Title(g, "Stream watchdog");
            Pick(g, "When the picture gets stuck", () => App.Store.Settings.WatchdogMode,
                () => new List<Tuple<string, string>> { T("live", "Live button only (jump back to live)"), T("full", "Full repair (safer settings, then the other account)") },
                v => Change(s => s.WatchdogMode = v));
            Pick(g, "Full repair may use the same channel on another account", () => App.Store.Settings.WatchdogOtherAccount ? "yes" : "no",
                () => new List<Tuple<string, string>> { T("yes", "Yes"), T("no", "No") }, v => Change(s => s.WatchdogOtherAccount = v == "yes"));
            Pick(g, "Reconnect a stream that sends nothing for", () => App.Store.Settings.StallSeconds.ToString(),
                () => new[] { 6, 10, 20 }.Select(n => T(n.ToString(), n + " s")).ToList(), v => Change(s => s.StallSeconds = Int(v)));

            Title(g, "Channels that don't work");
            Check(g, "Skip to the next channel automatically", () => App.Store.Settings.AutoSkip, v => Change(s => s.AutoSkip = v));
            Pick(g, "…after waiting", () => App.Store.Settings.DeadSeconds.ToString(),
                () => new[] { 5, 8, 10, 15, 30 }.Select(n => T(n.ToString(), n + " s")).ToList(), v => Change(s => s.DeadSeconds = Int(v)),
                "Servers that usually start slowly get more time by themselves (learned).");
            Pick(g, "When the line is too slow for a channel", () => App.Store.Settings.LiveSlowAction,
                () => new List<Tuple<string, string>> { T("similar", "Propose similar channels (≈)"), T("switch", "Switch to my other server"), T("stay", "Stay on the channel") },
                v => Change(s => s.LiveSlowAction = v));
            Check(g, "Status dots on channels, films and episodes (green works · red not working · orange format · grey not checked)",
                () => App.Store.Settings.StatusDots, v => Change(s => s.StatusDots = v));

            Title(g, "Live TV view");
            Check(g, "Preview box: Enter plays the channel in the small box, Enter again goes full screen", () => App.Store.Settings.LivePreview, v => Change(s => s.LivePreview = v));
            Check(g, "Show channel logos", () => App.Store.Settings.LiveLogos, v => Change(s => s.LiveLogos = v));
            Check(g, "Show the programme on air under each channel", () => App.Store.Settings.LiveNowLine, v => Change(s => s.LiveNowLine = v));
            Check(g, "TV-style arrows in the player: ▲ = next channel, ▼ = previous", () => App.Store.Settings.TvArrows, v => Change(s => s.TvArrows = v));
            Check(g, "Open a group as soon as it is highlighted with the arrows", () => App.Store.Settings.OpenGroupOnFocus, v => Change(s => s.OpenGroupOnFocus = v));
            Check(g, "Type a channel number in the player to jump to it", () => App.Store.Settings.NumberKeys, v => Change(s => s.NumberKeys = v));
            Check(g, "When Nova starts, play the last channel I watched", () => App.Store.Settings.AutoPlayLast, v => Change(s => s.AutoPlayLast = v));
            Pick(g, "Channel row height", () => App.Store.Settings.LiveRowSize,
                () => new List<Tuple<string, string>> { T("compact", "Compact"), T("normal", "Normal"), T("large", "Large") }, v => Change(s => s.LiveRowSize = v));
            Pick(g, "Lists over the video: transparency", () => App.Store.Settings.OverlayTransparency.ToString(),
                () => new List<Tuple<string, string>> { T("0", "Solid"), T("15", "15 %"), T("25", "25 %"), T("40", "40 %"), T("60", "60 %") },
                v => Change(s => s.OverlayTransparency = Int(v)));
            Pick(g, "Sort Live TV", () => App.Store.Settings.SortLive, () => Organize.SortChoices("live"), v => Change(s => s.SortLive = v));
        }

        // ------------------------------------------------------------------ 3 · player

        private void BuildPlayer()
        {
            var g = Group("▶  PLAYER");
            Pick(g, "Live channel format", () => App.Store.Settings.LiveFormat,
                () => new List<Tuple<string, string>> { T("ts", "MPEG-TS (recommended: buffer, rewind, recording)"), T("m3u8", "HLS (m3u8)") },
                v => Change(s => s.LiveFormat = v));
            Pick(g, "Network buffer (only when the disk buffer is off)", () => App.Store.Settings.NetworkCachingMs.ToString(),
                () => new List<Tuple<string, string>> { T("1000", "1 s"), T("3000", "3 s"), T("5000", "5 s"), T("10000", "10 s") },
                v => Change(s => s.NetworkCachingMs = Int(v)));
            Check(g, "Use the graphics card to decode video (turn off if the picture is green, frozen or flickering)",
                () => App.Store.Settings.HardwareDecoding, v => Change(s => s.HardwareDecoding = v));
            Check(g, "Open the player in full screen", () => App.Store.Settings.PlayerFullScreen, v => Change(s => s.PlayerFullScreen = v));
            Check(g, "Films & series: press Back twice to stop the video", () => App.Store.Settings.DoubleBackVod, v => Change(s => s.DoubleBackVod = v));
            Pick(g, "🔊 Volume boost for everything", () => App.Store.Settings.BoostAll.ToString(),
                () => new[] { 100, 125, 150, 175, 200 }.Select(n => T(n.ToString(), n == 100 ? "Off (100 %)" : n + " %")).ToList(),
                v => Change(s => s.BoostAll = Int(v)), "One channel or film can keep its own boost (player › 🔊 Boost).");

            Title(g, "Which player plays what");
            PlayerPick(g, "Live TV", () => App.Store.Settings.PlayerLive, (s, v) => s.PlayerLive = v);
            PlayerPick(g, "Films", () => App.Store.Settings.PlayerFilms, (s, v) => s.PlayerFilms = v);
            PlayerPick(g, "Series", () => App.Store.Settings.PlayerSeries, (s, v) => s.PlayerSeries = v);
            PlayerPick(g, "Catch-up", () => App.Store.Settings.PlayerCatchup, (s, v) => s.PlayerCatchup = v);
            PlayerPick(g, "Files and links", () => App.Store.Settings.PlayerFiles, (s, v) => s.PlayerFiles = v);

            Title(g, "↗  External players (“Play with…”)");
            Hint(g, "Your order of the video players found on this PC. ▲ ▼ to move, Hide to leave one out of the list.");
            _extList = new StackPanel();
            g.Children.Add(_extList);
            Pick(g, "↗ External button", () => App.Store.Settings.ExtMode,
                () => new List<Tuple<string, string>> { T("ask", "Ask each time (Nova's “Play with…” list)"), T("first", "Always the first one of my list") },
                v => Change(s => s.ExtMode = v));
            Buttons(g, Btn("Add a program…", () =>
            {
                var p = ChooseProgram();
                if (p == null) return;
                Change(s => { s.ExtOrder.Remove(p); s.ExtOrder.Insert(0, p); if (string.IsNullOrEmpty(s.ExternalPlayer)) s.ExternalPlayer = p; });
                FillExternal();
            }));

            Title(g, "When Nova can't play something");
            Pick(g, "Open it in another player", () => App.Store.Settings.FailApp,
                () =>
                {
                    var l = new List<Tuple<string, string>> { T("ask", "Offer my first player (10 s, a key stops it)"), T("none", "Don't offer") };
                    l.AddRange(ExternalPlayers.Ordered().Select(p => T(p.Path, "Offer " + p.Name)));
                    return l;
                }, v => Change(s => { s.FailApp = v; s.ExternalOnError = v != "none"; }));
            Pick(g, "…when", () => App.Store.Settings.FailAppWhen,
                () => new List<Tuple<string, string>> { T("format", "Format problems only (Nova's player can't decode it)"), T("any", "Any failure") },
                v => Change(s => s.FailAppWhen = v));

            Title(g, "Subtitles");
            Pick(g, "Size", () => App.Store.Settings.SubtitleSize.ToString(),
                () => new List<Tuple<string, string>> { T("14", "Small"), T("20", "Normal"), T("26", "Large"), T("32", "Very large"), T("40", "Huge") },
                v => Change(s => s.SubtitleSize = Int(v)));
            Pick(g, "Colour", () => App.Store.Settings.SubtitleColor,
                () => new List<Tuple<string, string>> { T("white", "White"), T("yellow", "Yellow"), T("green", "Green"), T("cyan", "Cyan") },
                v => Change(s => s.SubtitleColor = v));
            Check(g, "Dark box behind the subtitles", () => App.Store.Settings.SubtitleBackground, v => Change(s => s.SubtitleBackground = v));
        }

        private void PlayerPick(Panel g, string label, Func<string> current, Action<Settings, string> set)
        {
            Pick(g, label, () => string.IsNullOrEmpty(current()) ? ExternalPlayers.Builtin : current(),
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
            return dlg.ShowDialog(Owner) == true ? dlg.FileName : null;
        }

        private void FillExternal()
        {
            if (_extList == null) return;
            _extList.Children.Clear();
            var s = App.Store.Settings;
            var all = ExternalPlayers.Installed();
            var order = s.ExtOrder ?? new List<string>();
            var sorted = all.OrderBy(p => { var i = order.FindIndex(x => string.Equals(x, p.Path, StringComparison.OrdinalIgnoreCase)); return i < 0 ? 1000 + all.IndexOf(p) : i; }).ToList();
            if (sorted.Count == 0) { _extList.Children.Add(new TextBlock { Text = "No video player found (VLC, MPC-HC, MPC-BE, PotPlayer, Kodi, mpv…).", Foreground = (Brush)FindResource("DimText") }); return; }
            var hidden = new HashSet<string>(s.ExtHidden ?? new List<string>(), StringComparer.OrdinalIgnoreCase);
            for (int i = 0; i < sorted.Count; i++)
            {
                var p = sorted[i];
                var index = i;
                var row = new DockPanel { Margin = new Thickness(0, 2, 0, 2) };
                var right = new StackPanel { Orientation = Orientation.Horizontal };
                DockPanel.SetDock(right, Dock.Right);
                Action<int> move = d =>
                {
                    var list = sorted.Select(x => x.Path).ToList();
                    var j = index + d;
                    if (j < 0 || j >= list.Count) return;
                    var t = list[index]; list[index] = list[j]; list[j] = t;
                    Change(x => x.ExtOrder = list);
                    FillExternal();
                };
                right.Children.Add(Btn("▲", () => move(-1)));
                right.Children.Add(Btn("▼", () => move(1)));
                var isHidden = hidden.Contains(p.Path);
                right.Children.Add(Btn(isHidden ? "Show" : "Hide", () =>
                {
                    Change(x => { if (!x.ExtHidden.Remove(p.Path)) x.ExtHidden.Add(p.Path); });
                    FillExternal();
                }));
                row.Children.Add(right);
                row.Children.Add(new TextBlock
                {
                    Text = (i + 1) + ".  " + p.Name + (isHidden ? "   (hidden)" : "") + "   ·  " + p.Path,
                    VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis,
                    Opacity = isHidden ? 0.5 : 1,
                });
                _extList.Children.Add(row);
            }
        }

        // ------------------------------------------------------------------ 4 · films & downloads

        private void BuildFilms()
        {
            var g = Group("🎬  FILMS & DOWNLOADS");
            Pick(g, "Films & series read-ahead on disk", () => App.Store.Settings.FilmBufferMb.ToString(),
                () => new List<Tuple<string, string>> { T("256", "256 MB"), T("512", "512 MB"), T("1024", "1 GB"), T("2048", "2 GB"), T("4096", "4 GB") },
                v => Change(s => s.FilmBufferMb = Int(v)), "How much of a film is fetched ahead and kept (HLS films too: in pieces).");

            Title(g, "Downloads");
            Hint(g, "One download at a time per IPTV server, and up to 4 links at once — all running together.");
            var conns = new List<Tuple<string, string>> { T("1", "1 connection"), T("2", "2 connections"), T("4", "4 connections"), T("8", "8 connections"), T("16", "16 connections") };
            var speeds = new List<Tuple<string, string>> { T("0", "No limit"), T("1", "1 MB/s"), T("2", "2 MB/s"), T("5", "5 MB/s"), T("10", "10 MB/s"), T("20", "20 MB/s") };
            Pick(g, "Connections per download from your IPTV servers", () => App.Store.Settings.DlConnServers.ToString(), () => conns,
                v => { Change(s => s.DlConnServers = Int(v)); FillConnHint(); });
            Pick(g, "Speed limit, IPTV servers", () => App.Store.Settings.DlSpeedServers.ToString(), () => speeds, v => Change(s => s.DlSpeedServers = Int(v)));
            Pick(g, "Connections per download from other links", () => App.Store.Settings.DlConnLinks.ToString(), () => conns, v => Change(s => s.DlConnLinks = Int(v)));
            Pick(g, "Speed limit, other links", () => App.Store.Settings.DlSpeedLinks.ToString(), () => speeds, v => Change(s => s.DlSpeedLinks = Int(v)));
            _connHint = Hint(g, "");

            Title(g, "Posters");
            Pick(g, "Poster size", () => App.Store.Settings.PosterSize,
                () => new List<Tuple<string, string>> { T("small", "Small"), T("medium", "Medium"), T("large", "Large") }, v => Change(s => s.PosterSize = v));
            Pick(g, "Posters per row", () => App.Store.Settings.PosterColumns.ToString(),
                () => new List<Tuple<string, string>> { T("0", "Automatic (fits the window)"), T("4", "4"), T("5", "5"), T("6", "6"), T("7", "7"), T("8", "8"), T("10", "10") },
                v => Change(s => s.PosterColumns = Int(v)));
            Check(g, "Show titles under the posters", () => App.Store.Settings.PosterTitles, v => Change(s => s.PosterTitles = v));
            Pick(g, "Sort films", () => App.Store.Settings.SortMovie, () => Organize.SortChoices("movie"), v => Change(s => s.SortMovie = v));
            Pick(g, "Sort series", () => App.Store.Settings.SortSeries, () => Organize.SortChoices("series"), v => Change(s => s.SortSeries = v));
        }

        private void FillConnHint()
        {
            if (_connHint == null) return;
            var parts = App.Store.Accounts.Select(a =>
            {
                var n = App.Catalog.MaxConnections(a.Id);
                return a.DisplayName + ": " + (n > 0 ? n + (n == 1 ? " connection" : " connections") + " allowed" : "not known yet");
            }).ToList();
            _connHint.Text = "Nova never uses more connections than your server allows and always keeps one free for watching." +
                             (parts.Count > 0 ? "\n" + string.Join("  ·  ", parts) : "");
        }

        // ------------------------------------------------------------------ 5 · storage

        private void BuildStorage()
        {
            var g = Group("💾  STORAGE");
            FolderRow(g, "Buffer folder (live TV and films being watched)", () => KnownFolders.Buffer(App.Store.Settings),
                () => { var p = PickFolder("Folder for Nova's disk buffer (any drive)", KnownFolders.Buffer(App.Store.Settings)); if (p != null) SetFolder(true, p); },
                () => OpenFolder(KnownFolders.Buffer(App.Store.Settings)), () => SetFolder(true, null));
            FolderRow(g, "Downloads, recordings, backups, index, filter and logs", () => KnownFolders.NovaDownloads(App.Store.Settings),
                () => { var p = PickFolder("Folder for downloads and recordings", KnownFolders.NovaDownloads(App.Store.Settings)); if (p != null) SetFolder(false, p); },
                () => OpenFolder(KnownFolders.NovaDownloads(App.Store.Settings)), () => SetFolder(false, null));
            Title(g, "Buffers");
            _buffers = Hint(g, "");
            Buttons(g, Btn("Clear the films buffer", ClearBuffers), Btn("Clear picture cache", () => { Img.ClearCache(); App.Home.Toast("Picture cache cleared"); }),
                Btn("Open data folder", () => OpenFolder(App.Store.DataFolder)));
        }

        private static long FolderSize(string dir)
        {
            try { return Directory.Exists(dir) ? new DirectoryInfo(dir).GetFiles("*", SearchOption.AllDirectories).Sum(f => f.Length) : 0; }
            catch { return 0; }
        }

        private static string Mb(long b) { return b >= 1L << 30 ? (b / (double)(1L << 30)).ToString("0.0") + " GB" : (b / (1L << 20)) + " MB"; }

        private async void FillBuffers()
        {
            if (_buffers == null) return;
            var live = App.Hub.LiveFolder;
            var films = App.Hub.FilmsFolder;
            var sizes = await Task.Run(() => Tuple.Create(FolderSize(live), FolderSize(films)));
            var s = App.Store.Settings;
            _buffers.Text = "📡 Live TV: " + Mb(sizes.Item1) + " (up to " + s.LiveDiskMinutes + " min per channel)  ·  🎬 Films & series: " + Mb(sizes.Item2) +
                            " (read-ahead " + Mb((long)s.FilmBufferMb << 20) + ")\n" + KnownFolders.Buffer(s);
        }

        private void ClearBuffers()
        {
            if (App.Dots.PlayingAccount != null) { App.Home.Toast("Close the player first."); return; }
            if (!NovaDialog.Ask(Owner, "Clear the films buffer?", "Deletes the parts of films and episodes Nova keeps on disk to play them. Downloads and recordings are not touched.", "Yes, clear")) return;
            try
            {
                var dir = App.Hub.FilmsFolder;
                if (Directory.Exists(dir)) foreach (var d in Directory.GetDirectories(dir)) { try { Directory.Delete(d, true); } catch { } }
            }
            catch { }
            App.Home.Toast("Buffer cleared");
            FillBuffers();
        }

        // ------------------------------------------------------------------ 6 · screen & remote

        private void BuildScreen()
        {
            var g = Group("🖥  SCREEN & REMOTE");
            Pick(g, "Clock in the player", () => App.Store.Settings.ClockPosition,
                () => new List<Tuple<string, string>> { T("off", "Off"), T("tl", "Top left"), T("tr", "Top right"), T("bl", "Bottom left"), T("br", "Bottom right") },
                v => Change(s => s.ClockPosition = v));
            Check(g, "Press Back (Esc) twice on Live TV to close Nova", () => App.Store.Settings.DoubleBackExit, v => Change(s => s.DoubleBackExit = v));
            Check(g, "Add “Open with Nova” to the right-click menu of video files (your default player stays)", () =>
            {
                try { return ShellMenu.IsAdded(); } catch { return false; }
            }, v =>
            {
                try
                {
                    if (v) ShellMenu.Add(Process.GetCurrentProcess().MainModule.FileName); else ShellMenu.Remove();
                    App.Home.Toast(v ? "“Open with Nova” added to the right-click menu" : "“Open with Nova” removed from the right-click menu");
                }
                catch (Exception ex) { App.Home.Toast("Couldn't change the right-click menu: " + ex.Message); }
            });

            Title(g, "Remote control & keyboard");
            Hint(g, "Choose which button does what. “Add a button”, then press the button on your remote or keyboard.");
            _keysToggle = Btn("Show the buttons ▾", OnKeysToggle);
            Buttons(g, _keysToggle, Btn("Reset all buttons", OnKeysReset));
            _keyRows = new StackPanel { Visibility = Visibility.Collapsed };
            g.Children.Add(_keyRows);
        }

        // ------------------------------------------------------------------ 7 · system

        private void BuildSystem()
        {
            var g = Group("🛠  SYSTEM");
            Title(g, "Advanced");
            Hint(g, "User-Agent sent to the server. Change only if your provider asks for a specific one.");
            _agentBox = new TextBox { Margin = new Thickness(0, 0, 0, 6) };
            g.Children.Add(_agentBox);
            Buttons(g, Btn("Save User-Agent", () =>
            {
                var text = _agentBox.Text.Trim();
                Change(s => s.UserAgent = text.Length == 0 ? Store.DefaultUserAgent : text);
                App.Home.Toast("User agent saved");
            }), Btn("Reset", () => { _agentBox.Text = Store.DefaultUserAgent; Change(s => s.UserAgent = Store.DefaultUserAgent); }));

            Title(g, "Last problem");
            Hint(g, "Playback report: every channel, film and episode played (start time, quality, line speed, cushion, interruptions, watchdog). " +
                    "No usernames, passwords or links. Saved as a .txt in Downloads\\NovaIPTV\\Logs to share.");
            _glance = Hint(g, "");
            Buttons(g, Btn("Report: today", () => SaveReport(1)), Btn("3 days", () => SaveReport(3)), Btn("7 days", () => SaveReport(7)),
                Btn("Open Logs folder", () => OpenFolder(Path.Combine(KnownFolders.NovaDownloads(App.Store.Settings), "Logs"))));
            _lastProblem = Hint(g, "");
            Buttons(g, Btn("Clear the report and problems", () =>
            {
                if (!NovaDialog.Ask(Owner, "Clear the playback report?", "The list of what played and the saved problems are deleted.", "Yes, clear")) return;
                App.Report.Clear();
                App.Report.ClearProblems();
                FillReport();
            }));

            Title(g, "Reset");
            Hint(g, "Puts every setting back to Nova's defaults (disk live buffer 15 min, 15 s cushion with fast start growing to 2 min, skip channels " +
                    "that don't work after 8 s, propose similar channels when the line is too slow, open in another player: offer (format problems), " +
                    "age filter NC-17 with words, 1 GB read-ahead, 8 connections for links…). Servers, favorites, order, hidden items, PIN and downloads are kept.");
            Buttons(g, Btn("Reset all settings to Nova defaults", OnReset));

            Title(g, "About");
            _about = new TextBlock { Style = (Style)FindResource("Hint"), FontSize = 13.5, LineHeight = 22, TextWrapping = TextWrapping.Wrap };
            g.Children.Add(_about);
            g.Children.Add(new TextBlock { Text = Brand.Signature, Foreground = (Brush)FindResource("Accent"), FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 8, 0, 0) });
        }

        private void SaveReport(int days)
        {
            try
            {
                var s = App.Store.Settings;
                var summary = "buffer " + (s.UseDiskBuffer ? "disk " + s.LiveDiskMinutes + " min" : "memory") + ", cushion " + s.CushionSeconds + " s (" + s.LiveStart + "), grow " +
                              s.LiveGrowTarget + " s, watchdog " + s.WatchdogMode + ", skip " + (s.AutoSkip ? "after " + s.DeadSeconds + " s" : "off") + ", slow line " + s.LiveSlowAction +
                              ", format " + s.LiveFormat + ", decoding " + (s.HardwareDecoding ? "graphics card" : "processor") + ", read-ahead " + s.FilmBufferMb + " MB";
                var text = App.Report.Report(days, Brand.Version, summary, App.Store.State.HealthLog);
                var path = App.Report.SaveReport(text);
                try { Process.Start("notepad.exe", "\"" + path + "\""); } catch { }
                App.Home.Toast("Report saved: " + path);
            }
            catch (Exception e) { NovaDialog.Info(Owner, "Couldn't make the report", e.Message); }
        }

        private void FillReport()
        {
            if (_glance == null) return;
            _glance.Text = App.Report.Glance(7);
            var last = App.Report.LastProblem();
            _lastProblem.Text = last == null ? "No crash or forced close saved." : "Last problem:\n" + string.Join("\n", last.Split('\n').Take(4));
        }

        private void OnReset()
        {
            if (!PinGate.Unlock(Owner, "Your PIN protects the settings.")) return;
            if (!NovaDialog.Ask(Owner, "Reset all settings?", "Servers, favorites, order, hidden items, PIN and downloads stay as they are.", "Reset")) return;
            var old = App.Store.Settings;
            var s = new Settings
            {
                BufferFolder = old.BufferFolder, DownloadFolder = old.DownloadFolder,
                WindowWidth = old.WindowWidth, WindowHeight = old.WindowHeight, WindowMaximized = old.WindowMaximized,
            };
            App.Store.SaveSettings(s);
            Filtering.Update(f =>
            {
                var fresh = new FilterSettings();
                f.Level = fresh.Level; f.Source = fresh.Source; f.HideUnknown = fresh.HideUnknown; f.LiveMode = fresh.LiveMode;
                f.AllowedGroups = fresh.AllowedGroups; f.TmdbKey = fresh.TmdbKey; f.WordsOn = fresh.WordsOn; f.WordGroupsOn = null; f.Words.Clear();
            });
            Fill();
            App.Home.Toast("Settings reset to Nova defaults");
        }

        // ================================================================== filling

        private void Fill()
        {
            _filling = true;
            foreach (var r in _refresh) r();
            if (_agentBox != null && !_agentBox.IsKeyboardFocusWithin) _agentBox.Text = App.Store.Settings.UserAgent;
            _filling = false;
            FillLink();
            FillPin();
            FillHidden();
            FillLock();
            FillVpn();
            FillConnHint();
            FillIndexStatus();
            FillFilterStatus();
            FillExternal();
            FillBuffers();
            FillReport();
            if (_about != null)
                _about.Text = "Nova IPTV for Windows " + Brand.Version + "  ·  32-bit\nVideo engine: " + (App.Vlc != null ? "VLC " + App.Vlc.Version : "not started") +
                              "\nData folder: " + App.Store.DataFolder;
            if (_keyRows != null && _keyRows.Visibility == Visibility.Visible) FillKeys();
        }

        // ------------------------------------------------------------------ accounts

        private async void LoadAccounts()
        {
            _accounts.Children.Clear();
            foreach (var a in App.Store.Accounts.ToList())
            {
                var acc = a;
                var status = new TextBlock { Text = "Checking…", Foreground = (Brush)FindResource("DimText"), FontSize = 13, Margin = new Thickness(0, 4, 0, 0), TextWrapping = TextWrapping.Wrap };
                var info = new StackPanel();
                info.Children.Add(new TextBlock { Text = acc.DisplayName + (acc.FromList ? "   (accounts link)" : ""), FontSize = 16, FontWeight = FontWeights.SemiBold });
                info.Children.Add(new TextBlock { Text = acc.Username + "  @  " + XtreamApi.NormalizeServer(acc.Server), Foreground = (Brush)FindResource("DimText"), FontSize = 13, Margin = new Thickness(0, 2, 0, 0) });
                info.Children.Add(status);
                var buttons = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
                buttons.Children.Add(Btn("Edit", () => AccountDialog.Edit(Owner, acc)));
                buttons.Children.Add(Btn("Remove", () =>
                {
                    if (!NovaDialog.Ask(Owner, "Remove this account?", acc.DisplayName + "\nIts favorites are removed too.", "Yes, remove")) return;
                    App.Store.RemoveAccount(acc.Id);
                }));
                var dock = new DockPanel();
                DockPanel.SetDock(buttons, Dock.Right);
                dock.Children.Add(buttons);
                dock.Children.Add(info);
                _accounts.Children.Add(new Border { Background = (Brush)FindResource("SurfaceVar"), CornerRadius = new CornerRadius(9), Padding = new Thickness(14, 10, 14, 10), Margin = new Thickness(0, 0, 0, 8), Child = dock });
                try
                {
                    var ui = await App.Catalog.Api.Authenticate(acc);
                    App.Catalog.Note(acc.Id, ui);
                    status.Text = ui.Summary;
                    status.Foreground = (Brush)FindResource("Accent");
                }
                catch (Exception e)
                {
                    status.Text = "Can't sign in: " + e.Message;
                    status.Foreground = (Brush)FindResource("ErrorBrush");
                }
            }
            if (App.Store.Accounts.Count == 0) _accounts.Children.Add(new TextBlock { Text = "No account yet.", Foreground = (Brush)FindResource("DimText") });
            FillConnHint();
        }

        private void FillLink()
        {
            if (_linkStatus == null) return;
            var st = App.Store.State;
            var on = !string.IsNullOrEmpty(st.AccountsLink);
            if (on)
            {
                var when = st.AccountsLinkChecked > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(st.AccountsLinkChecked).LocalDateTime.ToString("d MMM HH:mm") : "never";
                _linkStatus.Text = "Connected: " + st.AccountsLink + (st.AccountsLinkPassword != null ? "  (protected)" : "") +
                                   "\nChecked every day · last check " + when + (string.IsNullOrEmpty(st.AccountsLinkNote) ? "" : " · " + st.AccountsLinkNote);
            }
            else _linkStatus.Text = "Keep your accounts in one text file on OneDrive, Google Drive or Dropbox; Nova reads it every day (same as the box).";
            _linkConnect.Content = on ? "Change the link…" : "Connect an accounts link…";
            _linkSync.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
            _linkStop.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
        }

        private async void OnLinkSync()
        {
            _linkStatus.Text = "Checking…";
            var note = await BackupUi.SyncAccountsLink();
            App.Home.Toast(note);
            FillLink();
        }

        // ------------------------------------------------------------------ lock

        private void FillLock()
        {
            var locked = App.Store.HasPin && !PinGate.IsUnlocked;
            LockBar.Visibility = locked ? Visibility.Visible : Visibility.Collapsed;
            Body.IsEnabled = !locked;
            Body.Opacity = locked ? 0.45 : 1;
        }

        private void OnUnlock(object sender, RoutedEventArgs e)
        {
            if (PinGate.Unlock(Owner, "Settings are protected by your PIN.")) { FillLock(); FillPin(); FillHidden(); FocusStart(); }
        }

        // ------------------------------------------------------------------ PIN and hidden items

        private void FillPin()
        {
            if (_pinStatus == null) return;
            if (!App.Store.HasPin)
            {
                _pinStatus.Text = "No PIN. With a PIN, hiding, reordering, the filters, hidden items and these settings need the PIN (then stay open 5 minutes).";
                _pinSet.Content = "Set a PIN…";
                _pinRemove.Visibility = Visibility.Collapsed;
                _pinLock.Visibility = Visibility.Collapsed;
            }
            else
            {
                _pinStatus.Text = PinGate.IsUnlocked ? "PIN is on · unlocked for a few minutes." : "PIN is on · locked.";
                _pinSet.Content = "Change the PIN…";
                _pinRemove.Visibility = Visibility.Visible;
                _pinLock.Visibility = PinGate.IsUnlocked ? Visibility.Visible : Visibility.Collapsed;
            }
        }

        private void OnPinSet()
        {
            var w = Owner;
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

        private void OnPinRemove()
        {
            var w = Owner;
            if (!PinGate.Unlock(w, "Enter your PIN to remove it.")) return;
            if (!NovaDialog.Ask(w, "Remove the PIN?", "Hiding, reordering, the filters and the settings will no longer need a PIN.", "Yes, remove")) return;
            App.Store.SetPin(null);
            PinGate.Lock();
            App.Home.Toast("PIN removed");
            FillPin();
            FillHidden();
            FillLock();
        }

        private void OnPinLock()
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
            if (_hiddenList == null) return;
            _hiddenList.Children.Clear();
            var org = App.Store.Org;
            var all = org.HiddenGroups.Select(k => Tuple.Create(k, true)).Concat(org.HiddenItems.Select(k => Tuple.Create(k, false))).ToList();
            _hiddenTitle.Text = all.Count == 0 ? "Nothing hidden. Hide groups, channels and posters with ✎ Edit (or a double click)." : "Hidden items (" + all.Count + ")";
            if (all.Count == 0) return;
            if (App.Store.HasPin && !PinGate.IsUnlocked)
            {
                _hiddenList.Children.Add(Btn("Show the list (PIN)", () => { if (PinGate.Unlock(Owner, "The hidden list is protected by your PIN.")) FillHidden(); }));
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
                var btn = Btn("Show again", () =>
                {
                    if (!NovaDialog.Ask(Owner, "Show it again?", name, "Yes, show it")) return;
                    if (!PinGate.Unlock(Owner, "Your PIN protects hidden items.")) return;
                    App.Store.UpdateOrg(o => { if (group) o.HiddenGroups.Remove(key); else o.HiddenItems.Remove(key); o.Names.Remove(key); });
                    App.Home.Toast("Shown again: " + name);
                    FillHidden();
                });
                DockPanel.SetDock(btn, Dock.Right);
                row.Children.Add(btn);
                row.Children.Add(new TextBlock { Text = name + "   ·  " + ZoneName(zone) + (group ? " group" : ""), VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis });
                _hiddenList.Children.Add(row);
            }
        }

        // ------------------------------------------------------------------ VPN

        private void FillVpn()
        {
            if (_vpnStatus == null) return;
            var v = Vpn.Active();
            _vpnStatus.Text = v != null ? "✓ VPN connected: " + v : "No VPN connection found.";
            _vpnStatus.Foreground = (Brush)FindResource(v != null ? "Accent" : "SubText");
        }

        // ------------------------------------------------------------------ folders

        private static string PickFolder(string title, string current)
        {
            using (var dlg = new System.Windows.Forms.FolderBrowserDialog { Description = title, ShowNewFolderButton = true, SelectedPath = current })
                return dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK ? dlg.SelectedPath : null;
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
            Change(s => { if (buffer) s.BufferFolder = path ?? ""; else s.DownloadFolder = path ?? ""; });
            Fill();
            App.Home.Toast(buffer ? "Buffer folder: " + KnownFolders.Buffer(App.Store.Settings) + " (used from the next stream)"
                                  : "Downloads go to " + KnownFolders.NovaDownloads(App.Store.Settings) + " from now on");
        }

        private static void OpenFolder(string path)
        {
            try { Directory.CreateDirectory(path); Process.Start("explorer.exe", "\"" + path + "\""); }
            catch (Exception ex) { App.Home.Toast(ex.Message); }
        }

        // ------------------------------------------------------------------ remote control

        private void OnKeysToggle()
        {
            var show = _keyRows.Visibility != Visibility.Visible;
            _keyRows.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
            _keysToggle.Content = show ? "Hide the buttons ▴" : "Show the buttons ▾";
            if (show) FillKeys();
        }

        private void OnKeysReset()
        {
            if (!NovaDialog.Ask(Owner, "Reset all buttons?", "Every action goes back to its usual buttons.", "Yes, reset")) return;
            KeyMap.ResetAll();
            if (_keyRows.Visibility == Visibility.Visible) FillKeys();
            App.Home.Toast("Buttons reset");
        }

        private void FillKeys()
        {
            _keyRows.Children.Clear();
            foreach (var area in new[] { KeyArea.Live, KeyArea.Vod, KeyArea.Lists })
            {
                _keyRows.Children.Add(new TextBlock
                {
                    Text = area == KeyArea.Live ? "Live TV player" : area == KeyArea.Vod ? "Films & series player" : "Lists and menus",
                    FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 12, 0, 4), Foreground = (Brush)FindResource("Accent"),
                });
                foreach (var a in KeyMap.Actions.Where(x => x.Area == area))
                {
                    var action = a;
                    var row = new DockPanel { Margin = new Thickness(0, 2, 0, 2) };
                    var clear = Btn("Clear", () => { KeyMap.ClearKeys(action.Id); FillKeys(); });
                    var add = Btn("Add a button", () =>
                    {
                        var key = CaptureKey(action.Label);
                        if (key == null) return;
                        var taken = KeyMap.Assign(action.Id, key);
                        App.Home.Toast(KeyMap.Pretty(key) + " → " + action.Label + (taken.Count > 0 ? "  (taken from: " + string.Join(", ", taken) + ")" : ""));
                        FillKeys();
                    });
                    DockPanel.SetDock(clear, Dock.Right);
                    DockPanel.SetDock(add, Dock.Right);
                    var keys = KeyMap.KeysFor(action.Id);
                    var label = new TextBlock { VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
                    label.Inlines.Add(new System.Windows.Documents.Run(action.Label));
                    label.Inlines.Add(new System.Windows.Documents.Run("   " + (keys.Count == 0 ? "(no button)" : string.Join("  ·  ", keys.Select(KeyMap.Pretty)))) { Foreground = (Brush)FindResource("SubText") });
                    row.Children.Add(clear);
                    row.Children.Add(add);
                    row.Children.Add(label);
                    _keyRows.Children.Add(row);
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
                Margin = new Thickness(30), Foreground = (Brush)FindResource("TextBrush"),
            };
            var w = new Window
            {
                Owner = Owner, Title = "Remote button", Width = 420, SizeToContent = SizeToContent.Height,
                WindowStartupLocation = WindowStartupLocation.CenterOwner, ResizeMode = ResizeMode.NoResize, ShowInTaskbar = false,
                Background = (Brush)FindResource("Surface"), Content = text,
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

        // ------------------------------------------------------------------ keys and helpers

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
