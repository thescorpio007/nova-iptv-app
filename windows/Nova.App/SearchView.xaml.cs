using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using Nova.Core;

namespace Nova.App
{
    /// <summary>Search across every account at once (channels, films, series).</summary>
    public partial class SearchView : UserControl, IView, IReloadable
    {
        public class ResultItem
        {
            public object Payload { get; set; }
            public string Title { get; set; }
            public string Sub { get; set; }
            public string Image { get; set; }
            public double ThumbWidth { get; set; }
            public Stretch ThumbStretch { get; set; }
            public Thickness ThumbMargin { get; set; }
        }

        private readonly DispatcherTimer _typing = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        private CancellationTokenSource _cts;
        private const int MaxPerType = 300;

        public SearchView()
        {
            InitializeComponent();
            _typing.Tick += (s, e) => { _typing.Stop(); Run(); };
            FillFilters();
        }

        public void OnShown() { FillFilters(); }

        // ---------------------------------------------------------------- filters (remembered, as on the box)

        private static readonly string[] Kinds = { "all", "live", "movie", "series" };
        private static readonly string[] KindNames = { "All", "Channels", "Films", "Series" };

        private void FillFilters()
        {
            var st = App.Store.State;
            var k = Array.IndexOf(Kinds, st.SearchKind);
            KindBtn.Content = "Show: " + KindNames[k < 0 ? 0 : k] + "  ▾";
            var a = st.SearchAccount == null ? null : App.Store.Account(st.SearchAccount);
            if (a == null) st.SearchAccount = null;
            AccountBtn.Content = "In: " + (a == null ? "All servers" : a.DisplayName) + "  ▾";
            AccountBtn.Visibility = App.Store.Accounts.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
            var kind = st.SearchKind ?? "all";
            ChList.Visibility = ChTitle.Visibility = kind == "all" || kind == "live" ? Visibility.Visible : Visibility.Collapsed;
            FilmList.Visibility = FilmTitle.Visibility = kind == "all" || kind == "movie" ? Visibility.Visible : Visibility.Collapsed;
            SeriesList.Visibility = SeriesTitle.Visibility = kind == "all" || kind == "series" ? Visibility.Visible : Visibility.Collapsed;
            ChCol.Width = new GridLength(ChList.Visibility == Visibility.Visible ? 1 : 0, GridUnitType.Star);
            FilmCol.Width = new GridLength(FilmList.Visibility == Visibility.Visible ? 1 : 0, GridUnitType.Star);
            SeriesCol.Width = new GridLength(SeriesList.Visibility == Visibility.Visible ? 1 : 0, GridUnitType.Star);
        }

        private void OnKind(object sender, RoutedEventArgs e)
        {
            var st = App.Store.State;
            var i = NovaDialog.Choose(Window.GetWindow(this), "Search in", null, KindNames, Math.Max(0, Array.IndexOf(Kinds, st.SearchKind)));
            if (i < 0) return;
            st.SearchKind = Kinds[i];
            App.Store.SaveState();
            FillFilters();
            Run();
        }

        private void OnAccount(object sender, RoutedEventArgs e)
        {
            var st = App.Store.State;
            var accounts = App.Store.Accounts.ToList();
            var names = new List<string> { "All servers" };
            names.AddRange(accounts.Select(a => a.DisplayName));
            var i = NovaDialog.Choose(Window.GetWindow(this), "Search on", null, names, st.SearchAccount == null ? 0 : accounts.FindIndex(a => a.Id == st.SearchAccount) + 1);
            if (i < 0) return;
            st.SearchAccount = i == 0 ? null : accounts[i - 1].Id;
            App.Store.SaveState();
            FillFilters();
            Run();
        }
        public void OnHidden() { }

        public void FocusStart()
        {
            Box.Focus();
            Box.SelectAll();
        }

        public void Reload()
        {
            if (Box.Text.Trim().Length >= 2) Run();
        }

        private void OnTextChanged(object sender, TextChangedEventArgs e)
        {
            Placeholder.Visibility = Box.Text.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
            _typing.Stop();
            _typing.Start();
        }

        private async void Run()
        {
            if (_cts != null) _cts.Cancel();
            var q = Box.Text.Trim();
            if (q.Length < 2)
            {
                Clear();
                Status.Text = "Type at least 2 letters. The first search downloads the full lists of every account, so it can take a moment.";
                return;
            }
            var cts = new CancellationTokenSource();
            _cts = cts;
            Status.Text = "Searching…";
            SearchResults res;
            try
            {
                res = await App.Catalog.Search(q, MaxPerType, App.Store.State.SearchKind, App.Store.State.SearchAccount, cts.Token);
            }
            catch (OperationCanceledException) { return; }
            catch (Exception e)
            {
                if (!cts.IsCancellationRequested) Status.Text = "Search failed: " + e.Message;
                return;
            }
            if (cts.IsCancellationRequested) return;
            var multi = App.Store.Accounts.Count > 1;
            Func<string, string> acc = id =>
            {
                if (!multi) return null;
                var a = App.Store.Account(id);
                return a == null ? null : a.DisplayName;
            };
            // The parental filters apply to search too.
            res.Channels = res.Channels.Where(c => !Filtering.Hides(c, null)).ToList();
            res.Movies = res.Movies.Where(m => !Filtering.Hides(m, null)).ToList();
            res.Series = res.Series.Where(x => !Filtering.Hides(x, null)).ToList();
            ChList.ItemsSource = res.Channels.Select(c => new ResultItem
            {
                Payload = c, Title = c.Name, Sub = Join(c.Num > 0 ? "No. " + c.Num : null, acc(c.AccountId)), Image = c.Icon,
                ThumbWidth = 72, ThumbStretch = Stretch.Uniform, ThumbMargin = new Thickness(4),
            }).ToList();
            FilmList.ItemsSource = res.Movies.Select(m => new ResultItem
            {
                Payload = m, Title = m.Name, Sub = Join(m.Year.HasValue ? m.Year.ToString() : null, m.Rating != null ? "★ " + m.Rating : null, acc(m.AccountId)),
                Image = m.Icon, ThumbWidth = 34, ThumbStretch = Stretch.UniformToFill, ThumbMargin = new Thickness(0),
            }).ToList();
            SeriesList.ItemsSource = res.Series.Select(s => new ResultItem
            {
                Payload = s, Title = s.Name, Sub = Join(s.Year.HasValue ? s.Year.ToString() : null, s.Rating != null ? "★ " + s.Rating : null, acc(s.AccountId)),
                Image = s.Cover, ThumbWidth = 34, ThumbStretch = Stretch.UniformToFill, ThumbMargin = new Thickness(0),
            }).ToList();
            ChTitle.Text = "Channels (" + Count(res.Channels.Count) + ")";
            FilmTitle.Text = "Films (" + Count(res.Movies.Count) + ")";
            SeriesTitle.Text = "Series (" + Count(res.Series.Count) + ")";
            var status = res.IsEmpty ? "No results." : "Enter or double-click to open. ↓ from the search box goes to the results.";
            if (res.Errors.Count > 0) status += "\nSome lists couldn't be searched: " + string.Join("; ", res.Errors);
            Status.Text = status;
        }

        private static string Count(int n)
        {
            return n >= MaxPerType ? MaxPerType + "+" : n.ToString();
        }

        private static string Join(params string[] parts)
        {
            return string.Join("  ·  ", parts.Where(p => !string.IsNullOrEmpty(p)));
        }

        private void Clear()
        {
            ChList.ItemsSource = null;
            FilmList.ItemsSource = null;
            SeriesList.ItemsSource = null;
            ChTitle.Text = "Channels";
            FilmTitle.Text = "Films";
            SeriesTitle.Text = "Series";
        }

        // ---------------------------------------------------------------- keys

        private void OnBoxKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter)
            {
                e.Handled = true;
                _typing.Stop();
                Run();
            }
            else if (e.Key == Key.Up)
            {
                e.Handled = true;
                App.Home.FocusTabs();
            }
            else if (e.Key == Key.Down)
            {
                e.Handled = true;
                var first = new[] { ChList, FilmList, SeriesList }.FirstOrDefault(l => l.Items.Count > 0 && l.Visibility == Visibility.Visible);
                if (first != null) FocusList(first);
            }
        }

        private void OnListKey(object sender, KeyEventArgs e)
        {
            var list = (ListBox)sender;
            if (e.Key == Key.Enter)
            {
                e.Handled = true;
                Open(list, list.SelectedItem as ResultItem);
            }
            else if (e.Key == Key.Up && list.SelectedIndex <= 0)
            {
                e.Handled = true;
                Box.Focus();
            }
            else if (e.Key == Key.Right || e.Key == Key.Left)
            {
                e.Handled = true;
                var lists = new[] { ChList, FilmList, SeriesList };
                var i = Array.IndexOf(lists, list);
                for (int j = i + (e.Key == Key.Right ? 1 : -1); j >= 0 && j < lists.Length; j += e.Key == Key.Right ? 1 : -1)
                {
                    if (lists[j].Items.Count > 0 && lists[j].Visibility == Visibility.Visible) { FocusList(lists[j]); break; }
                }
            }
        }

        private void OnListDoubleClick(object sender, MouseButtonEventArgs e)
        {
            var list = (ListBox)sender;
            var c = ItemsControl.ContainerFromElement(list, e.OriginalSource as DependencyObject) as ListBoxItem;
            if (c == null) return;
            e.Handled = true;
            Open(list, c.DataContext as ResultItem);
        }

        private static void FocusList(ListBox list)
        {
            if (list.SelectedIndex < 0) list.SelectedIndex = 0;
            list.ScrollIntoView(list.SelectedItem);
            list.UpdateLayout();
            var c = list.ItemContainerGenerator.ContainerFromIndex(list.SelectedIndex) as ListBoxItem;
            if (c != null) c.Focus(); else list.Focus();
        }

        private async void Open(ListBox list, ResultItem item)
        {
            if (item == null) return;
            var ch = item.Payload as LiveChannel;
            if (ch != null)
            {
                if (App.Vlc == null) { App.Home.Toast("The video engine (VLC) didn't start, so videos can't play."); return; }
                var channels = ((IEnumerable<ResultItem>)ChList.ItemsSource).Select(r => (LiveChannel)r.Payload).ToList();
                var items = channels.Select(c => PlayItem.Of(c)).ToList();
                var last = await PlayerWindow.Play(Window.GetWindow(this), items, channels.IndexOf(ch));
                if (last >= 0 && last < ChList.Items.Count) { ChList.SelectedIndex = last; FocusList(ChList); }
                return;
            }
            var m = item.Payload as Movie;
            if (m != null) { App.Home.PushPage(new MovieDetailView(m)); return; }
            var s = item.Payload as Series;
            if (s != null) App.Home.PushPage(new SeriesDetailView(s));
        }
    }
}
