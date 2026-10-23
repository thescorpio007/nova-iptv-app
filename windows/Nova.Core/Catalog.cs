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
        /// <summary>The groups of a zone when they are already loaded, or null (never downloads).</summary>
        public List<GroupSection> SectionsIfLoaded(ContentType type)
        {
            var list = new List<GroupSection>();
            foreach (var a in _store.Accounts.ToList())
            {
                Task<object> t;
                if (_cache.TryGetValue("cats|" + Code(type) + "|" + a.Id, out t) && t.Status == TaskStatus.RanToCompletion && t.Result is List<Category>)
                    list.Add(new GroupSection { Account = a, Groups = (List<Category>)t.Result });
            }
            return list;
        }

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
        /// <summary>Every channel / film / series of one account: from the search index on disk when it is
        /// less than a day old, otherwise downloaded (and the index saved).</summary>
        public Task<List<LiveChannel>> AllChannels(Account a) { return Cached("all|live|" + a.Id, () => Indexed(a, "live", () => _api.LiveStreams(a, null))); }
        public Task<List<Movie>> AllMovies(Account a) { return Cached("all|movie|" + a.Id, () => Indexed(a, "movie", () => _api.Movies(a, null))); }
        public Task<List<Series>> AllSeries(Account a) { return Cached("all|series|" + a.Id, () => Indexed(a, "series", () => _api.SeriesList(a, null))); }

        /// <summary>Folder of the search index (Downloads\NovaIPTV\Index). Holds names and pictures only, never passwords.</summary>
        public Func<string> IndexFolder = () => null;

        private string IndexPath(Account a, string kind)
        {
            var f = IndexFolder();
            return f == null ? null : System.IO.Path.Combine(f, a.Id + "-" + kind + ".json");
        }

        private async Task<List<T>> Indexed<T>(Account a, string kind, Func<Task<List<T>>> download) where T : class
        {
            var path = IndexPath(a, kind);
            if (path != null)
            {
                try
                {
                    var fi = new System.IO.FileInfo(path);
                    if (fi.Exists && (DateTime.UtcNow - fi.LastWriteTimeUtc).TotalHours < 24)
                    {
                        var text = System.IO.File.ReadAllText(path);
                        var list = Newtonsoft.Json.JsonConvert.DeserializeObject<List<T>>(text);
                        if (list != null) return list;
                    }
                }
                catch { }
            }
            var fresh = await download().ConfigureAwait(false);
            SaveIndex(path, fresh);
            return fresh;
        }

        private static void SaveIndex<T>(string path, List<T> list)
        {
            if (path == null) return;
            try
            {
                System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path));
                var tmp = path + ".tmp";
                System.IO.File.WriteAllText(tmp, Newtonsoft.Json.JsonConvert.SerializeObject(list));
                if (System.IO.File.Exists(path)) System.IO.File.Delete(path);
                System.IO.File.Move(tmp, path);
            }
            catch { }
        }

        /// <summary>Quietly refreshes the search index of every account when it is older than a day.</summary>
        public async Task RefreshIndex(CancellationToken ct)
        {
            foreach (var a in _store.Accounts.ToList())
            {
                foreach (var kind in new[] { "live", "movie", "series" })
                {
                    if (ct.IsCancellationRequested) return;
                    var path = IndexPath(a, kind);
                    if (path == null) return;
                    var fi = new System.IO.FileInfo(path);
                    if (fi.Exists && (DateTime.UtcNow - fi.LastWriteTimeUtc).TotalHours < 24) continue;
                    try
                    {
                        if (kind == "live") SaveIndex(path, await _api.LiveStreams(a, null, ct).ConfigureAwait(false));
                        else if (kind == "movie") SaveIndex(path, await _api.Movies(a, null, ct).ConfigureAwait(false));
                        else SaveIndex(path, await _api.SeriesList(a, null, ct).ConfigureAwait(false));
                    }
                    catch { }
                    Forget("all|" + kind + "|" + a.Id);
                }
            }
        }

        // ================================================================== portable index (.novaindex, same as the box)
        //
        // One gzip text file holding the index of one or more servers:
        //   NOVAINDEX2 <tab> exportedAt <tab> appVersion
        //   A <tab> server label <tab> host <tab> builtAt        (starts a server section)
        //   L / M / S lines (channels, films, series)
        // No usernames, passwords or stream links: a section is linked to an account by its host.

        public const string IndexExt = "novaindex";
        public const string IndexLatest = "index-latest." + IndexExt;

        /// <summary>"http://S4.Example.net:8000/" → "s4.example.net:8000".</summary>
        public static string HostOf(string server)
        {
            var s = (server ?? "").Trim().ToLowerInvariant();
            var i = s.IndexOf("://", StringComparison.Ordinal);
            if (i >= 0) s = s.Substring(i + 3);
            var slash = s.IndexOf('/');
            if (slash >= 0) s = s.Substring(0, slash);
            return s.TrimEnd('.');
        }

        private static string Tsv(string s) { return (s ?? "").Replace('\t', ' ').Replace('\n', ' ').Replace('\r', ' '); }

        /// <summary>When the index of this account was built (unix ms), or 0 when there is none.</summary>
        public long IndexBuiltAt(Account a)
        {
            var times = new[] { "live", "movie", "series" }.Select(k => IndexPath(a, k)).Where(p => p != null && System.IO.File.Exists(p))
                .Select(p => new DateTimeOffset(System.IO.File.GetLastWriteTimeUtc(p)).ToUnixTimeMilliseconds()).ToList();
            return times.Count == 3 ? times.Min() : 0;
        }

        /// <summary>Accounts whose whole index (channels, films, series) is on disk.</summary>
        public List<Account> Exportable() { return _store.Accounts.Where(a => IndexBuiltAt(a) > 0).ToList(); }

        private static List<T> ReadJson<T>(string path)
        {
            try { return path != null && System.IO.File.Exists(path) ? Newtonsoft.Json.JsonConvert.DeserializeObject<List<T>>(System.IO.File.ReadAllText(path)) ?? new List<T>() : new List<T>(); }
            catch { return new List<T>(); }
        }

        /// <summary>Writes the index of these accounts as a .novaindex file. Returns the number of items.</summary>
        public int ExportIndex(string path, IList<Account> accounts, string appVersion)
        {
            var n = 0;
            System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path));
            var tmp = path + ".tmp";
            using (var gz = new System.IO.Compression.GZipStream(System.IO.File.Create(tmp), System.IO.Compression.CompressionMode.Compress))
            using (var w = new System.IO.StreamWriter(gz, new System.Text.UTF8Encoding(false)))
            {
                w.Write("NOVAINDEX2\t" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + "\t" + Tsv(appVersion) + "\n");
                foreach (var a in accounts)
                {
                    var built = IndexBuiltAt(a);
                    if (built == 0) continue;
                    var live = ReadJson<LiveChannel>(IndexPath(a, "live"));
                    var movies = ReadJson<Movie>(IndexPath(a, "movie"));
                    var series = ReadJson<Series>(IndexPath(a, "series"));
                    w.Write("A\t" + Tsv(a.DisplayName) + "\t" + HostOf(a.Server) + "\t" + built + "\n");
                    foreach (var c in live)
                        w.Write("L\t" + c.StreamId + "\t" + Tsv(c.Name) + "\t" + Tsv(c.Icon) + "\t" + c.CategoryId + "\t" + c.Num + "\t" + (c.HasArchive ? 1 : 0) + "\t" + c.Added + "\n");
                    foreach (var m in movies)
                        w.Write("M\t" + m.StreamId + "\t" + Tsv(m.Name) + "\t" + Tsv(m.Icon) + "\t" + m.CategoryId + "\t" + Tsv(m.Rating) + "\t" + m.Extension + "\t" + m.Added + "\t" + (m.Year.HasValue ? m.Year.Value.ToString() : "") + "\t" + (m.Tmdb ?? "") + "\n");
                    foreach (var x in series)
                        w.Write("S\t" + x.SeriesId + "\t" + Tsv(x.Name) + "\t" + Tsv(x.Cover) + "\t" + x.CategoryId + "\t" + Tsv(x.Rating) + "\t" + x.Added + "\t" + (x.Year.HasValue ? x.Year.Value.ToString() : "") + "\t" + (x.Tmdb ?? "") + "\n");
                    n += live.Count + movies.Count + series.Count;
                }
            }
            if (System.IO.File.Exists(path)) System.IO.File.Delete(path);
            System.IO.File.Move(tmp, path);
            return n;
        }

        public sealed class IndexSection
        {
            public string Name, Host;
            public long BuiltAt;
            public List<LiveChannel> Live = new List<LiveChannel>();
            public List<Movie> Movies = new List<Movie>();
            public List<Series> Series = new List<Series>();
            public int Items { get { return Live.Count + Movies.Count + Series.Count; } }
        }

        private static int ToInt(string s) { int n; return int.TryParse(s, out n) ? n : 0; }
        private static long ToLong(string s) { long n; return long.TryParse(s, out n) ? n : 0; }
        private static int? ToYear(string s) { int n; return int.TryParse(s, out n) ? n : (int?)null; }
        private static string Col(string[] p, int i) { return i < p.Length && p[i].Length > 0 ? p[i] : null; }

        /// <summary>Reads a .novaindex file (from the box or this PC). Throws NovaException when it isn't one.</summary>
        public static List<IndexSection> ReadIndexFile(string path, out long exportedAt)
        {
            exportedAt = 0;
            var list = new List<IndexSection>();
            IndexSection cur = null;
            try
            {
                using (var gz = new System.IO.Compression.GZipStream(System.IO.File.OpenRead(path), System.IO.Compression.CompressionMode.Decompress))
                using (var r = new System.IO.StreamReader(gz, System.Text.Encoding.UTF8))
                {
                    var first = r.ReadLine();
                    if (first == null || !first.StartsWith("NOVAINDEX")) throw new NovaException("This isn't a Nova search index file (.novaindex).");
                    var head = first.Split('\t');
                    int ver;
                    if (!int.TryParse(head[0].Substring(9), out ver) || ver > 2) throw new NovaException("This index file was made by a newer Nova — update Nova first.");
                    if (head.Length > 1) exportedAt = ToLong(head[1]);
                    string line;
                    while ((line = r.ReadLine()) != null)
                    {
                        var p = line.Split('\t');
                        if (p[0] == "A") { cur = new IndexSection { Name = Col(p, 1) ?? "", Host = Col(p, 2) ?? "", BuiltAt = ToLong(Col(p, 3)) }; list.Add(cur); continue; }
                        if (cur == null) continue;
                        if (p[0] == "L" && p.Length >= 8)
                            cur.Live.Add(new LiveChannel { StreamId = p[1], Name = p[2], Icon = Col(p, 3), CategoryId = p[4], Num = ToInt(p[5]), HasArchive = p[6] == "1", Added = ToLong(p[7]) });
                        else if (p[0] == "M" && p.Length >= 9)
                            cur.Movies.Add(new Movie { StreamId = p[1], Name = p[2], Icon = Col(p, 3), CategoryId = p[4], Rating = Col(p, 5), Extension = p[6], Added = ToLong(p[7]), Year = ToYear(p[8]), Tmdb = Col(p, 9) });
                        else if (p[0] == "S" && p.Length >= 8)
                            cur.Series.Add(new Series { SeriesId = p[1], Name = p[2], Cover = Col(p, 3), CategoryId = p[4], Rating = Col(p, 5), Added = ToLong(p[6]), Year = ToYear(p[7]), Tmdb = Col(p, 8) });
                    }
                }
            }
            catch (NovaException) { throw; }
            catch (Exception) { throw new NovaException("This isn't a Nova search index file (.novaindex)."); }
            return list;
        }

        /// <summary>
        /// Imports a .novaindex file: each server section goes to the accounts with the same host (replacing their index).
        /// Returns (accounts linked, servers waiting for an account, items).
        /// </summary>
        public Tuple<List<string>, List<string>, int> ImportIndex(string path, IList<Account> accounts = null)
        {
            long exported;
            var sections = ReadIndexFile(path, out exported);
            var linked = new List<string>(); var waiting = new List<string>(); var items = 0;
            foreach (var sec in sections)
            {
                var matches = (accounts ?? _store.Accounts).Where(a => HostOf(a.Server) == sec.Host).ToList();
                if (matches.Count == 0) { waiting.Add(sec.Name); continue; }
                foreach (var a in matches)
                {
                    var when = sec.BuiltAt > 0 ? DateTimeOffset.FromUnixTimeMilliseconds(sec.BuiltAt).UtcDateTime : DateTime.UtcNow;
                    WriteIndex(IndexPath(a, "live"), sec.Live.Select(c => { c.AccountId = a.Id; return c; }).ToList(), when);
                    WriteIndex(IndexPath(a, "movie"), sec.Movies.Select(m => { m.AccountId = a.Id; return m; }).ToList(), when);
                    WriteIndex(IndexPath(a, "series"), sec.Series.Select(x => { x.AccountId = a.Id; return x; }).ToList(), when);
                    foreach (var k in new[] { "live", "movie", "series" }) Forget("all|" + k + "|" + a.Id);
                    linked.Add(a.DisplayName);
                    items += sec.Items;
                }
            }
            return Tuple.Create(linked, waiting, items);
        }

        private static void WriteIndex<T>(string path, List<T> list, DateTime builtUtc)
        {
            if (path == null) return;
            SaveIndex(path, list);
            try { System.IO.File.SetLastWriteTimeUtc(path, builtUtc); } catch { }
        }

        /// <summary>Saves index-latest.novaindex in the Index folder when something changed since the last export.</summary>
        public string AutoExport(string appVersion)
        {
            var folder = IndexFolder();
            if (folder == null) return null;
            var list = Exportable();
            if (list.Count == 0) return null;
            var stamp = string.Join(",", list.Select(a => HostOf(a.Server) + "@" + IndexBuiltAt(a)).OrderBy(x => x));
            if (stamp == _store.State.IndexExportStamp && System.IO.File.Exists(System.IO.Path.Combine(folder, IndexLatest))) return null;
            var path = System.IO.Path.Combine(folder, IndexLatest);
            ExportIndex(path, list, appVersion);
            _store.State.IndexExportStamp = stamp;
            _store.State.IndexExportedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            _store.SaveState();
            return path;
        }

        /// <summary>New account (or new install): takes its index from an export file in the Index folder, if one has its server.</summary>
        public void AdoptFromExports()
        {
            var folder = IndexFolder();
            if (folder == null || !System.IO.Directory.Exists(folder)) return;
            var missing = _store.Accounts.Where(a => IndexBuiltAt(a) == 0).ToList();
            if (missing.Count == 0) return;
            var files = new System.IO.DirectoryInfo(folder).GetFiles("*." + IndexExt)
                .OrderByDescending(f => f.Name == IndexLatest).ThenByDescending(f => f.LastWriteTimeUtc).ToList();
            foreach (var f in files)
            {
                if (missing.Count == 0) break;
                try
                {
                    long at;
                    var hosts = new HashSet<string>(ReadIndexFile(f.FullName, out at).Select(x => x.Host));
                    var here = missing.Where(a => hosts.Contains(HostOf(a.Server))).ToList();
                    if (here.Count == 0) continue;
                    ImportIndex(f.FullName, here);
                    missing = missing.Except(here).ToList();
                }
                catch { }
            }
        }

        /// <summary>The newest films or series of every account (the 🆕 Recently added group).</summary>
        public async Task<List<T>> RecentlyAdded<T>(ContentType type, int max) where T : class
        {
            var all = new List<T>();
            foreach (var a in _store.Accounts.ToList())
            {
                try
                {
                    if (type == ContentType.Movie) all.AddRange((await AllMovies(a).ConfigureAwait(false)).OrderByDescending(m => m.Added).Take(max).Cast<T>());
                    else all.AddRange((await AllSeries(a).ConfigureAwait(false)).OrderByDescending(m => m.Added).Take(max).Cast<T>());
                }
                catch { }
            }
            if (type == ContentType.Movie) return all.Cast<Movie>().OrderByDescending(m => m.Added).Take(max).Cast<T>().ToList();
            return all.Cast<Series>().OrderByDescending(m => m.Added).Take(max).Cast<T>().ToList();
        }

        public Task<List<CatchupEntry>> Catchup(LiveChannel ch, CancellationToken ct)
        {
            return _api.Catchup(Need(ch.AccountId), ch.StreamId, ct);
        }

        public string CatchupUrl(LiveChannel ch, CatchupEntry e)
        {
            return XtreamApi.CatchupUrl(Need(ch.AccountId), ch.StreamId, e);
        }

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
        public Task<SearchResults> Search(string query, int maxPerType, CancellationToken ct)
        {
            return Search(query, maxPerType, "all", null, ct);
        }

        /// <summary>kind: "all", "live", "movie", "series"; accountId: one server, or null for all.</summary>
        public async Task<SearchResults> Search(string query, int maxPerType, string kind, string accountId, CancellationToken ct)
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

            var wantLive = kind == null || kind == "all" || kind == "live";
            var wantFilms = kind == null || kind == "all" || kind == "movie";
            var wantSeries = kind == null || kind == "all" || kind == "series";
            foreach (var a in _store.Accounts.ToList())
            {
                ct.ThrowIfCancellationRequested();
                if (accountId != null && a.Id != accountId) continue;
                var live = wantLive ? AllChannels(a) : null;
                var films = wantFilms ? AllMovies(a) : null;
                var series = wantSeries ? AllSeries(a) : null;
                if (live != null)
                {
                    try { res.Channels.AddRange((await live.ConfigureAwait(false)).Where(x => match(x.Name))); }
                    catch (Exception e) { res.Errors.Add(a.DisplayName + " (channels): " + e.Message); }
                }
                if (films != null)
                {
                    try { res.Movies.AddRange((await films.ConfigureAwait(false)).Where(x => match(x.Name))); }
                    catch (Exception e) { res.Errors.Add(a.DisplayName + " (films): " + e.Message); }
                }
                if (series != null)
                {
                    try { res.Series.AddRange((await series.ConfigureAwait(false)).Where(x => match(x.Name))); }
                    catch (Exception e) { res.Errors.Add(a.DisplayName + " (series): " + e.Message); }
                }
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
