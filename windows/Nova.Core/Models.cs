using System;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json;

namespace Nova.Core
{
    /// <summary>An Xtream Codes account. JSON names match the Android app (for shared backups).</summary>
    public class Account
    {
        [JsonProperty("id")] public string Id { get; set; }
        [JsonProperty("name")] public string Name { get; set; }
        [JsonProperty("server")] public string Server { get; set; }
        [JsonProperty("username")] public string Username { get; set; }
        [JsonProperty("password")] public string Password { get; set; }
        /// <summary>Comes from your accounts link (kept up to date from it).</summary>
        [JsonProperty("fromList")] public bool FromList { get; set; }

        [JsonIgnore]
        public string DisplayName
        {
            get { return string.IsNullOrWhiteSpace(Name) ? Username + " @ " + HostOf(Server) : Name; }
        }

        private static string HostOf(string server)
        {
            Uri u;
            return Uri.TryCreate(XtreamApi.NormalizeServer(server ?? ""), UriKind.Absolute, out u) ? u.Host : server;
        }

        public Account Copy()
        {
            return new Account { Id = Id, Name = Name, Server = Server, Username = Username, Password = Password, FromList = FromList };
        }
    }

    public class UserInfo
    {
        public string Status { get; set; }
        /// <summary>Expiry, unix seconds (null = unlimited / unknown).</summary>
        public long? ExpDate { get; set; }
        public string MaxConnections { get; set; }
        public string ActiveConnections { get; set; }
        public bool IsTrial { get; set; }

        public string Summary
        {
            get
            {
                var exp = ExpDate.HasValue
                    ? "expires " + DateTimeOffset.FromUnixTimeSeconds(ExpDate.Value).LocalDateTime.ToString("d MMM yyyy")
                    : "no expiry date";
                return Status + " · " + exp + " · connections " + ActiveConnections + "/" + MaxConnections + (IsTrial ? " · trial" : "");
            }
        }
    }

    public enum ContentType { Live, Movie, Series }

    /// <summary>A group of channels / films / series on one account. Key is unique across accounts.</summary>
    public class Category
    {
        public string AccountId { get; set; }
        public string Id { get; set; }
        public string Name { get; set; }
        public string Key { get { return AccountId + "|" + Id; } }
    }

    public class LiveChannel
    {
        public string AccountId { get; set; }
        public string StreamId { get; set; }
        public int Num { get; set; }
        public string Name { get; set; }
        public string Icon { get; set; }
        public string CategoryId { get; set; }
        public bool HasArchive { get; set; }
        /// <summary>When the provider added it (unix seconds, 0 = unknown).</summary>
        public long Added { get; set; }
        public string Key { get { return AccountId + "|" + StreamId; } }
    }

    public class Movie
    {
        public string AccountId { get; set; }
        public string StreamId { get; set; }
        public string Name { get; set; }
        public string Icon { get; set; }
        public string CategoryId { get; set; }
        public string Rating { get; set; }
        public string Extension { get; set; }
        public long Added { get; set; }
        public int? Year { get; set; }
        /// <summary>Film database (TMDB) number, when the provider gives it.</summary>
        public string Tmdb { get; set; }
        public string Key { get { return AccountId + "|" + StreamId; } }
    }

    public class Series
    {
        public string AccountId { get; set; }
        public string SeriesId { get; set; }
        public string Name { get; set; }
        public string Cover { get; set; }
        public string CategoryId { get; set; }
        public string Plot { get; set; }
        public string Rating { get; set; }
        public long Added { get; set; }
        public int? Year { get; set; }
        public string Tmdb { get; set; }
        public string Key { get { return AccountId + "|" + SeriesId; } }
    }

    /// <summary>Details shown on a film's or series' information page (any field may be missing).</summary>
    public class MediaDetails
    {
        public string Plot { get; set; }
        public string Director { get; set; }
        public string Cast { get; set; }
        public string Genre { get; set; }
        public string ReleaseDate { get; set; }
        public string Duration { get; set; }
        /// <summary>Rating out of 10.</summary>
        public double? Rating { get; set; }
        public string Backdrop { get; set; }
        public string Poster { get; set; }
        /// <summary>YouTube video id or full address.</summary>
        public string Trailer { get; set; }
        public string Country { get; set; }

        /// <summary>Full address of the trailer, or null.</summary>
        public string TrailerUrl
        {
            get
            {
                if (string.IsNullOrWhiteSpace(Trailer)) return null;
                var t = Trailer.Trim();
                if (t.StartsWith("http://", StringComparison.OrdinalIgnoreCase) ||
                    t.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return t;
                return "https://www.youtube.com/watch?v=" + Uri.EscapeDataString(t);
            }
        }
    }

    public class Episode
    {
        public string Id { get; set; }
        public string Title { get; set; }
        public int Season { get; set; }
        public int EpisodeNum { get; set; }
        public string Extension { get; set; }
        public string Plot { get; set; }
        public string Image { get; set; }
    }

    public class SeriesInfo
    {
        public Series Series { get; set; }
        public string Plot { get; set; }
        public string Genre { get; set; }
        public string Cast { get; set; }
        public SortedDictionary<int, List<Episode>> Episodes { get; set; }
        public MediaDetails Details { get; set; }

        public List<int> Seasons { get { return Episodes.Keys.ToList(); } }
    }

    public class EpgEntry
    {
        public string Title { get; set; }
        public string Description { get; set; }
        /// <summary>Unix milliseconds.</summary>
        public long Start { get; set; }
        public long End { get; set; }

        public DateTime StartLocal { get { return DateTimeOffset.FromUnixTimeMilliseconds(Start).LocalDateTime; } }
        public DateTime EndLocal { get { return DateTimeOffset.FromUnixTimeMilliseconds(End).LocalDateTime; } }
        public string TimeRange { get { return StartLocal.ToString("HH:mm") + " – " + EndLocal.ToString("HH:mm"); } }

        public bool IsOnAir(long nowMs) { return nowMs >= Start && nowMs < End; }

        /// <summary>0..1 share of the programme already shown.</summary>
        public double Progress(long nowMs)
        {
            if (End <= Start) return 0;
            return Math.Max(0, Math.Min(1, (nowMs - Start) / (double)(End - Start)));
        }
    }

    /// <summary>A past programme that can be replayed (catch-up / timeshift).</summary>
    public class CatchupEntry
    {
        public string Title { get; set; }
        public string Description { get; set; }
        public long Start { get; set; }
        public long End { get; set; }
        /// <summary>Start as the server writes it, "yyyy-MM-dd:HH-mm", used in the timeshift address.</summary>
        public string ServerStart { get; set; }
        public int DurationMinutes { get; set; }
        public DateTime StartLocal { get { return DateTimeOffset.FromUnixTimeMilliseconds(Start).LocalDateTime; } }
        public string When { get { return StartLocal.ToString("ddd d MMM  HH:mm") + " – " + DateTimeOffset.FromUnixTimeMilliseconds(End).LocalDateTime.ToString("HH:mm"); } }
    }

    public static class ItemType
    {
        public const string Live = "live";
        public const string Movie = "movie";
        public const string Series = "series";
        public const string Episode = "episode";
    }

    /// <summary>A lightweight reference used for favorites and playback (same JSON as the Android app).</summary>
    public class SavedItem
    {
        [JsonProperty("type")] public string Type { get; set; }
        [JsonProperty("id")] public string Id { get; set; }
        [JsonProperty("name")] public string Name { get; set; }
        [JsonProperty("icon", NullValueHandling = NullValueHandling.Ignore)] public string Icon { get; set; }
        [JsonProperty("extension", NullValueHandling = NullValueHandling.Ignore)] public string Extension { get; set; }
        [JsonProperty("savedAt")] public long SavedAt { get; set; }
        [JsonProperty("accountId")] public string AccountId { get; set; }

        [JsonIgnore] public string Key { get { return AccountId + "|" + Id; } }
        [JsonIgnore] public string ResumeKey { get { return Type + "_" + AccountId + "_" + Id; } }

        /// <summary>Episodes: the series they belong to (kept with the item so the series page can open).</summary>
        [JsonProperty("seriesId", NullValueHandling = NullValueHandling.Ignore)] public string SeriesId { get; set; }

        public static SavedItem Of(Series s, Episode e)
        {
            return new SavedItem
            {
                Type = ItemType.Episode, Id = e.Id, AccountId = s.AccountId, Extension = e.Extension, SeriesId = s.SeriesId,
                Name = s.Name + " · S" + e.Season + " E" + Math.Max(1, e.EpisodeNum) + (string.IsNullOrWhiteSpace(e.Title) ? "" : " · " + e.Title),
                Icon = e.Image ?? s.Cover,
            };
        }

        public bool SameAs(SavedItem other)
        {
            return other != null && Type == other.Type && Id == other.Id && AccountId == other.AccountId;
        }

        public static SavedItem Of(LiveChannel c)
        {
            return new SavedItem { Type = ItemType.Live, Id = c.StreamId, Name = c.Name, Icon = c.Icon, AccountId = c.AccountId };
        }

        public static SavedItem Of(Movie m)
        {
            return new SavedItem { Type = ItemType.Movie, Id = m.StreamId, Name = m.Name, Icon = m.Icon, Extension = m.Extension, AccountId = m.AccountId };
        }

        public static SavedItem Of(Series s)
        {
            return new SavedItem { Type = ItemType.Series, Id = s.SeriesId, Name = s.Name, Icon = s.Cover, AccountId = s.AccountId };
        }

        public LiveChannel ToChannel()
        {
            return new LiveChannel { AccountId = AccountId, StreamId = Id, Name = Name, Icon = Icon, CategoryId = "" };
        }

        public Movie ToMovie()
        {
            return new Movie { AccountId = AccountId, StreamId = Id, Name = Name, Icon = Icon, CategoryId = "", Extension = Extension ?? "mp4", Year = XtreamApi.YearFromName(Name) };
        }

        public Series ToSeries()
        {
            return new Series { AccountId = AccountId, SeriesId = Id, Name = Name, Cover = Icon, CategoryId = "", Year = XtreamApi.YearFromName(Name) };
        }
    }
}
