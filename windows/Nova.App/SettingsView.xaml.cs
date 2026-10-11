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

        public SettingsView()
        {
            InitializeComponent();
            Fill();
            var v = Assembly.GetExecutingAssembly().GetName().Version;
            About.Text = "Nova IPTV for Windows " + v.Major + "." + v.Minor + "." + v.Build + " (32-bit)\n" +
                         "Video engine: " + (App.Vlc != null ? "VLC " + App.Vlc.Version : "not started") + "\n" +
                         "Data folder: " + App.Store.DataFolder;
        }

        public void OnShown()
        {
            Fill();
            LoadAccounts();
        }

        public void OnHidden() { }

        public void FocusStart()
        {
            AddAccountBtn.Focus();
            var firstEdit = FindFirst<Button>(AccountList);
            if (firstEdit != null) firstEdit.Focus();
        }

        public void Reload()
        {
            if (IsVisible) LoadAccounts();
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
            var ok = MessageBox.Show(Window.GetWindow(this), "Remove the account \"" + a.DisplayName + "\"?\nIts favorites are removed too.",
                "Nova IPTV", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes;
            if (!ok) return;
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
            _filling = false;
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
