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
    public partial class VodView : UserControl, IView, IReloadable, IBackHandler, IZoneActions
    {
        private readonly ContentType _type;
        private readonly ObservableCollection<PosterRow> _rows = new ObservableCollection<PosterRow>();
        private List<PosterItem> _items = new List<PosterItem>();
        private GroupRow _group;
        private int _loadId;
        private int _columns = 1;
        private bool _loaded;

        private bool _edit;
        private PosterItem _moving;
        private List<Movie> _movies = new List<Movie>();
        private List<Series> _series = new List<Series>();
        private System.Windows.Threading.DispatcherTimer _clickTimer;
        private PosterItem _clickItem;

        public VodView(ContentType type)
        {
            InitializeComponent();
            _type = type;
            Posters.ItemsSource = _rows;
            Groups.Type = type;
            Groups.Opened += OnGroupOpened;
            Groups.GoRight += FocusGrid;
            App.Store.FavoritesChanged += OnFavoritesChanged;
            App.Store.SettingsChanged += () => { UpdateSortText(); if (_group != null) Fill(null); };
            App.Store.OrgChanged += () => Dispatcher.BeginInvoke(new Action(() => { if (!_edit) Groups.Build(); }));
            Filtering.Changed += () => { if (_group != null) Fill(FocusedItem() != null ? FocusedItem().Key : null); };
            _dotTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
            _dotTimer.Tick += (s, e) => { _dotTimer.Stop(); foreach (var p in _items) p.RefreshDot(); };
            App.Dots.Changed += () => Dispatcher.BeginInvoke(new Action(() => { if (IsVisible && !_dotTimer.IsEnabled) _dotTimer.Start(); }));
            IsVisibleChanged += (s, e) => { if (IsVisible) WantDots(); };
            UpdateSortText();
        }

        private readonly System.Windows.Threading.DispatcherTimer _dotTimer;

        /// <summary>Status dots: the films of the open group are checked quietly (one at a time).</summary>
        private void WantDots()
        {
            if (_type != ContentType.Movie || !App.Store.Settings.StatusDots) return;
            App.Dots.Want(_items.Where(p => p.Movie != null && !p.Hidden).Take(300)
                .Select(p => new StreamCheck.Target { Key = p.DotKey, Url = App.Catalog.MovieUrl(p.Movie), AccountId = p.Movie.AccountId }));
        }

        private string SavedType { get { return _type == ContentType.Movie ? ItemType.Movie : ItemType.Series; } }
        private string Noun { get { return _type == ContentType.Movie ? "film" : "series"; } }
        private string Zone { get { return Organize.Zone(_type); } }
        private string ZoneSort { get { return App.Store.Settings.SortFor(Zone); } }

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

        public bool HandleBack()
        {
            if (_edit) { ToggleEdit(); return true; }
            return false;
        }

        // ---------------------------------------------------------------- sort and edit

        private void UpdateSortText()
        {
            SortText.Text = "⇅ " + Organize.SortLabel(Zone, ZoneSort);
        }

        public void ChooseSort()
        {
            var choices = Organize.SortChoices(Zone);
            var current = choices.FindIndex(c => c.Item1 == ZoneSort);
            var i = NovaDialog.Choose(Window.GetWindow(this), _type == ContentType.Movie ? "Sort films" : "Sort series",
                "“My order” keeps the order you set with ✎ Edit.", choices.Select(c => c.Item2).ToList(), current);
            if (i < 0) return;
            var st = App.Store.Settings.Copy();
            st.SetSort(Zone, choices[i].Item1);
            App.Store.SaveSettings(st);
        }

        public void ToggleEdit()
        {
            if (!_edit && !PinGate.Unlock(Window.GetWindow(this), "Your PIN protects changes to the lists.")) return;
            _edit = !_edit;
            if (_moving != null) { _moving.Moving = false; _moving = null; }
            EditText.Text = _edit ? "✓ Done" : "✎ Edit";
            EditText.Foreground = (Brush)FindResource(_edit ? "StarBrush" : "SubText");
            EditHint.Visibility = _edit ? Visibility.Visible : Visibility.Collapsed;
            HideHint.Visibility = _edit ? Visibility.Collapsed : Visibility.Visible;
            Groups.EditMode = _edit;
            var f = FocusedItem();
            if (_group != null) Fill(f != null ? f.Key : null);
            App.Home.Toast(_edit ? "Edit: hide and reorder groups and posters — Esc when done" : "Changes saved");
        }

        private void OnSort(object sender, RoutedEventArgs e) { ChooseSort(); }
        private void OnEdit(object sender, RoutedEventArgs e) { ToggleEdit(); }

        private bool CanMove { get { return _group != null && (_group.Kind == GroupKind.Group || _group.Kind == GroupKind.Favorites); } }
        private bool CanHide { get { return _group != null && (_group.Kind == GroupKind.Group || _group.Kind == GroupKind.New); } }

        private void MovePoster(PosterItem item, int delta)
        {
            if (item == null || !CanMove) return;
            var keys = _items.Select(p => p.Key).ToList();
            List<string> moved;
            if (delta == int.MinValue) moved = Nova.Core.Org.MoveTo(keys, item.Key, true);
            else if (delta == int.MaxValue) moved = Nova.Core.Org.MoveTo(keys, item.Key, false);
            else moved = Nova.Core.Org.Move(keys, item.Key, delta);
            if (_group.Kind == GroupKind.Favorites)
            {
                var favs = App.Store.FavoritesOf(SavedType);
                App.Store.ReorderFavorites(SavedType, moved.Select(k => favs.FirstOrDefault(f => f.Key == k)).Where(f => f != null).ToList());
            }
            else Organize.MoveItem(Zone, _group.Key, keys, item.Key, delta);
            if (_group.Kind == GroupKind.Group && ZoneSort != "custom" && !_edit)
                App.Home.Toast("Saved in “My order” (the list is sorted by " + Organize.SortLabel(Zone, ZoneSort) + ")");
            Fill(item.Key);
        }

        private void TogglePosterHidden(PosterItem item)
        {
            if (item == null || !CanHide) return;
            var hiding = !Organize.IsItemHidden(Zone, item.Key);
            var index = _items.IndexOf(item);
            Organize.ToggleItemHidden(Zone, item.Key, item.Title);
            App.Home.Toast(hiding ? "Hidden: " + item.Title + "  (Settings › Hidden items to show it again)" : "Shown again: " + item.Title);
            Fill(Organize.ShowsHidden(_edit) || !hiding ? item.Key : null, index);
        }

        /// <summary>Double Enter / double-click on a poster: “Hide this film?” — No is highlighted first.</summary>
        private void AskHide(PosterItem item)
        {
            if (item == null) return;
            if (!CanHide) { App.Home.Toast("Hide posters from their own group (not from Favorites)."); return; }
            var hidden = Organize.IsItemHidden(Zone, item.Key);
            var w = Window.GetWindow(this);
            if (!NovaDialog.Ask(w, hidden ? "Show this " + Noun + " again?" : "Hide this " + Noun + "?",
                item.Title + (hidden ? "" : "\n\nYou can show it again from Settings › Hidden items."), hidden ? "Yes, show it" : "Yes, hide it")) { FocusItem(item); return; }
            if (!PinGate.Unlock(w, "Your PIN protects hiding " + (_type == ContentType.Movie ? "films." : "series."))) return;
            TogglePosterHidden(item);
        }

        private void OnPosterMenu(object sender, ContextMenuEventArgs e)
        {
            var b = (FrameworkElement)sender;
            var item = b.DataContext as PosterItem;
            var menu = b.ContextMenu;
            if (item == null || menu == null) { e.Handled = true; return; }
            menu.Items.Clear();
            var w = Window.GetWindow(this);
            Action<string, Action> add = (text, act) => { var mi = new MenuItem { Header = text }; mi.Click += (s, a) => act(); menu.Items.Add(mi); };
            add("Open", () => Open(item));
            add(item.IsFavorite ? "Remove from favorites" : "Add to favorites", () => ToggleFavorite(item));
            if (CanHide) add(Organize.IsItemHidden(Zone, item.Key) ? "Show again" : "Hide this " + Noun, () => { if (PinGate.Unlock(w, "Your PIN protects hiding.")) TogglePosterHidden(item); });
            if (CanMove)
            {
                menu.Items.Add(new Separator());
                add("Move left / up", () => { if (PinGate.Unlock(w, "Your PIN protects changes.")) MovePoster(item, -1); });
                add("Move right / down", () => { if (PinGate.Unlock(w, "Your PIN protects changes.")) MovePoster(item, 1); });
                add("Move to the start", () => { if (PinGate.Unlock(w, "Your PIN protects changes.")) MovePoster(item, int.MinValue); });
                add("Move to the end", () => { if (PinGate.Unlock(w, "Your PIN protects changes.")) MovePoster(item, int.MaxValue); });
            }
        }

        // ---------------------------------------------------------------- loading

        private async void OnGroupOpened(GroupRow row)
        {
            _group = row;
            var id = ++_loadId;
            _items = new List<PosterItem>();
            _rows.Clear();
            if (_moving != null) { _moving = null; }
            var multi = App.Store.Accounts.Count > 1;
            GroupTitle.Text = row.Kind == GroupKind.Favorites ? "★ Favorites"
                : row.Kind == GroupKind.New ? "🆕 Recently added"
                : row.Category.Name + (multi ? "  ·  " + row.Account.DisplayName : "");
            Count.Text = "";
            SetStatus(_type == ContentType.Movie ? "Loading films…" : "Loading series…");
            try
            {
                if (_type == ContentType.Movie)
                {
                    _movies = row.Kind == GroupKind.Favorites ? App.Store.FavoritesOf(ItemType.Movie).Select(f => f.ToMovie()).ToList()
                        : row.Kind == GroupKind.New ? await App.Catalog.RecentlyAdded<Movie>(ContentType.Movie, 300)
                        : await App.Catalog.Movies(row.Category);
                }
                else
                {
                    _series = row.Kind == GroupKind.Favorites ? App.Store.FavoritesOf(ItemType.Series).Select(f => f.ToSeries()).ToList()
                        : row.Kind == GroupKind.New ? await App.Catalog.RecentlyAdded<Series>(ContentType.Series, 300)
                        : await App.Catalog.SeriesOf(row.Category);
                }
            }
            catch (Exception e)
            {
                if (id == _loadId) SetStatus("Couldn't load this group: " + e.Message + "\nPress F5 to try again.");
                return;
            }
            if (id != _loadId) return;
            Fill(null);
        }

        /// <summary>Builds the posters from the loaded list: your order or the sort, hidden ones only in edit mode.</summary>
        private void Fill(string keepKey, int keepIndex = -1)
        {
            if (_group == null) return;
            var multi = App.Store.Accounts.Count > 1;
            var favs = new HashSet<string>(App.Store.FavoritesOf(SavedType).Select(f => f.Key));
            var gk = _group.Key;
            var movingKey = _moving != null ? _moving.Key : null;
            var list = new List<PosterItem>();
            int total;
            var groupName = _group.Category != null ? _group.Category.Name : null;
            var filtered = 0;
            if (_type == ContentType.Movie)
            {
                total = _movies.Count;
                foreach (var m in Organize.Items(Zone, gk, _movies, x => x.Key, _edit, ZoneSort, Organize.Info))
                {
                    if (Filtering.Hides(m, groupName)) { filtered++; continue; }
                    list.Add(new PosterItem { Movie = m, IsFavorite = favs.Contains(m.Key), AccountTag = AccountTagFor(m.AccountId, multi), Hidden = Organize.IsItemHidden(Zone, m.Key), DotKey = StreamCheck.KeyOf("movie", m.Key) });
                }
            }
            else
            {
                total = _series.Count;
                foreach (var s in Organize.Items(Zone, gk, _series, x => x.Key, _edit, ZoneSort, Organize.Info))
                {
                    if (Filtering.Hides(s, groupName)) { filtered++; continue; }
                    list.Add(new PosterItem { Series = s, IsFavorite = favs.Contains(s.Key), AccountTag = AccountTagFor(s.AccountId, multi), Hidden = Organize.IsItemHidden(Zone, s.Key) });
                }
            }
            total -= filtered;
            if (_group.Kind == GroupKind.Favorites) list.ForEach(p => p.Hidden = false);
            _moving = null;
            if (movingKey != null) { _moving = list.FirstOrDefault(p => p.Key == movingKey); if (_moving != null) _moving.Moving = true; }
            _items = list;
            var hidden = total - list.Count(p => !p.Hidden);
            var noun = _type == ContentType.Movie ? (list.Count == 1 ? "film" : "films") : "series";
            Count.Text = list.Count(p => !p.Hidden) + " " + noun + (hidden > 0 && _group.Kind == GroupKind.Group ? "  ·  " + hidden + " hidden" : "") +
                         (filtered > 0 ? "  ·  🔞 " + filtered + " hidden by the filter" : "");
            SetStatus(list.Count == 0 ? (_group.Kind == GroupKind.Favorites ? "No favorite " + Noun + " yet. Press F on a poster to add it."
                : _group.Kind == GroupKind.New ? "Nothing new yet (open a few groups first; new " + (_type == ContentType.Movie ? "films" : "series") + " show here)." : "Nothing in this group.") : null);
            PosterItem keep = keepKey == null ? null : list.FirstOrDefault(p => p.Key == keepKey);
            if (keep == null && keepIndex >= 0 && list.Count > 0) keep = list[Math.Min(keepIndex, list.Count - 1)];
            Rebuild(keep);
            WantDots();
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
            var width = Math.Max(200, GridHost.ActualWidth - 30);
            var tile = TileWidth;
            var fixedCols = App.Store.Settings.PosterColumns;
            if (fixedCols > 0) { _columns = fixedCols; tile = Math.Max(70, width / fixedCols - 12); }
            else _columns = Math.Max(1, (int)(width / (tile + 12)));
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
            if (cols == _columns && App.Store.Settings.PosterColumns <= 0) return;
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
            if (_edit && HandleEditKey(item, index, e)) { e.Handled = true; return; }
            switch (e.Key)
            {
                case Key.F:
                    e.Handled = true;
                    ToggleFavorite(item);
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

        /// <summary>Edit mode: Enter picks a poster up / puts it down, arrows move it, H hides, Home / End.</summary>
        private bool HandleEditKey(PosterItem item, int index, KeyEventArgs e)
        {
            switch (e.Key)
            {
                case Key.Enter:
                    if (!CanMove) { if (CanHide) TogglePosterHidden(item); return true; }
                    if (_moving == item) { item.Moving = false; _moving = null; }
                    else { if (_moving != null) _moving.Moving = false; _moving = item; item.Moving = true; }
                    return true;
                case Key.H:
                    TogglePosterHidden(item);
                    return true;
            }
            if (_moving != item) return false;
            switch (e.Key)
            {
                case Key.Left: if (index > 0) MovePoster(item, -1); return true;
                case Key.Right: if (index + 1 < _items.Count) MovePoster(item, 1); return true;
                case Key.Up: if (index - _columns >= 0) MovePoster(item, -_columns); return true;
                case Key.Down: if (index + _columns < _items.Count) MovePoster(item, Math.Min(_columns, _items.Count - 1 - index)); return true;
                case Key.Home: MovePoster(item, int.MinValue); return true;
                case Key.End: MovePoster(item, int.MaxValue); return true;
            }
            return false;
        }

        private void ToggleFavorite(PosterItem item)
        {
            var now = App.Store.ToggleFavorite(item.Saved);
            item.IsFavorite = now;
            App.Home.Toast(now ? "Added to favorites: " + item.Title : "Removed from favorites: " + item.Title);
        }

        /// <summary>
        /// One Enter / click opens the poster; a second one within 350 ms asks to hide it instead (as on the box).
        /// In edit mode Enter is handled by the keys above.
        /// </summary>
        private void OnPosterClick(object sender, RoutedEventArgs e)
        {
            var item = ((FrameworkElement)sender).DataContext as PosterItem;
            if (item == null) return;
            if (_edit)
            {
                // Mouse click in edit mode: pick up / put down.
                if (!CanMove) return;
                if (_moving == item) { item.Moving = false; _moving = null; }
                else { if (_moving != null) _moving.Moving = false; _moving = item; item.Moving = true; }
                return;
            }
            if (_clickTimer != null && _clickTimer.IsEnabled && _clickItem == item)
            {
                _clickTimer.Stop();
                AskHide(item);
                return;
            }
            if (_clickTimer == null)
            {
                _clickTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMilliseconds(350) };
                _clickTimer.Tick += (s, a) => { _clickTimer.Stop(); if (_clickItem != null) Open(_clickItem); };
            }
            _clickTimer.Stop();
            _clickItem = item;
            _clickTimer.Start();
        }

        private void Open(PosterItem item)
        {
            if (item.Movie != null) App.Home.PushPage(new MovieDetailView(item.Movie));
            else App.Home.PushPage(new SeriesDetailView(item.Series));
        }

        private void OnFavoritesChanged()
        {
            Groups.RefreshFavorites();
            var favs = new HashSet<string>(App.Store.FavoritesOf(SavedType).Select(f => f.Key));
            foreach (var p in _items) p.IsFavorite = favs.Contains(p.Key);
            if (_group != null && _group.Kind == GroupKind.Favorites)
            {
                var keep = FocusedItem();
                if (_type == ContentType.Movie) _movies = App.Store.FavoritesOf(ItemType.Movie).Select(f => f.ToMovie()).ToList();
                else _series = App.Store.FavoritesOf(ItemType.Series).Select(f => f.ToSeries()).ToList();
                Fill(keep != null && keep.IsFavorite ? keep.Key : null);
            }
        }
    }
}
