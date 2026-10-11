using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Media;
using Nova.Core;

namespace Nova.App
{
    public abstract class Notify : INotifyPropertyChanged
    {
        public event PropertyChangedEventHandler PropertyChanged;

        protected void Changed([CallerMemberName] string name = null)
        {
            var h = PropertyChanged;
            if (h != null) h(this, new PropertyChangedEventArgs(name));
        }
    }

    public enum GroupKind { Header, Group, Favorites, Info }

    /// <summary>One line of the group list: an account header, a group, the Favorites group or a message.</summary>
    public class GroupRow : Notify
    {
        public GroupKind Kind { get; set; }
        public Category Category { get; set; }
        public Account Account { get; set; }
        public string Message { get; set; }
        private bool _closed;
        public bool Closed { get { return _closed; } set { _closed = value; Changed(); Changed("Text"); } }

        public string Key
        {
            get
            {
                if (Kind == GroupKind.Favorites) return Catalog.FavKey;
                if (Kind == GroupKind.Group) return Category.Key;
                return null;
            }
        }

        public string Text
        {
            get
            {
                switch (Kind)
                {
                    case GroupKind.Header: return (Closed ? "▸  " : "▾  ") + Account.DisplayName;
                    case GroupKind.Favorites: return "★  Favorites";
                    case GroupKind.Info: return Message;
                    default: return Category.Name;
                }
            }
        }

        public FontWeight Weight { get { return Kind == GroupKind.Header || Kind == GroupKind.Favorites ? FontWeights.Bold : FontWeights.Normal; } }

        public Brush Foreground
        {
            get
            {
                switch (Kind)
                {
                    case GroupKind.Header: return (Brush)Application.Current.Resources["Accent"];
                    case GroupKind.Favorites: return (Brush)Application.Current.Resources["FavBrush"];
                    case GroupKind.Info: return (Brush)Application.Current.Resources["ErrorBrush"];
                    default: return (Brush)Application.Current.Resources["TextBrush"];
                }
            }
        }

        public Thickness Indent { get { return Kind == GroupKind.Group && Account != null && ShowSections ? new Thickness(14, 0, 0, 0) : new Thickness(0); } }

        /// <summary>True when several accounts are shown (groups are indented under their account).</summary>
        public bool ShowSections { get; set; }
    }

    /// <summary>A channel in the Live TV list.</summary>
    public class ChannelRow : Notify
    {
        public LiveChannel Channel { get; set; }
        public string Number { get { return Channel.Num > 0 ? Channel.Num.ToString() : ""; } }
        public string Name { get { return Channel.Name; } }
        public string Icon { get { return Channel.Icon; } }
        public string AccountTag { get; set; }
        public Visibility LogoVisibility { get; set; }

        private bool _fav;
        public bool IsFavorite { get { return _fav; } set { _fav = value; Changed(); Changed("FavVisibility"); } }
        public Visibility FavVisibility { get { return _fav ? Visibility.Visible : Visibility.Collapsed; } }

        private bool _playing;
        public bool IsPlaying { get { return _playing; } set { _playing = value; Changed(); Changed("PlayingVisibility"); } }
        public Visibility PlayingVisibility { get { return _playing ? Visibility.Visible : Visibility.Collapsed; } }

        public Visibility TagVisibility { get { return string.IsNullOrEmpty(AccountTag) ? Visibility.Collapsed : Visibility.Visible; } }

        private bool _rec;
        public bool IsRecording { get { return _rec; } set { _rec = value; Changed(); Changed("RecVisibility"); } }
        public Visibility RecVisibility { get { return _rec ? Visibility.Visible : Visibility.Collapsed; } }
    }

    /// <summary>A film or series tile in the poster grid.</summary>
    public class PosterItem : Notify
    {
        public Movie Movie { get; set; }
        public Series Series { get; set; }
        public string Title { get { return Movie != null ? Movie.Name : Series.Name; } }
        public string Image { get { return Movie != null ? Movie.Icon : Series.Cover; } }
        public string Rating { get { var r = Movie != null ? Movie.Rating : Series.Rating; return string.IsNullOrEmpty(r) ? null : "★ " + r; } }
        public Visibility RatingVisibility { get { return Rating == null ? Visibility.Collapsed : Visibility.Visible; } }
        public string AccountTag { get; set; }
        public Visibility TagVisibility { get { return string.IsNullOrEmpty(AccountTag) ? Visibility.Collapsed : Visibility.Visible; } }

        public double TileWidth { get; set; }
        public double PosterHeight { get { return TileWidth * 1.5; } }
        public int DecodeWidth { get { return (int)(TileWidth * 1.25); } }
        public Visibility TitleVisibility { get; set; }

        private bool _fav;
        public bool IsFavorite { get { return _fav; } set { _fav = value; Changed(); Changed("FavVisibility"); } }
        public Visibility FavVisibility { get { return _fav ? Visibility.Visible : Visibility.Collapsed; } }

        public SavedItem Saved { get { return Movie != null ? SavedItem.Of(Movie) : SavedItem.Of(Series); } }
    }

    /// <summary>One row of posters (the grid is a virtualized list of rows, so huge lists stay fast).</summary>
    public class PosterRow
    {
        public List<PosterItem> Items { get; set; }
    }

    /// <summary>Something the player can play.</summary>
    public class PlayItem
    {
        public string Title { get; set; }
        public string Url { get; set; }
        public bool IsLive { get; set; }
        public LiveChannel Channel { get; set; }
        /// <summary>Second line in the player (account, season/episode…).</summary>
        public string Subtitle { get; set; }

        public string AccountId { get; set; }
        /// <summary>File type of a film / episode (mkv, mp4…).</summary>
        public string Ext { get; set; }
        /// <summary>For the Download button: the film, or the series + episode.</summary>
        public Movie Movie { get; set; }
        public Series Series { get; set; }
        public Episode Episode { get; set; }
        /// <summary>Download &amp; Watch: the player reads the download itself.</summary>
        public bool DownloadWatch { get; set; }
        /// <summary>A file on this PC (a recording), played as it is.</summary>
        public string LocalPath { get; set; }

        public static PlayItem Of(LiveChannel c)
        {
            var multi = App.Store.Accounts.Count > 1;
            var acc = App.Store.Account(c.AccountId);
            return new PlayItem
            {
                Title = (c.Num > 0 ? c.Num + "  ·  " : "") + c.Name,
                Url = App.Catalog.ChannelUrl(c),
                IsLive = true,
                Channel = c,
                AccountId = c.AccountId,
                Subtitle = multi && acc != null ? acc.DisplayName : null,
            };
        }

        public static PlayItem Of(Movie m)
        {
            var acc = App.Store.Account(m.AccountId);
            return new PlayItem
            {
                Title = m.Name,
                Url = App.Catalog.MovieUrl(m),
                Ext = string.IsNullOrEmpty(m.Extension) ? "mp4" : m.Extension,
                AccountId = m.AccountId,
                Movie = m,
                Subtitle = App.Store.Accounts.Count > 1 && acc != null ? acc.DisplayName : null,
            };
        }

        public static PlayItem Of(Series s, Episode e, int position)
        {
            return new PlayItem
            {
                Title = s.Name + "  ·  S" + e.Season + " E" + (e.EpisodeNum > 0 ? e.EpisodeNum : position + 1),
                Subtitle = e.Title,
                Url = App.Catalog.EpisodeUrl(s.AccountId, e),
                Ext = string.IsNullOrEmpty(e.Extension) ? "mp4" : e.Extension,
                AccountId = s.AccountId,
                Series = s,
                Episode = e,
            };
        }

        /// <summary>Adds this film / episode to the downloads (returns null for live channels).</summary>
        public Nova.Core.Streaming.DownloadItem StartDownload()
        {
            if (Movie != null) return App.Downloads.AddFilm(Movie, Url);
            if (Series != null && Episode != null) return App.Downloads.AddEpisode(Series, Episode, Url);
            return null;
        }
    }
}
