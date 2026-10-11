using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Nova.Core;

namespace Nova.App
{
    /// <summary>Films or Series: groups on the left, a grid of posters on the right.</summary>
    public partial class VodView : UserControl, IView, IReloadable
    {
        private readonly ContentType _type;
        private readonly ObservableCollection<PosterRow> _rows = new ObservableCollection<PosterRow>();
        private List<PosterItem> _items = new List<PosterItem>();
        private GroupRow _group;
        private int _loadId;
        private int _columns = 1;
        private bool _loaded;

        public VodView(ContentType type)
        {
            InitializeComponent();
            _type = type;
            Posters.ItemsSource = _rows;
            Groups.Type = type;
            Groups.Opened += OnGroupOpened;
            Groups.GoRight += FocusGrid;
            App.Store.FavoritesChanged += OnFavoritesChanged;
            App.Store.SettingsChanged += () => { if (_group != null) Rebuild(null); };
        }

        private string SavedType { get { return _type == ContentType.Movie ? ItemType.Movie : ItemType.Series; } }
        private string Noun { get { return _type == ContentType.Movie ? "film" : "series"; } }

        // ---------------------------------------------------------------- IView

        public void OnShown()
        {
            if (!_loaded) { _loaded = true; Groups.Load(); }
        }

        public void OnHidden() { }

        public void FocusStart()
        {
            if (_items.Count > 0) FocusGrid(); else Groups.FocusList();
        }

        public void Reload()
        {
            _items.Clear();
            _rows.Clear();
            if (_loaded) Groups.Load();
        }

        // ---------------------------------------------------------------- loading

        private async void OnGroupOpened(GroupRow row)
        {
            _group = row;
            var id = ++_loadId;
            _items = new List<PosterItem>();
            _rows.Clear();
            var multi = App.Store.Accounts.Count > 1;
            GroupTitle.Text = row.Kind == GroupKind.Favorites ? "★ Favorites"
                : row.Category.Name + (multi ? "  ·  " + row.Account.DisplayName : "");
            Count.Text = "";
            SetStatus(_type == ContentType.Movie ? "Loading films…" : "Loading series…");
            var favs = new HashSet<string>(App.Store.FavoritesOf(SavedType).Select(f => f.Key));
            var list = new List<PosterItem>();
            try
            {
                if (_type == ContentType.Movie)
                {
                    var movies = row.Kind == GroupKind.Favorites
                        ? App.Store.FavoritesOf(ItemType.Movie).Select(f => f.ToMovie()).ToList()
                        : await App.Catalog.Movies(row.Category);
                    foreach (var m in movies) list.Add(new PosterItem { Movie = m, IsFavorite = favs.Contains(m.Key), AccountTag = AccountTagFor(m.AccountId, multi) });
                }
                else
                {
                    var series = row.Kind == GroupKind.Favorites
                        ? App.Store.FavoritesOf(ItemType.Series).Select(f => f.ToSeries()).ToList()
                        : await App.Catalog.SeriesOf(row.Category);
                    foreach (var s in series) list.Add(new PosterItem { Series = s, IsFavorite = favs.Contains(s.Key), AccountTag = AccountTagFor(s.AccountId, multi) });
                }
            }
            catch (Exception e)
            {
                if (id == _loadId) SetStatus("Couldn't load this group: " + e.Message + "\nPress F5 to try again.");
                return;
            }
            if (id != _loadId) return;
            _items = list;
            Count.Text = list.Count + " " + (_type == ContentType.Movie ? (list.Count == 1 ? "film" : "films") : "series");
            SetStatus(list.Count == 0 ? (row.Kind == GroupKind.Favorites ? "No favorite " + Noun + " yet. Press F on a poster to add it." : "Nothing in this group.") : null);
            Rebuild(null);
        }

        private static string AccountTagFor(string accountId, bool multi)
        {
            if (!multi) return null;
            var a = App.Store.Account(accountId);
            return a == null ? null : a.DisplayName;
        }

        private void SetStatus(string text)
        {
            ListStatus.Text = text ?? "";
            ListStatus.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
        }

        private double TileWidth
        {
            get
            {
                switch (App.Store.Settings.PosterSize)
                {
                    case "small": return 128;
                    case "large": return 200;
                    default: return 158;
                }
            }
        }

        /// <summary>Lays the posters out in rows that fit the window width.</summary>
        private void Rebuild(PosterItem keepFocus)
        {
            var tile = TileWidth;
            var width = Math.Max(200, GridHost.ActualWidth - 30);
            _columns = Math.Max(1, (int)(width / (tile + 12)));
            var titles = App.Store.Settings.PosterTitles ? Visibility.Visible : Visibility.Collapsed;
            foreach (var p in _items) { p.TileWidth = tile; p.TitleVisibility = titles; }
            _rows.Clear();
            for (int i = 0; i < _items.Count; i += _columns)
                _rows.Add(new PosterRow { Items = _items.Skip(i).Take(_columns).ToList() });
            if (keepFocus != null) Dispatcher.BeginInvoke(new Action(() => FocusItem(keepFocus)), System.Windows.Threading.DispatcherPriority.Loaded);
        }

        private void OnGridSize(object sender, SizeChangedEventArgs e)
        {
            if (_items.Count == 0 || Math.Abs(e.NewSize.Width - e.PreviousSize.Width) < 1) return;
            var cols = Math.Max(1, (int)(Math.Max(200, GridHost.ActualWidth - 30) / (TileWidth + 12)));
            if (cols == _columns) return;
            var focused = FocusedItem();
            Rebuild(focused);
        }

        // ---------------------------------------------------------------- focus and keys

        private PosterItem FocusedItem()
        {
            var fe = Keyboard.FocusedElement as FrameworkElement;
            return fe == null ? null : fe.DataContext as PosterItem;
        }

        private void FocusGrid()
        {
            if (_items.Count == 0) return;
            FocusItem(_items[0]);
        }

        private void FocusItem(PosterItem item)
        {
            var index = _items.IndexOf(item);
            if (index < 0) return;
            var row = _rows[index / _columns];
            Posters.UpdateLayout();
            var rowHost = Posters.ItemContainerGenerator.ContainerFromItem(row) as FrameworkElement;
            if (rowHost == null)
            {
                // Not drawn yet (far down the list): scroll to it first.
                var vsp = FindChild<VirtualizingStackPanel>(Posters);
                if (vsp != null) vsp.BringIndexIntoViewPublic(index / _columns);
                Posters.UpdateLayout();
                rowHost = Posters.ItemContainerGenerator.ContainerFromItem(row) as FrameworkElement;
            }
            if (rowHost == null) return;
            var button = FindButtonFor(rowHost, item);
            if (button != null) { button.Focus(); button.BringIntoView(); }
        }

        private static Button FindButtonFor(DependencyObject root, PosterItem item)
        {
            var count = VisualTreeHelper.GetChildrenCount(root);
            for (int i = 0; i < count; i++)
            {
                var child = VisualTreeHelper.GetChild(root, i);
                var b = child as Button;
                if (b != null && b.DataContext == item) return b;
                var found = FindButtonFor(child, item);
                if (found != null) return found;
            }
            return null;
        }

        private static T FindChild<T>(DependencyObject root) where T : DependencyObject
        {
            var count = VisualTreeHelper.GetChildrenCount(root);
            for (int i = 0; i < count; i++)
            {
                var child = VisualTreeHelper.GetChild(root, i);
                var t = child as T;
                if (t != null) return t;
                var found = FindChild<T>(child);
                if (found != null) return found;
            }
            return null;
        }

        private void OnGridKeyDown(object sender, KeyEventArgs e)
        {
            var item = FocusedItem();
            if (item == null) return;
            var index = _items.IndexOf(item);
            if (index < 0) return;
            switch (e.Key)
            {
                case Key.F:
                    e.Handled = true;
                    var now = App.Store.ToggleFavorite(item.Saved);
                    item.IsFavorite = now;
                    App.Home.Toast(now ? "Added to favorites: " + item.Title : "Removed from favorites: " + item.Title);
                    break;
                case Key.Left:
                    if (index % _columns == 0) { e.Handled = true; Groups.FocusList(); }
                    else { e.Handled = true; FocusItem(_items[index - 1]); }
                    break;
                case Key.Right:
                    e.Handled = true;
                    if (index + 1 < _items.Count && (index + 1) % _columns != 0) FocusItem(_items[index + 1]);
                    break;
                case Key.Down:
                    e.Handled = true;
                    if (index + _columns < _items.Count) FocusItem(_items[index + _columns]);
                    else if (index / _columns < (_items.Count - 1) / _columns) FocusItem(_items[_items.Count - 1]);
                    break;
                case Key.Up:
                    e.Handled = true;
                    if (index - _columns >= 0) FocusItem(_items[index - _columns]);
                    else App.Home.FocusTabs();
                    break;
                case Key.PageDown:
                    e.Handled = true;
                    FocusItem(_items[Math.Min(_items.Count - 1, index + _columns * 3)]);
                    break;
                case Key.PageUp:
                    e.Handled = true;
                    FocusItem(_items[Math.Max(0, index - _columns * 3)]);
                    break;
                case Key.Home:
                    e.Handled = true;
                    FocusItem(_items[0]);
                    break;
                case Key.End:
                    e.Handled = true;
                    FocusItem(_items[_items.Count - 1]);
                    break;
            }
        }

        private void OnPosterClick(object sender, RoutedEventArgs e)
        {
            var item = ((FrameworkElement)sender).DataContext as PosterItem;
            if (item == null) return;
            if (item.Movie != null) App.Home.PushPage(new MovieDetailView(item.Movie));
            else App.Home.PushPage(new SeriesDetailView(item.Series));
        }

        private void OnFavoritesChanged()
        {
            Groups.RefreshFavorites();
            var favs = new HashSet<string>(App.Store.FavoritesOf(SavedType).Select(f => f.Key));
            foreach (var p in _items) p.IsFavorite = favs.Contains(p.Movie != null ? p.Movie.Key : p.Series.Key);
            if (_group != null && _group.Kind == GroupKind.Favorites)
            {
                var keep = FocusedItem();
                _items = _items.Where(p => p.IsFavorite).ToList();
                Count.Text = _items.Count + " " + (_type == ContentType.Movie ? (_items.Count == 1 ? "film" : "films") : "series");
                Rebuild(keep != null && keep.IsFavorite ? keep : null);
            }
        }
    }
}
