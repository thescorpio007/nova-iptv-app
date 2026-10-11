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
    /// The group list on the left of every zone: ★ Favorites, then the groups of each account
    /// in its own section (▾ open / ▸ closed), like the Android app.
    /// </summary>
    public partial class GroupPanel : UserControl
    {
        private readonly ObservableCollection<GroupRow> _rows = new ObservableCollection<GroupRow>();
        private readonly HashSet<string> _closed = new HashSet<string>();
        private readonly DispatcherTimer _focusTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(350) };
        private List<GroupSection> _sections = new List<GroupSection>();
        private string _openKey;
        private int _loadId;

        public ContentType Type { get; set; }

        /// <summary>A group (or Favorites) was opened.</summary>
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
                if (row != null && row.Key != null && row.Key != _openKey) Open(row);
            };
        }

        public string OpenKey { get { return _openKey; } }

        private string SavedType
        {
            get { return Type == ContentType.Live ? ItemType.Live : Type == ContentType.Movie ? ItemType.Movie : ItemType.Series; }
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

        private void Build()
        {
            var selectedKey = GroupList.SelectedItem is GroupRow ? ((GroupRow)GroupList.SelectedItem).Key : null;
            _rows.Clear();
            var multi = _sections.Count > 1;
            if (App.Store.FavoritesOf(SavedType).Count > 0)
                _rows.Add(new GroupRow { Kind = GroupKind.Favorites });
            foreach (var s in _sections)
            {
                var closed = _closed.Contains(s.Account.Id);
                if (multi) _rows.Add(new GroupRow { Kind = GroupKind.Header, Account = s.Account, Closed = closed });
                if (multi && closed) continue;
                foreach (var c in s.Groups)
                    _rows.Add(new GroupRow { Kind = GroupKind.Group, Category = c, Account = s.Account, ShowSections = multi });
                if (s.Error != null)
                    _rows.Add(new GroupRow { Kind = GroupKind.Info, Account = s.Account, Message = "Couldn't load: " + s.Error + "  (Enter = retry)" });
                else if (s.Groups.Count == 0 && !multi)
                    ShowStatus("This account has no groups here.");
            }
            if (selectedKey != null)
            {
                var again = _rows.FirstOrDefault(r => r.Key == selectedKey);
                if (again != null) GroupList.SelectedItem = again;
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
            if (_closed.Contains(header.Account.Id)) _closed.Remove(header.Account.Id); else _closed.Add(header.Account.Id);
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
                    if (row.Key != _openKey) Open(row);
                    if (fromKeyboard) { var h = GoRight; if (h != null) h(); }
                    break;
            }
        }

        private void OnSelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            var row = GroupList.SelectedItem as GroupRow;
            if (row == null || row.Key == null || row.Key == _openKey) { _focusTimer.Stop(); return; }
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
            Activate(item.DataContext as GroupRow, false);
        }

        private void OnListKeyDown(object sender, KeyEventArgs e)
        {
            var row = GroupList.SelectedItem as GroupRow;
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
