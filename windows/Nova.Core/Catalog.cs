using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace Nova.Core
{
    /// <summary>The groups of one account in a zone (Live TV, Films or Series).</summary>
    public class GroupSection
    {
        public Account Account { get; set; }
        public List<Category> Groups { get; set; }
        /// <summary>Why this account's groups couldn't be loaded, or null.</summary>
        public string Error { get; set; }
    }

    public class SearchResults
    {
        public List<LiveChannel> Channels = new List<LiveChannel>();
        public List<Movie> Movies = new List<Movie>();
        public List<Series> Series = new List<Series>();
        public List<string> Errors = new List<string>();
        public bool IsEmpty { get { return Channels.Count == 0 && Movies.Count == 0 && Series.Count == 0; } }
    }

    /// <summary>
    /// Loads and caches everything shown in the three zones, for every account together
    /// (same idea as the Android MainViewModel zones). Requests for the same thing share one download.
    /// </summary>
    public class Catalog
    {
        public const string FavKey = "__fav__";

        private readonly XtreamApi _api;
        private readonly Store _store;
        private readonly ConcurrentDictionary<string, Task<object>> _cache = new ConcurrentDictionary<string, Task<object>>();
        private readonly ConcurrentDictionary<string, Tuple<DateTime, List<EpgEntry>>> _epg = new ConcurrentDictionary<string, Tuple<DateTime, List<EpgEntry>>>();

        public Catalog(XtreamApi api, Store store)
        {
            _api = api;
            _store = store;
        }

        public XtreamApi Api { get { return _api; } }

        private readonly ConcurrentDictionary<string, int> _maxConnections = new ConcurrentDictionary<string, int>();

        /// <summary>Connections the account allows (0 = not known yet / unlimited).</summary>
        public int MaxConnections(string accountId)
        {
            int n;
            return accountId != null && _maxConnections.TryGetValue(accountId, out n) ? n : 0;
        }

        public void Note(string accountId, UserInfo info)
        {
            int n;
            if (info != null && int.TryParse(info.MaxConnections, out n)) _maxConnections[accountId] = n;
        }

        /// <summary>Asks every account how many connections it allows (quietly, in the background).</summary>
        public async Task CheckAccounts()
        {
            foreach (var a in _store.Accounts.ToList())
            {
                try { Note(a.Id, await _api.Authenticate(a).ConfigureAwait(false)); } catch { }
            }
        }

        /// <summary>Forgets everything downloaded (after an account change or "Reload").</summary>
        public void Clear()
        {
            _cache.Clear();
            _epg.Clear();
        }

        public void Forget(string prefix)
        {
            foreach (var k in _cache.Keys.Where(k => k.StartsWith(prefix, StringComparison.Ordinal)).ToList())
            {
                Task<object> t;
                _cache.TryRemove(k, out t);
            }
        }

        /// <summary>One shared download per key; a failed download is forgotten so it can be retried.</summary>
        private async Task<T> Cached<T>(string key, Func<Task<T>> load) where T : class
        {
            var task = _cache.GetOrAdd(key, _ => LoadBoxed(load));
            try
            {
                return (T)await task.ConfigureAwait(false);
            }
            catch
            {
                Task<object> removed;
                _cache.TryRemove(key, out removed);
                throw;
            }
        }

        private static async Task<object> LoadBoxed<T>(Func<Task<T>> load)
        {
            return await load().ConfigureAwait(false);
        }

        private static string Code(ContentType t)
        {
            return t == ContentType.Live ? "live" : t == ContentType.Movie ? "movie" : "series";
        }

        // ---------------------------------------------------------------- groups

        /// <summary>Groups of every account for one zone, loaded in parallel.</summary>
        public async Task<List<GroupSection>> Sections(ContentType type)
        {
            var accounts = _store.Accounts.ToList();
            var tasks = accounts.Select(async a =>
            {
                try
                {
                    var groups = await Cached("cats|" + Code(type) + "|" + a.Id, () => _api.Categories(a, type)).ConfigureAwait(false);
                    return new GroupSection { Account = a, Groups = groups };
                }
                catch (Exception e)
                {
                    return new GroupSection { Account = a, Groups = new List<Category>(), Error = e.Message };
                }
            }).ToList();
            return (await Task.WhenAll(tasks).ConfigureAwait(false)).ToList();
        }

        // ---------------------------------------------------------------- items of a group

        public Task<List<LiveChannel>> Channels(Category c)
        {
            var a = Need(c.AccountId);
            return Cached("items|live|" + c.Key, () => _api.LiveStreams(a, c.Id));
        }

        public Task<List<Movie>> Movies(Category c)
        {
            var a = Need(c.AccountId);
            return Cached("items|movie|" + c.Key, () => _api.Movies(a, c.Id));
        }

        public Task<List<Series>> SeriesOf(Category c)
        {
            var a = Need(c.AccountId);
            return Cached("items|series|" + c.Key, () => _api.SeriesList(a, c.Id));
        }

        /// <summary>Every channel / film / series of one account (used by search).</summary>
        public Task<List<LiveChannel>> AllChannels(Account a) { return Cached("all|live|" + a.Id, () => _api.LiveStreams(a, null)); }
        public Task<List<Movie>> AllMovies(Account a) { return Cached("all|movie|" + a.Id, () => _api.Movies(a, null)); }
        public Task<List<Series>> AllSeries(Account a) { return Cached("all|series|" + a.Id, () => _api.SeriesList(a, null)); }

        public Task<SeriesInfo> SeriesInfo(Series s)
        {
            var a = Need(s.AccountId);
            return Cached("info|series|" + s.Key, () => _api.SeriesInfo(a, s));
        }

        public Task<MediaDetails> MovieInfo(Movie m)
        {
            var a = Need(m.AccountId);
            return Cached("info|movie|" + m.Key, () => _api.MovieInfo(a, m));
        }

        private Account Need(string accountId)
        {
            var a = _store.Account(accountId);
            if (a == null) throw new NovaException("This account was removed");
            return a;
        }

        // ---------------------------------------------------------------- guide

        /// <summary>Short guide of a channel, kept 10 minutes (refreshed sooner when the programme ended).</summary>
        public async Task<List<EpgEntry>> Epg(LiveChannel ch, CancellationToken ct = default(CancellationToken))
        {
            Tuple<DateTime, List<EpgEntry>> hit;
            var now = XtreamApi.NowMs();
            if (_epg.TryGetValue(ch.Key, out hit) && (DateTime.UtcNow - hit.Item1).TotalMinutes < 10 &&
                (hit.Item2.Count == 0 || hit.Item2.Any(e => e.End > now)))
                return hit.Item2;
            var a = Need(ch.AccountId);
            var list = await _api.ShortEpg(a, ch.StreamId, 8, ct).ConfigureAwait(false);
            _epg[ch.Key] = Tuple.Create(DateTime.UtcNow, list);
            return list;
        }

        /// <summary>The guide already in memory (null when not loaded yet).</summary>
        public List<EpgEntry> EpgIfLoaded(LiveChannel ch)
        {
            Tuple<DateTime, List<EpgEntry>> hit;
            return _epg.TryGetValue(ch.Key, out hit) ? hit.Item2 : null;
        }

        // ---------------------------------------------------------------- search

        /// <summary>
        /// Searches the names of every account at once. The first search downloads the full
        /// lists of each account, so it can take a moment.
        /// </summary>
        public async Task<SearchResults> Search(string query, int maxPerType, CancellationToken ct)
        {
            var res = new SearchResults();
            var q = (query ?? "").Trim();
            if (q.Length < 2) return res;
            var words = q.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            Func<string, bool> match = name =>
            {
                if (name == null) return false;
                foreach (var w in words) if (name.IndexOf(w, StringComparison.OrdinalIgnoreCase) < 0) return false;
                return true;
            };

            foreach (var a in _store.Accounts.ToList())
            {
                ct.ThrowIfCancellationRequested();
                var live = AllChannels(a);
                var films = AllMovies(a);
                var series = AllSeries(a);
                try { res.Channels.AddRange((await live.ConfigureAwait(false)).Where(x => match(x.Name))); }
                catch (Exception e) { res.Errors.Add(a.DisplayName + " (channels): " + e.Message); }
                try { res.Movies.AddRange((await films.ConfigureAwait(false)).Where(x => match(x.Name))); }
                catch (Exception e) { res.Errors.Add(a.DisplayName + " (films): " + e.Message); }
                try { res.Series.AddRange((await series.ConfigureAwait(false)).Where(x => match(x.Name))); }
                catch (Exception e) { res.Errors.Add(a.DisplayName + " (series): " + e.Message); }
            }
            ct.ThrowIfCancellationRequested();
            // Names that start with the search come first.
            res.Channels = res.Channels.OrderBy(x => x.Name.StartsWith(q, StringComparison.OrdinalIgnoreCase) ? 0 : 1).ThenBy(x => x.Name, StringComparer.OrdinalIgnoreCase).Take(maxPerType).ToList();
            res.Movies = res.Movies.OrderBy(x => x.Name.StartsWith(q, StringComparison.OrdinalIgnoreCase) ? 0 : 1).ThenBy(x => x.Name, StringComparer.OrdinalIgnoreCase).Take(maxPerType).ToList();
            res.Series = res.Series.OrderBy(x => x.Name.StartsWith(q, StringComparison.OrdinalIgnoreCase) ? 0 : 1).ThenBy(x => x.Name, StringComparer.OrdinalIgnoreCase).Take(maxPerType).ToList();
            return res;
        }

        // ---------------------------------------------------------------- stream addresses

        public string ChannelUrl(LiveChannel c)
        {
            return XtreamApi.LiveUrl(Need(c.AccountId), c.StreamId, _store.Settings.LiveFormat == "m3u8" ? "m3u8" : "ts");
        }

        public string MovieUrl(Movie m)
        {
            return XtreamApi.MovieUrl(Need(m.AccountId), m.StreamId, string.IsNullOrEmpty(m.Extension) ? "mp4" : m.Extension);
        }

        public string EpisodeUrl(string accountId, Episode e)
        {
            return XtreamApi.EpisodeUrl(Need(accountId), e.Id, string.IsNullOrEmpty(e.Extension) ? "mp4" : e.Extension);
        }
    }
}
