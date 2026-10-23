using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using Nova.Core;

namespace Nova.App
{
    /// <summary>
    /// The group list on the left of every zone: ★ Favorites, then the groups of each account in its own section
    /// (▾ open / ▸ closed), in your order, hidden groups and groups the parental filter hides left out — like the Android app.
    /// A double click on a group asks "Hide …?" (No first, No by itself after 10 s).
    /// In edit mode: Enter picks a group up (▲ ▼ move it, Enter drops it), H hides / shows, Home / End = top / bottom.
    /// </summary>
    public partial class GroupPanel : UserControl
    {
        private readonly ObservableCollection<GroupRow> _rows = new ObservableCollection<GroupRow>();
        private readonly DispatcherTimer _focusTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(350) };
        private List<GroupSection> _sections = new List<GroupSection>();
        private List<Category> _ordered = new List<Category>();
        private string _openKey;
        private int _loadId;
        private bool _edit;
        private GroupRow _moving;

        public ContentType Type { get; set; }
        private string Zone { get { return Organize.Zone(Type); } }

        /// <summary>A group (or Favorites / Recently added) was opened.</summary>
        public event Action<GroupRow> Opened;
        /// <summary>Right arrow or Enter on a group: the owner moves the focus to its list.</summary>
        public event Action GoRight;

        public GroupPanel()
        {
            InitializeComponent();
            GroupList.ItemsSource = _rows;
            _focusTimer.Tick += (s, e) =>
            {
                _focusTimer.Stop();
                var row = GroupList.SelectedItem as GroupRow;
                if (row != null && row.Key != null && row.Key != _openKey && !_edit) Open(row);
            };
            GroupList.ContextMenuOpening += OnContextMenu;
            GroupList.ContextMenu = new ContextMenu();
            GroupList.MouseDoubleClick += OnDoubleClick;
            Filtering.Changed += () => { if (_sections.Count > 0 && !_edit) Build(); };
        }

        /// <summary>Double click on a group: "Hide the group …?" — No first, No by itself after 10 s.</summary>
        private void OnDoubleClick(object sender, MouseButtonEventArgs e)
        {
            if (_edit) return;
            var item = ItemsControl.ContainerFromElement(GroupList, e.OriginalSource as DependencyObject) as ListBoxItem;
            var row = item == null ? null : item.DataContext as GroupRow;
            if (row == null || row.Kind != GroupKind.Group) return;
            e.Handled = true;
            var w = Window.GetWindow(this);
            if (!NovaDialog.AskTimed(w, "Hide the group “" + row.Category.Name + "”?", "You can show it again in Settings › Hidden items.", "Yes, hide", "No", 10, false)) return;
            ToggleHidden(row);
        }

        public string OpenKey { get { return _openKey; } }

        private string SavedType
        {
            get { return Type == ContentType.Live ? ItemType.Live : Type == ContentType.Movie ? ItemType.Movie : ItemType.Series; }
        }

        /// <summary>Edit mode: hidden groups shown faded (when no PIN), groups can be moved and hidden.</summary>
        public bool EditMode
        {
            get { return _edit; }
            set
            {
                _edit = value;
                if (_moving != null) { _moving.Moving = false; _moving = null; }
                Heading.Text = _edit ? "Groups — EDIT: Enter pick up / drop · ▲ ▼ move · H hide / show · Home / End top / bottom" : "Groups";
                Heading.Foreground = (System.Windows.Media.Brush)FindResource(_edit ? "StarBrush" : "DimText");
                Build();
            }
        }

        /// <summary>Downloads the groups of every account (or uses the copy in memory).</summary>
        public async void Load()
        {
            var id = ++_loadId;
            _rows.Clear();
            if (App.Store.Accounts.Count == 0) { ShowStatus("No account yet."); return; }
            ShowStatus("Loading groups…");
            List<GroupSection> sections;
            try
            {
                sections = await App.Catalog.Sections(Type);
            }
            catch (Exception e)
            {
                if (id == _loadId) ShowStatus("Couldn't load the groups: " + e.Message);
                return;
            }
            if (id != _loadId) return;
            _sections = sections;
            ShowStatus(null);
            Build();

            var keep = _openKey != null ? _rows.FirstOrDefault(r => r.Key == _openKey) : null;
            var first = keep ?? _rows.FirstOrDefault(r => r.Key != null);
            if (first != null)
            {
                _openKey = null;
                GroupList.SelectedItem = first;
                GroupList.ScrollIntoView(first);
                Open(first);
                // The list itself had the focus while loading: give it to the selected line.
                if (GroupList.IsKeyboardFocused) FocusRow(first);
            }
            else if (_rows.Count == 0)
            {
                ShowStatus("This zone is empty.");
            }
        }

        /// <summary>Builds the lines again (after an order / hide / fold change).</summary>
        public void Build()
        {
            var selectedKey = GroupList.SelectedItem is GroupRow ? ((GroupRow)GroupList.SelectedItem).Key : null;
            var selectedHeader = GroupList.SelectedItem is GroupRow && ((GroupRow)GroupList.SelectedItem).Kind == GroupKind.Header
                ? ((GroupRow)GroupList.SelectedItem).Account.Id : null;
            var movingKey = _moving != null ? _moving.Key : null;
            _rows.Clear();
            _ordered = Organize.OrderedGroups(Zone, _sections);
            var multi = _sections.Count > 1;
            var showHidden = Organize.ShowsHidden(_edit);
            if (App.Store.FavoritesOf(SavedType).Count > 0) _rows.Add(new GroupRow { Kind = GroupKind.Favorites });
            var anyGroup = false;
            foreach (var s in _sections)
            {
                var closed = Organize.IsClosed(Zone, s.Account.Id);
                if (multi) _rows.Add(new GroupRow { Kind = GroupKind.Header, Account = s.Account, Closed = closed });
                if (multi && closed) continue;
                foreach (var c in Organize.Groups(Zone, _ordered, s.Account.Id, showHidden))
                {
                    if (Filtering.HidesGroup(Zone, c)) continue;
                    _rows.Add(new GroupRow { Kind = GroupKind.Group, Category = c, Account = s.Account, ShowSections = multi, Hidden = Organize.IsGroupHidden(Zone, c.Key) });
                    anyGroup = true;
                }
                if (s.Error != null)
                    _rows.Add(new GroupRow { Kind = GroupKind.Info, Account = s.Account, Message = "Couldn't load: " + s.Error + "  (Enter = retry)" });
            }
            if (!anyGroup && !multi && _sections.Count == 1 && _sections[0].Error == null && _sections[0].Groups.Count == 0)
                ShowStatus("This account has no groups here.");
            GroupRow again = null;
            if (selectedKey != null) again = _rows.FirstOrDefault(r => r.Key == selectedKey);
            if (again == null && selectedHeader != null) again = _rows.FirstOrDefault(r => r.Kind == GroupKind.Header && r.Account.Id == selectedHeader);
            if (again != null) GroupList.SelectedItem = again;
            if (movingKey != null)
            {
                _moving = _rows.FirstOrDefault(r => r.Key == movingKey);
                if (_moving != null) _moving.Moving = true;
            }
        }

        /// <summary>Adds or removes the ★ Favorites line after a favorite changed.</summary>
        public void RefreshFavorites()
        {
            var has = App.Store.FavoritesOf(SavedType).Count > 0;
            var row = _rows.FirstOrDefault(r => r.Kind == GroupKind.Favorites);
            if (has && row == null) _rows.Insert(0, new GroupRow { Kind = GroupKind.Favorites });
            else if (!has && row != null)
            {
                var wasSelected = GroupList.SelectedItem == row;
                _rows.Remove(row);
                if (wasSelected || _openKey == Catalog.FavKey)
                {
                    var first = _rows.FirstOrDefault(r => r.Key != null);
                    if (first != null) { GroupList.SelectedItem = first; Open(first); }
                }
            }
        }

        private void ShowStatus(string text)
        {
            Status.Text = text ?? "";
            Status.Visibility = string.IsNullOrEmpty(text) ? Visibility.Collapsed : Visibility.Visible;
        }

        private void Open(GroupRow row)
        {
            _focusTimer.Stop();
            _openKey = row.Key;
            var h = Opened;
            if (h != null) h(row);
        }

        private void Toggle(GroupRow header)
        {
            Organize.SetClosed(Zone, header.Account.Id, !Organize.IsClosed(Zone, header.Account.Id));
            Build();
            var again = _rows.FirstOrDefault(r => r.Kind == GroupKind.Header && r.Account.Id == header.Account.Id);
            if (again != null)
            {
                GroupList.SelectedItem = again;
                FocusRow(again);
            }
        }

        private void Activate(GroupRow row, bool fromKeyboard)
        {
            if (row == null) return;
            switch (row.Kind)
            {
                case GroupKind.Header:
                    Toggle(row);
                    break;
                case GroupKind.Info:
                    App.Catalog.Forget("cats|");
                    Load();
                    break;
                default:
                    if (_edit && row.Kind == GroupKind.Group && fromKeyboard)
                    {
                        // Edit: Enter picks the group up / puts it down.
                        if (_moving == row) { row.Moving = false; _moving = null; }
                        else { if (_moving != null) _moving.Moving = false; _moving = row; row.Moving = true; }
                        return;
                    }
                    if (row.Key != _openKey) Open(row);
                    if (fromKeyboard) { var h = GoRight; if (h != null) h(); }
                    break;
            }
        }

        private void Move(GroupRow row, int delta)
        {
            if (row == null || row.Kind != GroupKind.Group) return;
            if (!PinGate.Unlock(Window.GetWindow(this), "Your PIN protects changes to the lists.")) return;
            Organize.MoveGroup(Zone, _ordered, row.Category, delta);
            Build();
            var again = _rows.FirstOrDefault(r => r.Key == row.Key);
            if (again != null) { GroupList.SelectedItem = again; FocusRow(again); }
        }

        private void ToggleHidden(GroupRow row)
        {
            if (row == null || row.Kind != GroupKind.Group) return;
            if (!PinGate.Unlock(Window.GetWindow(this), "Your PIN protects hiding and showing groups.")) return;
            var hiding = !Organize.IsGroupHidden(Zone, row.Category.Key);
            var label = row.Category.Name + (_sections.Count > 1 ? "  ·  " + row.Account.DisplayName : "");
            var index = _rows.IndexOf(row);
            Organize.ToggleGroupHidden(Zone, row.Category, label);
            App.Home.Toast(hiding ? "Hidden: " + row.Category.Name + (App.Store.HasPin ? " (show it again in Settings › Hidden items)" : "") : "Shown again: " + row.Category.Name);
            Build();
            // The highlight goes to the line that is now in its place.
            var target = _rows.FirstOrDefault(r => r.Key == row.Key) ?? (index < _rows.Count ? _rows[Math.Max(0, index)] : _rows.LastOrDefault());
            if (target != null) { GroupList.SelectedItem = target; FocusRow(target); }
        }

        private void OnSelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            var row = GroupList.SelectedItem as GroupRow;
            if (row == null || row.Key == null || row.Key == _openKey || _edit) { _focusTimer.Stop(); return; }
            // Arrow keys: open after a short pause, so moving through the list stays quick.
            if (App.Store.Settings.OpenGroupOnFocus && GroupList.IsKeyboardFocusWithin)
            {
                _focusTimer.Stop();
                _focusTimer.Start();
            }
        }

        private void OnMouseUp(object sender, MouseButtonEventArgs e)
        {
            var item = ItemsControl.ContainerFromElement(GroupList, e.OriginalSource as DependencyObject) as ListBoxItem;
            if (item == null) return;
            var row = item.DataContext as GroupRow;
            if (_edit && row != null && row.Kind == GroupKind.Group) { if (row.Key != _openKey) Open(row); return; }
            Activate(row, false);
        }

        private void OnListKeyDown(object sender, KeyEventArgs e)
        {
            var row = GroupList.SelectedItem as GroupRow;
            if (_edit && _moving != null && row == _moving)
            {
                if (e.Key == Key.Up || e.Key == Key.Down) { e.Handled = true; Move(row, e.Key == Key.Up ? -1 : 1); return; }
                if (e.Key == Key.Home || e.Key == Key.End) { e.Handled = true; Move(row, e.Key == Key.Home ? int.MinValue : int.MaxValue); return; }
                if (e.Key == Key.Left || e.Key == Key.Right) { e.Handled = true; return; } // a line being moved keeps the remote
            }
            if (_edit && (e.Key == Key.H || e.Key == Key.Delete)) { e.Handled = true; ToggleHidden(row); return; }
            if (e.Key == Key.Enter)
            {
                e.Handled = true;
                Activate(row, true);
            }
            else if (e.Key == Key.Right)
            {
                e.Handled = true;
                if (row != null && row.Kind == GroupKind.Header && row.Closed) { Toggle(row); return; }
                if (row != null && row.Key != null && row.Key != _openKey) Open(row);
                var h = GoRight; if (h != null) h();
            }
            else if (e.Key == Key.Left && row != null && row.Kind == GroupKind.Header && !row.Closed)
            {
                e.Handled = true;
                Toggle(row);
            }
        }

        private void OnContextMenu(object sender, ContextMenuEventArgs e)
        {
            var row = GroupList.SelectedItem as GroupRow;
            var menu = GroupList.ContextMenu;
            menu.Items.Clear();
            if (row == null || row.Kind != GroupKind.Group) { e.Handled = true; return; }
            Action<string, Action> add = (text, act) => { var mi = new MenuItem { Header = text }; mi.Click += (s, a) => act(); menu.Items.Add(mi); };
            add(Organize.IsGroupHidden(Zone, row.Category.Key) ? "Show again" : "Hide this group", () => ToggleHidden(row));
            add("Move up", () => Move(row, -1));
            add("Move down", () => Move(row, 1));
            add("Move to the top", () => Move(row, int.MinValue));
            add("Move to the bottom", () => Move(row, int.MaxValue));
        }

        /// <summary>Puts the keyboard focus on the selected group (or the first line).</summary>
        public void FocusList()
        {
            var row = GroupList.SelectedItem as GroupRow ?? _rows.FirstOrDefault();
            if (row == null) { GroupList.Focus(); return; }
            GroupList.SelectedItem = row;
            FocusRow(row);
        }

        private void FocusRow(GroupRow row)
        {
            GroupList.ScrollIntoView(row);
            GroupList.UpdateLayout();
            var c = GroupList.ItemContainerGenerator.ContainerFromItem(row) as ListBoxItem;
            if (c != null) c.Focus(); else GroupList.Focus();
        }
    }
}
