using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Nova.Core;

namespace Nova.App
{
    /// <summary>Recently watched channels, films, series and episodes (all accounts together).</summary>
    public partial class RecentView : UserControl, IView
    {
        public class Row
        {
            public SavedItem Item { get; set; }
            public string Name { get; set; }
            public string Sub { get; set; }
            public string Icon { get; set; }
            public double ThumbWidth { get; set; }
            public Stretch Stretch { get; set; }
            public Thickness ThumbMargin { get; set; }
        }

        public RecentView()
        {
            InitializeComponent();
            App.Store.RecentChanged += () => Dispatcher.BeginInvoke(new Action(() => { if (IsVisible) Fill(); }));
        }

        public void OnShown() { Fill(); }
        public void OnHidden() { }

        public void FocusStart()
        {
            if (RecentList.Items.Count == 0) { ClearBtn.Focus(); return; }
            if (RecentList.SelectedIndex < 0) RecentList.SelectedIndex = 0;
            RecentList.UpdateLayout();
            var c = RecentList.ItemContainerGenerator.ContainerFromIndex(RecentList.SelectedIndex) as ListBoxItem;
            if (c != null) c.Focus(); else RecentList.Focus();
        }

        private void Fill()
        {
            var multi = App.Store.Accounts.Count > 1;
            var rows = App.Store.Recent.Select(r =>
            {
                var kind = r.Type == ItemType.Live ? "Channel" : r.Type == ItemType.Movie ? "Film" : r.Type == ItemType.Series ? "Series" : "Episode";
                var acc = App.Store.Account(r.AccountId);
                var when = DateTimeOffset.FromUnixTimeMilliseconds(r.SavedAt).LocalDateTime;
                var pos = App.Store.GetPosition(r.ResumeKey);
                var live = r.Type == ItemType.Live;
                return new Row
                {
                    Item = r,
                    Name = r.Name,
                    Sub = kind + "  ·  " + when.ToString("d MMM  HH:mm") + (pos > 0 ? "  ·  stopped at " + Playback.Time(pos) : "") +
                          (multi && acc != null ? "  ·  " + acc.DisplayName : ""),
                    Icon = r.Icon,
                    ThumbWidth = live ? 90 : 40,
                    Stretch = live ? Stretch.Uniform : Stretch.UniformToFill,
                    ThumbMargin = live ? new Thickness(4) : new Thickness(0),
                };
            }).ToList();
            var sel = RecentList.SelectedIndex;
            RecentList.ItemsSource = rows;
            if (rows.Count > 0) RecentList.SelectedIndex = Math.Max(0, Math.Min(sel, rows.Count - 1));
            Empty.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            ClearBtn.Visibility = rows.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        }

        private void OnKey(object sender, KeyEventArgs e)
        {
            var row = RecentList.SelectedItem as Row;
            if (row == null) return;
            if (e.Key == Key.Enter) { e.Handled = true; Open(row); }
            else if (e.Key == Key.Delete)
            {
                e.Handled = true;
                App.Store.State.Recent.RemoveAll(r => r.SameAs(row.Item));
                App.Store.SaveState();
                Fill();
                FocusStart();
            }
        }

        private void OnDoubleClick(object sender, MouseButtonEventArgs e)
        {
            var row = RecentList.SelectedItem as Row;
            if (row != null) Open(row);
        }

        private async void Open(Row row)
        {
            var r = row.Item;
            if (App.Store.Account(r.AccountId) == null) { App.Home.Toast("This account was removed."); return; }
            switch (r.Type)
            {
                case ItemType.Live:
                    {
                        var channels = App.Store.Recent.Where(x => x.Type == ItemType.Live).ToList();
                        var items = channels.Select(x => PlayItem.Of(x.ToChannel())).ToList();
                        await PlayerWindow.Play(Window.GetWindow(this), items, channels.FindIndex(x => x.SameAs(r)));
                        break;
                    }
                case ItemType.Movie:
                    await PlayerWindow.Play(Window.GetWindow(this), new List<PlayItem> { PlayItem.Of(r.ToMovie()) }, 0);
                    break;
                case ItemType.Series:
                    App.Home.PushPage(new SeriesDetailView(r.ToSeries()));
                    break;
                default:
                    {
                        // An episode: play it, then the rest of its season.
                        var series = new Series { AccountId = r.AccountId, SeriesId = r.SeriesId, Name = (r.Name ?? "").Split('·')[0].Trim() };
                        if (string.IsNullOrEmpty(r.SeriesId))
                        {
                            var ep = new Episode { Id = r.Id, Extension = r.Extension ?? "mp4", Title = r.Name, Season = 1 };
                            var item = PlayItem.Of(series, ep, 0);
                            item.Title = r.Name;
                            await PlayerWindow.Play(Window.GetWindow(this), new List<PlayItem> { item }, 0);
                            break;
                        }
                        try
                        {
                            var info = await App.Catalog.SeriesInfo(series);
                            var all = info.Episodes.Values.SelectMany(x => x).ToList();
                            var index = all.FindIndex(x => x.Id == r.Id);
                            var items = all.Select((x, i) => PlayItem.Of(info.Series, x, i)).ToList();
                            await PlayerWindow.Play(Window.GetWindow(this), items, Math.Max(0, index));
                        }
                        catch (Exception ex) { App.Home.Toast("Couldn't open this series: " + ex.Message); }
                        break;
                    }
            }
            FocusStart();
        }

        private void OnClear(object sender, RoutedEventArgs e)
        {
            if (!NovaDialog.Ask(Window.GetWindow(this), "Clear the history?", "Recently watched will be empty. Resume positions are kept.", "Yes, clear")) return;
            App.Store.ClearRecent();
            Fill();
        }
    }
}
