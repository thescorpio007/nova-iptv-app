using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Nova.Core;
using Nova.Core.Streaming;

namespace Nova.App
{
    /// <summary>One line of the Downloads tab.</summary>
    public class DownloadRow : Notify
    {
        public DownloadItem Item { get; private set; }

        public DownloadRow(DownloadItem item) { Item = item; }

        public void Update(DownloadItem item)
        {
            Item = item;
            foreach (var p in new[] { "Title", "Icon", "StatusText", "StatusBrush", "Progress", "ProgressVisibility", "PauseLabel", "PauseVisibility", "PlayVisibility", "RemoveVisibility", "KindIcon" })
                Changed(p);
        }

        public string Title { get { return Item.Title; } }
        public string Icon { get { return Item.Icon; } }
        public string KindIcon { get { return Item.Kind == "recording" ? "●" : Item.Kind == "episode" ? "▤" : "▶"; } }

        public string StatusText
        {
            get
            {
                var i = Item;
                switch (i.Status)
                {
                    case DownloadStatus.Recording: return "● Recording  ·  " + Size(i.DoneBytes);
                    case DownloadStatus.Done: return "✓ Done  ·  " + Size(i.TotalBytes > 0 ? i.TotalBytes : i.DoneBytes) + (File.Exists(i.FinalPath) ? "" : "  ·  file missing");
                    case DownloadStatus.Downloading:
                        return (i.Error != null ? "⟳ " + i.Error + " Reconnecting…  ·  " : "Downloading  ·  ") + Done(i);
                    case DownloadStatus.Waiting: return "Waiting — your account's only connection is in use  ·  " + Done(i);
                    case DownloadStatus.Paused: return "Paused  ·  " + Done(i);
                    case DownloadStatus.Finishing: return "Saving the file…";
                    case DownloadStatus.Failed: return "Failed: " + (i.Error ?? "unknown error");
                    default: return "Waiting in line  ·  " + Done(i);
                }
            }
        }

        public Brush StatusBrush
        {
            get
            {
                var key = Item.Status == DownloadStatus.Failed ? "ErrorBrush" : Item.Status == DownloadStatus.Done ? "Accent"
                    : Item.Status == DownloadStatus.Recording ? "FavBrush" : "SubText";
                return (Brush)Application.Current.Resources[key];
            }
        }

        public double Progress { get { return Item.TotalBytes > 0 ? (double)Item.DoneBytes / Item.TotalBytes : 0; } }

        public Visibility ProgressVisibility
        {
            get
            {
                var s = Item.Status;
                return s == DownloadStatus.Done || s == DownloadStatus.Failed || s == DownloadStatus.Recording || Item.TotalBytes <= 0 ? Visibility.Collapsed : Visibility.Visible;
            }
        }

        public string PauseLabel
        {
            get
            {
                if (Item.Status == DownloadStatus.Recording) return "■ Stop";
                return Item.Status == DownloadStatus.Paused || Item.Status == DownloadStatus.Failed ? "Resume" : "Pause";
            }
        }

        public Visibility PauseVisibility
        {
            get { return Item.Status == DownloadStatus.Done || Item.Status == DownloadStatus.Finishing ? Visibility.Collapsed : Visibility.Visible; }
        }

        public Visibility PlayVisibility
        {
            get
            {
                var playable = Item.Status == DownloadStatus.Done || Item.Status == DownloadStatus.Recording ||
                               (Item.Kind != "recording" && Item.Status != DownloadStatus.Failed && Item.Url != null);
                return playable ? Visibility.Visible : Visibility.Collapsed;
            }
        }

        public Visibility RemoveVisibility { get { return Item.Status == DownloadStatus.Recording ? Visibility.Collapsed : Visibility.Visible; } }

        private static string Done(DownloadItem i)
        {
            if (i.TotalBytes <= 0) return Size(i.DoneBytes);
            return (int)(100.0 * i.DoneBytes / i.TotalBytes) + "%  ·  " + Size(i.DoneBytes) + " of " + Size(i.TotalBytes);
        }

        public static string Size(long b)
        {
            if (b < 0) b = 0;
            if (b >= 1L << 30) return (b / (double)(1L << 30)).ToString("0.00") + " GB";
            return (b / (double)(1L << 20)).ToString("0") + " MB";
        }
    }

    /// <summary>The Downloads tab: downloads (with progress, pause, resume) and recordings.</summary>
    public partial class DownloadsView : UserControl, IView
    {
        private readonly ObservableCollection<DownloadRow> _rows = new ObservableCollection<DownloadRow>();
        private readonly DispatcherTimer _refresh = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };

        public DownloadsView()
        {
            InitializeComponent();
            DlList.ItemsSource = _rows;
            _refresh.Tick += (s, e) => { _refresh.Stop(); Refresh(); };
            App.Downloads.Changed += () => Dispatcher.BeginInvoke(new Action(() => { if (!_refresh.IsEnabled) _refresh.Start(); }));
        }

        public void OnShown()
        {
            FolderText.Text = "Saved in " + KnownFolders.NovaDownloads(App.Store.Settings) + "  (Films, Series, Recordings)";
            Refresh();
        }

        public void OnHidden() { }

        public void FocusStart()
        {
            if (_rows.Count == 0) { OpenFolderBtn.Focus(); return; }
            if (DlList.SelectedIndex < 0) DlList.SelectedIndex = 0;
            DlList.UpdateLayout();
            var c = DlList.ItemContainerGenerator.ContainerFromIndex(DlList.SelectedIndex) as ListBoxItem;
            if (c != null) c.Focus(); else DlList.Focus();
        }

        private void Refresh()
        {
            var items = App.Downloads.Items;
            var byId = _rows.ToDictionary(r => r.Item.Id);
            // Same order as the manager; update lines in place so the focus stays put.
            for (int i = 0; i < items.Count; i++)
            {
                DownloadRow row;
                if (byId.TryGetValue(items[i].Id, out row))
                {
                    row.Update(items[i]);
                    var at = _rows.IndexOf(row);
                    if (at != i) _rows.Move(at, i);
                }
                else _rows.Insert(i, new DownloadRow(items[i]));
            }
            while (_rows.Count > items.Count) _rows.RemoveAt(_rows.Count - 1);
            Empty.Visibility = _rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        // ---------------------------------------------------------------- actions

        private static DownloadRow RowOf(object sender)
        {
            return ((FrameworkElement)sender).DataContext as DownloadRow;
        }

        private void OnPauseClick(object sender, RoutedEventArgs e) { PauseOrResume(RowOf(sender)); }
        private void OnPlayClick(object sender, RoutedEventArgs e) { Play(RowOf(sender)); }
        private void OnFolderClick(object sender, RoutedEventArgs e) { ShowInFolder(RowOf(sender)); }
        private void OnRemoveClick(object sender, RoutedEventArgs e) { Remove(RowOf(sender)); }

        private void OnListKey(object sender, KeyEventArgs e)
        {
            var row = DlList.SelectedItem as DownloadRow;
            if (row == null) return;
            switch (e.Key)
            {
                case Key.Enter: e.Handled = true; Play(row); break;
                case Key.Space: e.Handled = true; PauseOrResume(row); break;
                case Key.O: e.Handled = true; ShowInFolder(row); break;
                case Key.Delete: e.Handled = true; Remove(row); break;
            }
        }

        private void OnDoubleClick(object sender, MouseButtonEventArgs e)
        {
            var c = ItemsControl.ContainerFromElement(DlList, e.OriginalSource as DependencyObject) as ListBoxItem;
            if (c == null) return;
            e.Handled = true;
            Play(c.DataContext as DownloadRow);
        }

        private void PauseOrResume(DownloadRow row)
        {
            if (row == null) return;
            var i = row.Item;
            if (i.Status == DownloadStatus.Recording)
            {
                var key = App.Recordings.RecordingKeys.FirstOrDefault(k =>
                {
                    var relay = App.Hub.FindLive(k);
                    return relay != null && string.Equals(relay.RecordingPath, i.FinalPath, StringComparison.OrdinalIgnoreCase);
                });
                if (key != null) App.Recordings.Stop(key);
                return;
            }
            if (i.Status == DownloadStatus.Paused || i.Status == DownloadStatus.Failed) App.Downloads.Resume(i.Id);
            else if (!i.IsDone) App.Downloads.Pause(i.Id);
        }

        private async void Play(DownloadRow row)
        {
            if (row == null) return;
            if (App.Vlc == null) { App.Home.Toast("The video engine (VLC) didn't start, so videos can't play."); return; }
            var i = row.Item;
            PlayItem item;
            if (i.IsDone || i.Kind == "recording")
            {
                if (!File.Exists(i.FinalPath)) { App.Home.Toast("The file isn't there any more: " + i.FinalPath); return; }
                item = new PlayItem { Title = i.Title, LocalPath = i.FinalPath, Ext = i.Ext };
            }
            else
            {
                // Still downloading: Download & Watch on the same connection.
                item = new PlayItem { Title = i.Title, Url = i.Url, Ext = i.Ext, AccountId = i.AccountId, DownloadWatch = true };
            }
            await PlayerWindow.Play(Window.GetWindow(this), new List<PlayItem> { item }, 0);
            FocusStart();
        }

        private void ShowInFolder(DownloadRow row)
        {
            if (row == null) return;
            var path = row.Item.FinalPath;
            try
            {
                if (File.Exists(path)) Process.Start("explorer.exe", "/select,\"" + path + "\"");
                else
                {
                    var dir = Path.GetDirectoryName(path);
                    Directory.CreateDirectory(dir);
                    Process.Start("explorer.exe", "\"" + dir + "\"");
                }
            }
            catch (Exception e) { App.Home.Toast(e.Message); }
        }

        private void Remove(DownloadRow row)
        {
            if (row == null || row.Item.Status == DownloadStatus.Recording) return;
            var i = row.Item;
            var hasFile = (i.IsDone && File.Exists(i.FinalPath)) || (i.PartsFolder != null && Directory.Exists(i.PartsFolder));
            var answer = hasFile
                ? MessageBox.Show(Window.GetWindow(this), "Remove \"" + i.Title + "\" from the list.\n\nAlso delete the video file from your PC?",
                    "Nova IPTV", MessageBoxButton.YesNoCancel, MessageBoxImage.Question)
                : MessageBoxResult.No;
            if (answer == MessageBoxResult.Cancel) return;
            App.Downloads.Remove(i.Id, answer == MessageBoxResult.Yes);
        }

        private void OnOpenFolder(object sender, RoutedEventArgs e)
        {
            try
            {
                var dir = KnownFolders.NovaDownloads(App.Store.Settings);
                Directory.CreateDirectory(dir);
                Process.Start("explorer.exe", "\"" + dir + "\"");
            }
            catch (Exception ex) { App.Home.Toast(ex.Message); }
        }
    }
}
