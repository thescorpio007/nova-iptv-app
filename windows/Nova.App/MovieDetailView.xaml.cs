using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Nova.Core;

namespace Nova.App
{
    /// <summary>A film's information page: poster, rating, plot, director, cast, trailer, Play.</summary>
    public partial class MovieDetailView : UserControl, IView
    {
        private readonly Movie _movie;
        private MediaDetails _details;
        private bool _started;

        public MovieDetailView(Movie movie)
        {
            InitializeComponent();
            _movie = movie;
            TitleText.Text = movie.Name;
            PosterTitle.Text = movie.Name;
            Img.SetUrl(Poster, movie.Icon);
            Meta.Text = DetailHelpers.Meta(movie.Year, null);
            double r;
            if (movie.Rating != null && double.TryParse(movie.Rating, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out r))
                ShowRating(r);
            else RatingRow.Visibility = Visibility.Collapsed;
            DetailHelpers.SetFavButton(FavBtn, App.Store.IsFavorite(SavedItem.Of(movie)));
            DetailHelpers.SetFact(DirectorLabel, Director, null);
            DetailHelpers.SetFact(CastLabel, Cast, null);
            DetailHelpers.SetFact(ReleaseLabel, Release, null);
            PreviewKeyDown += OnKey;
        }

        public void OnShown()
        {
            UpdateDownloadButton();
            if (_started) return;
            _started = true;
            LoadDetails();
        }

        public void OnHidden() { }

        public void FocusStart() { PlayBtn.Focus(); }

        private async void LoadDetails()
        {
            Status.Text = "Loading information…";
            try
            {
                _details = await App.Catalog.MovieInfo(_movie);
            }
            catch (Exception e)
            {
                Status.Text = "Couldn't load the information: " + e.Message;
                return;
            }
            Status.Text = "";
            var d = _details;
            Meta.Text = DetailHelpers.Meta(_movie.Year, d);
            if (d.Rating.HasValue && d.Rating.Value > 0) ShowRating(d.Rating.Value);
            Plot.Text = d.Plot ?? "";
            DetailHelpers.SetFact(DirectorLabel, Director, d.Director);
            DetailHelpers.SetFact(CastLabel, Cast, d.Cast);
            DetailHelpers.SetFact(ReleaseLabel, Release, d.ReleaseDate);
            if (string.IsNullOrEmpty(_movie.Icon) && !string.IsNullOrEmpty(d.Poster)) Img.SetUrl(Poster, d.Poster);
            if (!string.IsNullOrEmpty(d.Backdrop)) Img.SetUrl(Backdrop, d.Backdrop);
            TrailerBtn.Visibility = d.TrailerUrl != null ? Visibility.Visible : Visibility.Collapsed;
        }

        private void ShowRating(double r)
        {
            RatingRow.Visibility = Visibility.Visible;
            Stars.Text = DetailHelpers.Stars(r);
            RatingText.Text = DetailHelpers.RatingText(r);
        }

        private void OnKey(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.F && !(Keyboard.FocusedElement is TextBox)) { e.Handled = true; OnFav(null, null); }
            else if (e.Key == Key.D) { e.Handled = true; OnDownload(null, null); }
            else if (e.Key == Key.PageDown) { e.Handled = true; Scroll.PageDown(); }
            else if (e.Key == Key.PageUp) { e.Handled = true; Scroll.PageUp(); }
            else if (e.Key == Key.Down && Keyboard.FocusedElement is Button) { e.Handled = true; Scroll.LineDown(); Scroll.LineDown(); }
            else if (e.Key == Key.Up && Keyboard.FocusedElement is Button && Scroll.VerticalOffset > 0) { e.Handled = true; Scroll.LineUp(); Scroll.LineUp(); }
        }

        private async void OnPlay(object sender, RoutedEventArgs e)
        {
            await Play(false);
        }

        private async System.Threading.Tasks.Task Play(bool downloadToo)
        {
            if (App.Vlc == null) { App.Home.Toast("The video engine (VLC) didn't start, so videos can't play."); return; }
            var item = PlayItem.Of(_movie);
            if (downloadToo)
            {
                item.DownloadWatch = true;
                item.StartDownload();
                UpdateDownloadButton();
            }
            PlayBtn.IsEnabled = false;
            try { await PlayerWindow.Play(Window.GetWindow(this), new List<PlayItem> { item }, 0); }
            finally { PlayBtn.IsEnabled = true; PlayBtn.Focus(); UpdateDownloadButton(); }
        }

        private async void OnDownloadWatch(object sender, RoutedEventArgs e)
        {
            await Play(true);
        }

        private void OnDownload(object sender, RoutedEventArgs e)
        {
            var url = App.Catalog.MovieUrl(_movie);
            var existing = App.Downloads.DoneFile(url);
            if (existing != null) { App.Home.Toast("Already downloaded: " + existing); return; }
            var d = PlayItem.Of(_movie).StartDownload();
            App.Home.Toast("⬇ Downloading to " + System.IO.Path.GetDirectoryName(d.FinalPath) + " — see the Downloads tab");
            UpdateDownloadButton();
        }

        private void UpdateDownloadButton()
        {
            var url = App.Catalog.MovieUrl(_movie);
            var d = App.Downloads.Find(url);
            DownloadBtn.Content = d == null ? "⬇  Download"
                : App.Downloads.DoneFile(url) != null ? "✓  Downloaded"
                : "⬇  In Downloads";
            WatchDlBtn.Visibility = App.Downloads.DoneFile(url) != null ? Visibility.Collapsed : Visibility.Visible;
        }

        private void OnFav(object sender, RoutedEventArgs e)
        {
            var now = App.Store.ToggleFavorite(SavedItem.Of(_movie));
            DetailHelpers.SetFavButton(FavBtn, now);
            App.Home.Toast(now ? "Added to favorites" : "Removed from favorites");
        }

        private void OnTrailer(object sender, RoutedEventArgs e)
        {
            DetailHelpers.OpenTrailer(_details);
        }

        private void OnBack(object sender, RoutedEventArgs e)
        {
            App.Home.Back();
        }
    }
}
