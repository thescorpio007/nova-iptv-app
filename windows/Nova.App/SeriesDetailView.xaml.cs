using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Nova.Core;

namespace Nova.App
{
    /// <summary>A series page: information, seasons and episodes.</summary>
    public partial class SeriesDetailView : UserControl, IView
    {
        public class SeasonItem
        {
            public int Number { get; set; }
            public string Label { get; set; }
        }

        public class EpisodeItem
        {
            public Episode Episode { get; set; }
            public string Number { get; set; }
            public string Title { get; set; }
            public string Plot { get; set; }
            public string Image { get; set; }
        }

        private readonly Series _series;
        private SeriesInfo _info;
        private bool _started;

        public SeriesDetailView(Series series)
        {
            InitializeComponent();
            _series = series;
            TitleText.Text = series.Name;
            PosterTitle.Text = series.Name;
            Img.SetUrl(Poster, series.Cover);
            Meta.Text = DetailHelpers.Meta(series.Year, null);
            Plot.Text = series.Plot ?? "";
            double r;
            if (series.Rating != null && double.TryParse(series.Rating, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out r))
                ShowRating(r);
            else RatingRow.Visibility = Visibility.Collapsed;
            DetailHelpers.SetFavButton(FavBtn, App.Store.IsFavorite(SavedItem.Of(series)));
            PreviewKeyDown += OnKey;
        }

        public void OnShown()
        {
            if (_started) return;
            _started = true;
            Load();
        }

        public void OnHidden() { }

        public void FocusStart()
        {
            if (PlayBtn.IsEnabled) PlayBtn.Focus(); else FavBtn.Focus();
        }

        private async void Load()
        {
            Status.Text = "Loading episodes…";
            try
            {
                _info = await App.Catalog.SeriesInfo(_series);
            }
            catch (Exception e)
            {
                Status.Text = "Couldn't load this series: " + e.Message + "\nPress Back and try again.";
                return;
            }
            var d = _info.Details ?? new MediaDetails();
            Meta.Text = DetailHelpers.Meta(_series.Year, d);
            if (!string.IsNullOrEmpty(_info.Plot)) Plot.Text = _info.Plot;
            var cast = _info.Cast ?? d.Cast;
            CastLine.Text = string.IsNullOrWhiteSpace(cast) ? "" : "Cast: " + cast.Trim();
            if (d.Rating.HasValue && d.Rating.Value > 0) ShowRating(d.Rating.Value);
            if (string.IsNullOrEmpty(_series.Cover) && !string.IsNullOrEmpty(d.Poster)) Img.SetUrl(Poster, d.Poster);
            if (!string.IsNullOrEmpty(d.Backdrop)) Img.SetUrl(Backdrop, d.Backdrop);
            TrailerBtn.Visibility = d.TrailerUrl != null ? Visibility.Visible : Visibility.Collapsed;

            var seasons = _info.Seasons.Select(n => new SeasonItem
            {
                Number = n,
                Label = "Season " + n + "  (" + _info.Episodes[n].Count + ")",
            }).ToList();
            Seasons.ItemsSource = seasons;
            if (seasons.Count == 0)
            {
                Status.Text = "No episodes are available for this series.";
                return;
            }
            Status.Text = "";
            Seasons.SelectedIndex = 0;
            var first = _info.Episodes[seasons[0].Number].FirstOrDefault();
            PlayBtn.IsEnabled = first != null;
            EpDlBtn.IsEnabled = SeasonDlBtn.IsEnabled = first != null;
            if (first != null) PlayText.Text = "▶  Play S" + first.Season + " E" + (first.EpisodeNum > 0 ? first.EpisodeNum : 1);
            if (IsKeyboardFocusWithin && Keyboard.FocusedElement == FavBtn) PlayBtn.Focus();
        }

        private void ShowRating(double r)
        {
            RatingRow.Visibility = Visibility.Visible;
            Stars.Text = DetailHelpers.Stars(r);
            RatingText.Text = DetailHelpers.RatingText(r);
        }

        private void OnSeasonChanged(object sender, SelectionChangedEventArgs e)
        {
            var s = Seasons.SelectedItem as SeasonItem;
            if (s == null || _info == null) { Episodes.ItemsSource = null; return; }
            Episodes.ItemsSource = _info.Episodes[s.Number].Select(ep => new EpisodeItem
            {
                Episode = ep,
                Number = (ep.EpisodeNum > 0 ? ep.EpisodeNum : 0).ToString(),
                Title = (ep.EpisodeNum > 0 ? ep.EpisodeNum + ".  " : "") + ep.Title,
                Plot = ep.Plot,
                Image = ep.Image ?? _series.Cover,
            }).ToList();
        }

        // ---------------------------------------------------------------- keys

        private void OnKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.F) { e.Handled = true; OnFav(null, null); return; }
            if (e.Key == Key.Down && Keyboard.FocusedElement is Button && Seasons.Items.Count > 0)
            {
                e.Handled = true;
                FocusListItem(Seasons);
            }
        }

        private void OnSeasonKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Down || e.Key == Key.Enter)
            {
                e.Handled = true;
                FocusListItem(Episodes);
            }
            else if (e.Key == Key.Up)
            {
                e.Handled = true;
                FocusStart();
            }
        }

        private void OnEpisodeKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter)
            {
                e.Handled = true;
                PlayEpisode(Episodes.SelectedIndex);
            }
            else if (e.Key == Key.W)
            {
                e.Handled = true;
                PlayEpisode(Episodes.SelectedIndex, true);
            }
            else if (e.Key == Key.D)
            {
                e.Handled = true;
                OnDownloadEpisode(null, null);
            }
            else if (e.Key == Key.Up && Episodes.SelectedIndex <= 0)
            {
                e.Handled = true;
                FocusListItem(Seasons);
            }
            else if ((e.Key == Key.Left || e.Key == Key.Right) && Seasons.Items.Count > 1)
            {
                // ← → change season from the episode list too.
                e.Handled = true;
                var i = Seasons.SelectedIndex + (e.Key == Key.Right ? 1 : -1);
                if (i >= 0 && i < Seasons.Items.Count) { Seasons.SelectedIndex = i; FocusListItem(Episodes); }
            }
        }

        private void FocusListItem(ListBox list)
        {
            if (list.Items.Count == 0) return;
            if (list.SelectedIndex < 0) list.SelectedIndex = 0;
            list.ScrollIntoView(list.SelectedItem);
            list.UpdateLayout();
            var c = list.ItemContainerGenerator.ContainerFromIndex(list.SelectedIndex) as ListBoxItem;
            if (c != null) c.Focus(); else list.Focus();
        }

        private void OnEpisodeDoubleClick(object sender, MouseButtonEventArgs e)
        {
            var item = ItemsControl.ContainerFromElement(Episodes, e.OriginalSource as DependencyObject) as ListBoxItem;
            if (item == null) return;
            e.Handled = true;
            PlayEpisode(Episodes.ItemContainerGenerator.IndexFromContainer(item));
        }

        // ---------------------------------------------------------------- playing

        private void OnPlayFirst(object sender, RoutedEventArgs e)
        {
            if (Seasons.Items.Count == 0) return;
            Seasons.SelectedIndex = 0;
            PlayEpisode(0);
        }

        /// <summary>Plays an episode; the rest of the season follows automatically.</summary>
        private async void PlayEpisode(int index, bool downloadToo = false)
        {
            var season = Seasons.SelectedItem as SeasonItem;
            if (season == null || _info == null || index < 0) return;
            if (App.Vlc == null) { App.Home.Toast("The video engine (VLC) didn't start, so videos can't play."); return; }
            var eps = _info.Episodes[season.Number];
            if (index >= eps.Count) return;
            var items = eps.Select((ep, i) => PlayItem.Of(_series, ep, i)).ToList();
            if (downloadToo)
            {
                items[index].DownloadWatch = true;
                items[index].StartDownload();
            }
            var last = await PlayerWindow.Play(Window.GetWindow(this), items, index);
            if (last >= 0 && last < Episodes.Items.Count && ReferenceEquals(Seasons.SelectedItem, season))
            {
                Episodes.SelectedIndex = last;
                FocusListItem(Episodes);
            }
        }

        private Episode SelectedEpisode()
        {
            var item = Episodes.SelectedItem as EpisodeItem ?? (Episodes.Items.Count > 0 ? Episodes.Items[0] as EpisodeItem : null);
            return item == null ? null : item.Episode;
        }

        private void OnDownloadEpisode(object sender, RoutedEventArgs e)
        {
            var ep = SelectedEpisode();
            if (ep == null) return;
            var d = App.Downloads.AddEpisode(_series, ep, App.Catalog.EpisodeUrl(_series.AccountId, ep));
            App.Home.Toast(d.IsDone ? "Already downloaded" : "⬇ " + d.Title + " — see the Downloads tab");
        }

        private void OnDownloadSeason(object sender, RoutedEventArgs e)
        {
            var season = Seasons.SelectedItem as SeasonItem;
            if (season == null || _info == null) return;
            var eps = _info.Episodes[season.Number];
            foreach (var ep in eps) App.Downloads.AddEpisode(_series, ep, App.Catalog.EpisodeUrl(_series.AccountId, ep));
            App.Home.Toast("⬇ " + eps.Count + " episodes of season " + season.Number + " added — one at a time, see the Downloads tab");
        }

        private void OnFav(object sender, RoutedEventArgs e)
        {
            var now = App.Store.ToggleFavorite(SavedItem.Of(_series));
            DetailHelpers.SetFavButton(FavBtn, now);
            App.Home.Toast(now ? "Added to favorites" : "Removed from favorites");
        }

        private void OnTrailer(object sender, RoutedEventArgs e)
        {
            DetailHelpers.OpenTrailer(_info == null ? null : _info.Details);
        }

        private void OnBack(object sender, RoutedEventArgs e)
        {
            App.Home.Back();
        }
    }
}
