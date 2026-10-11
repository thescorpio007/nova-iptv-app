using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Nova.Core
{
    /// <summary>Error with a message that can be shown to the user as it is.</summary>
    public class NovaException : Exception
    {
        public NovaException(string message) : base(message) { }
        public NovaException(string message, Exception inner) : base(message, inner) { }
    }

    /// <summary>
    /// Client for the Xtream Codes "player_api.php" interface (port of the Android XtreamApi).
    /// Xtream servers are inconsistent (numbers sent as strings, nulls, arrays vs objects),
    /// so every field is read leniently. Long lists are read item by item to keep memory low
    /// on old 32-bit PCs.
    /// </summary>
    public class XtreamApi
    {
        private readonly HttpClient _http;
        private readonly Func<string> _userAgent;

        static XtreamApi()
        {
            // Windows 7 / .NET 4.8: make sure modern TLS is allowed for https servers.
            try
            {
                ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12 | SecurityProtocolType.Tls11;
            }
            catch (NotSupportedException) { }
            ServicePointManager.DefaultConnectionLimit = Math.Max(ServicePointManager.DefaultConnectionLimit, 16);
        }

        public XtreamApi(Func<string> userAgent, HttpMessageHandler handler = null)
        {
            _userAgent = userAgent;
            var h = handler ?? new HttpClientHandler
            {
                AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
                AllowAutoRedirect = true,
            };
            _http = new HttpClient(h) { Timeout = TimeSpan.FromSeconds(60) };
        }

        // ------------------------------------------------------------------ transport

        private string ApiUrl(Account a, IDictionary<string, string> p)
        {
            var sb = new StringBuilder(NormalizeServer(a.Server));
            sb.Append("/player_api.php?username=").Append(Uri.EscapeDataString(a.Username ?? ""));
            sb.Append("&password=").Append(Uri.EscapeDataString(a.Password ?? ""));
            if (p != null)
                foreach (var kv in p)
                    sb.Append('&').Append(kv.Key).Append('=').Append(Uri.EscapeDataString(kv.Value));
            return sb.ToString();
        }

        /// <summary>Opens the response of one API call and hands the JSON reader to [read].</summary>
        private async Task<T> Call<T>(Account a, IDictionary<string, string> p, Func<JsonTextReader, T> read, CancellationToken ct)
        {
            Uri uri;
            if (!Uri.TryCreate(ApiUrl(a, p), UriKind.Absolute, out uri))
                throw new NovaException("Invalid server address");
            var req = new HttpRequestMessage(HttpMethod.Get, uri);
            req.Headers.TryAddWithoutValidation("User-Agent", _userAgent());
            HttpResponseMessage resp;
            try
            {
                resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
            }
            catch (TaskCanceledException e)
            {
                if (ct.IsCancellationRequested) throw;
                throw new NovaException("The server did not answer in time", e);
            }
            catch (HttpRequestException e)
            {
                throw new NovaException("Can't reach the server: " + Innermost(e).Message, e);
            }
            using (resp)
            {
                if (!resp.IsSuccessStatusCode)
                    throw new NovaException("Server returned HTTP " + (int)resp.StatusCode);
                using (var stream = await resp.Content.ReadAsStreamAsync().ConfigureAwait(false))
                using (var sr = new StreamReader(stream, Encoding.UTF8))
                using (var jr = new JsonTextReader(sr) { DateParseHandling = DateParseHandling.None, FloatParseHandling = FloatParseHandling.Decimal })
                {
                    try
                    {
                        return read(jr);
                    }
                    catch (JsonException e)
                    {
                        throw new NovaException("The server sent an unexpected response", e);
                    }
                    catch (IOException e)
                    {
                        throw new NovaException("The connection dropped while loading", e);
                    }
                }
            }
        }

        private static Exception Innermost(Exception e)
        {
            while (e.InnerException != null) e = e.InnerException;
            return e;
        }

        /// <summary>Whole response as one token (small answers: login, info pages, guide).</summary>
        private Task<JToken> CallToken(Account a, IDictionary<string, string> p, CancellationToken ct)
        {
            return Call(a, p, jr => jr.Read() ? JToken.ReadFrom(jr) : (JToken)JValue.CreateNull(), ct);
        }

        /// <summary>A long list, read one object at a time. Accepts [ {...} ] or { "x": {...} }.</summary>
        private Task<List<T>> CallList<T>(Account a, IDictionary<string, string> p, Func<JObject, T> map, CancellationToken ct) where T : class
        {
            return Call(a, p, jr => ReadObjects(jr, map, ct), ct);
        }

        internal static List<T> ReadObjects<T>(JsonReader jr, Func<JObject, T> map, CancellationToken ct) where T : class
        {
            var list = new List<T>();
            if (!jr.Read()) return list;
            if (jr.TokenType != JsonToken.StartArray && jr.TokenType != JsonToken.StartObject)
            {
                return list; // null, false, "" … = nothing
            }
            var endToken = jr.TokenType == JsonToken.StartArray ? JsonToken.EndArray : JsonToken.EndObject;
            while (jr.Read() && jr.TokenType != endToken)
            {
                ct.ThrowIfCancellationRequested();
                if (jr.TokenType == JsonToken.PropertyName) continue;
                if (jr.TokenType == JsonToken.StartObject)
                {
                    var o = JObject.Load(jr);
                    var item = map(o);
                    if (item != null) list.Add(item);
                }
                else if (jr.TokenType == JsonToken.StartArray)
                {
                    JArray.Load(jr); // unexpected nesting: skip
                }
            }
            return list;
        }

        // ------------------------------------------------------------------ API

        public async Task<UserInfo> Authenticate(Account a, CancellationToken ct = default(CancellationToken))
        {
            var root = await CallToken(a, null, ct).ConfigureAwait(false) as JObject;
            if (root == null) throw new NovaException("The server did not answer like an Xtream server");
            var info = root["user_info"] as JObject;
            if (info == null) throw new NovaException("Login failed");
            if (Str(info, "auth") == "0") throw new NovaException("Wrong username or password");
            var exp = ToLong(Str(info, "exp_date"));
            return new UserInfo
            {
                Status = Str(info, "status") ?? "Unknown",
                ExpDate = exp > 0 ? exp : (long?)null,
                MaxConnections = Str(info, "max_connections") ?? "-",
                ActiveConnections = Str(info, "active_cons") ?? "-",
                IsTrial = Str(info, "is_trial") == "1",
            };
        }

        public Task<List<Category>> Categories(Account a, ContentType type, CancellationToken ct = default(CancellationToken))
        {
            string action = type == ContentType.Live ? "get_live_categories"
                : type == ContentType.Movie ? "get_vod_categories" : "get_series_categories";
            return CallList(a, P("action", action), o =>
            {
                var id = Str(o, "category_id");
                if (id == null) return null;
                return new Category { AccountId = a.Id, Id = id, Name = Str(o, "category_name") ?? "Unnamed" };
            }, ct);
        }

        public Task<List<LiveChannel>> LiveStreams(Account a, string categoryId, CancellationToken ct = default(CancellationToken))
        {
            var p = P("action", "get_live_streams");
            if (categoryId != null) p["category_id"] = categoryId;
            return CallList(a, p, o => ParseChannel(a, o), ct);
        }

        internal static LiveChannel ParseChannel(Account a, JObject o)
        {
            var id = Str(o, "stream_id");
            if (id == null) return null;
            return new LiveChannel
            {
                AccountId = a.Id,
                StreamId = id,
                Num = (int)ToDouble(Str(o, "num")),
                Name = Str(o, "name") ?? "Channel " + id,
                Icon = Str(o, "stream_icon"),
                CategoryId = Str(o, "category_id") ?? "",
                HasArchive = Str(o, "tv_archive") == "1",
                Added = (long)ToDouble(Str(o, "added")),
            };
        }

        public Task<List<Movie>> Movies(Account a, string categoryId, CancellationToken ct = default(CancellationToken))
        {
            var p = P("action", "get_vod_streams");
            if (categoryId != null) p["category_id"] = categoryId;
            return CallList(a, p, o => ParseMovie(a, o), ct);
        }

        internal static Movie ParseMovie(Account a, JObject o)
        {
            var id = Str(o, "stream_id");
            if (id == null) return null;
            return new Movie
            {
                AccountId = a.Id,
                StreamId = id,
                Name = Str(o, "name") ?? "Movie " + id,
                Icon = Str(o, "stream_icon"),
                CategoryId = Str(o, "category_id") ?? "",
                Rating = RatingOf(o),
                Extension = Str(o, "container_extension") ?? "mp4",
                Added = (long)ToDouble(Str(o, "added")),
                Year = YearOf(o, Str(o, "name")),
            };
        }

        public Task<List<Series>> SeriesList(Account a, string categoryId, CancellationToken ct = default(CancellationToken))
        {
            var p = P("action", "get_series");
            if (categoryId != null) p["category_id"] = categoryId;
            return CallList(a, p, o => ParseSeries(a, o), ct);
        }

        internal static Series ParseSeries(Account a, JObject o)
        {
            var id = Str(o, "series_id");
            if (id == null) return null;
            return new Series
            {
                AccountId = a.Id,
                SeriesId = id,
                Name = Str(o, "name") ?? "Series " + id,
                Cover = Str(o, "cover"),
                CategoryId = Str(o, "category_id") ?? "",
                Plot = Str(o, "plot"),
                Rating = RatingOf(o),
                Added = (long)ToDouble(Str(o, "last_modified") ?? Str(o, "added")),
                Year = YearOf(o, Str(o, "name")),
            };
        }

        public async Task<SeriesInfo> SeriesInfo(Account a, Series series, CancellationToken ct = default(CancellationToken))
        {
            var root = await CallToken(a, P("action", "get_series_info", "series_id", series.SeriesId), ct).ConfigureAwait(false) as JObject;
            if (root == null) throw new NovaException("No information for this series");
            return ParseSeriesInfo(series, root);
        }

        internal static SeriesInfo ParseSeriesInfo(Series series, JObject root)
        {
            var info = root["info"] as JObject;
            var episodes = new SortedDictionary<int, List<Episode>>();

            Action<int?, JToken> add = (seasonKey, el) =>
            {
                var o = el as JObject;
                if (o == null) return;
                var id = Str(o, "id");
                if (id == null) return;
                int season;
                if (!int.TryParse(Str(o, "season"), out season)) season = seasonKey ?? 1;
                var epInfo = o["info"] as JObject;
                int num;
                int.TryParse(Str(o, "episode_num"), out num);
                List<Episode> list;
                if (!episodes.TryGetValue(season, out list)) { list = new List<Episode>(); episodes[season] = list; }
                list.Add(new Episode
                {
                    Id = id,
                    Title = Str(o, "title") ?? "Episode",
                    Season = season,
                    EpisodeNum = num,
                    Extension = Str(o, "container_extension") ?? "mp4",
                    Plot = epInfo == null ? null : Str(epInfo, "plot"),
                    Image = epInfo == null ? null : Str(epInfo, "movie_image"),
                });
            };

            var e = root["episodes"];
            if (e is JObject)
            {
                foreach (var prop in ((JObject)e).Properties())
                {
                    int k;
                    int? key = int.TryParse(prop.Name, out k) ? k : (int?)null;
                    var arr = prop.Value as JArray;
                    if (arr != null) foreach (var x in arr) add(key, x);
                    else add(key, prop.Value);
                }
            }
            else if (e is JArray)
            {
                foreach (var seasonEl in (JArray)e)
                {
                    var arr = seasonEl as JArray;
                    if (arr != null) foreach (var x in arr) add(null, x);
                    else add(null, seasonEl);
                }
            }

            foreach (var key in episodes.Keys.ToList())
                episodes[key] = episodes[key].OrderBy(x => x.EpisodeNum).ToList();

            if (series.Cover == null && info != null) series.Cover = Str(info, "cover");
            return new SeriesInfo
            {
                Series = series,
                Plot = (info == null ? null : Str(info, "plot")) ?? series.Plot,
                Genre = info == null ? null : Str(info, "genre"),
                Cast = info == null ? null : Str(info, "cast"),
                Episodes = episodes,
                Details = info != null ? DetailsOf(info) : new MediaDetails { Plot = series.Plot },
            };
        }

        /// <summary>Full information of one film (director, cast, plot, backdrop, trailer…).</summary>
        public async Task<MediaDetails> MovieInfo(Account a, Movie movie, CancellationToken ct = default(CancellationToken))
        {
            var root = await CallToken(a, P("action", "get_vod_info", "vod_id", movie.StreamId), ct).ConfigureAwait(false) as JObject;
            var info = root == null ? null : root["info"] as JObject;
            return info == null ? new MediaDetails() : DetailsOf(info);
        }

        public async Task<List<EpgEntry>> ShortEpg(Account a, string streamId, int limit = 8, CancellationToken ct = default(CancellationToken))
        {
            var root = await CallToken(a, P("action", "get_short_epg", "stream_id", streamId, "limit", limit.ToString(CultureInfo.InvariantCulture)), ct).ConfigureAwait(false) as JObject;
            return root == null ? new List<EpgEntry>() : ParseEpg(root);
        }

        internal static List<EpgEntry> ParseEpg(JObject root)
        {
            var list = new List<EpgEntry>();
            foreach (var el in Items(root["epg_listings"]))
            {
                var o = el as JObject;
                if (o == null) continue;
                var start = ToLong(Str(o, "start_timestamp"));
                if (start <= 0) continue;
                var end = ToLong(Str(o, "stop_timestamp"));
                if (end <= 0) end = start;
                list.Add(new EpgEntry
                {
                    Title = DecodeB64(Str(o, "title")),
                    Description = DecodeB64(Str(o, "description")),
                    Start = start * 1000,
                    End = end * 1000,
                });
            }
            return list.OrderBy(x => x.Start).ToList();
        }

        // ------------------------------------------------------------------ stream addresses

        public static string LiveUrl(Account a, string streamId, string format)
        {
            return NormalizeServer(a.Server) + "/live/" + Uri.EscapeDataString(a.Username) + "/" + Uri.EscapeDataString(a.Password) + "/" + streamId + "." + format;
        }

        public static string MovieUrl(Account a, string streamId, string ext)
        {
            return NormalizeServer(a.Server) + "/movie/" + Uri.EscapeDataString(a.Username) + "/" + Uri.EscapeDataString(a.Password) + "/" + streamId + "." + ext;
        }

        public static string EpisodeUrl(Account a, string episodeId, string ext)
        {
            return NormalizeServer(a.Server) + "/series/" + Uri.EscapeDataString(a.Username) + "/" + Uri.EscapeDataString(a.Password) + "/" + episodeId + "." + ext;
        }

        // ------------------------------------------------------------------ helpers

        /// <summary>Turns "myhost.com:8080/", "http://myhost:80/player_api.php", etc. into "http://myhost:80".</summary>
        public static string NormalizeServer(string raw)
        {
            var s = (raw ?? "").Trim();
            if (!s.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !s.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                s = "http://" + s;
            var q = s.IndexOf('?');
            if (q >= 0) s = s.Substring(0, q);
            foreach (var suffix in new[] { "/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php" })
            {
                if (s.EndsWith(suffix, StringComparison.OrdinalIgnoreCase)) s = s.Substring(0, s.Length - suffix.Length);
            }
            return s.TrimEnd('/');
        }

        /// <summary>Reads server, username and password out of a pasted M3U / Xtream link, if present.</summary>
        public static bool TryParseLink(string raw, out string server, out string user, out string pass)
        {
            server = user = pass = null;
            Uri u;
            var text = (raw ?? "").Trim();
            if (!text.StartsWith("http", StringComparison.OrdinalIgnoreCase)) text = "http://" + text;
            if (!Uri.TryCreate(text, UriKind.Absolute, out u)) return false;
            var q = ParseQuery(u.Query);
            if (!q.TryGetValue("username", out user) || !q.TryGetValue("password", out pass)) return false;
            server = u.Scheme + "://" + u.Host + (u.IsDefaultPort ? "" : ":" + u.Port);
            return true;
        }

        private static Dictionary<string, string> ParseQuery(string query)
        {
            var d = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            foreach (var part in (query ?? "").TrimStart('?').Split('&'))
            {
                if (part.Length == 0) continue;
                var i = part.IndexOf('=');
                var k = Uri.UnescapeDataString((i < 0 ? part : part.Substring(0, i)).Replace('+', ' '));
                var v = i < 0 ? "" : Uri.UnescapeDataString(part.Substring(i + 1).Replace('+', ' '));
                d[k] = v;
            }
            return d;
        }

        private static Dictionary<string, string> P(params string[] kv)
        {
            var d = new Dictionary<string, string>();
            for (int i = 0; i + 1 < kv.Length; i += 2) d[kv[i]] = kv[i + 1];
            return d;
        }

        private static IEnumerable<JToken> Items(JToken t)
        {
            if (t is JArray) return (JArray)t;
            if (t is JObject) return ((JObject)t).Properties().Select(x => x.Value);
            return Enumerable.Empty<JToken>();
        }

        /// <summary>Text value of a field: numbers as plain text, null / "" / "null" as null.</summary>
        public static string Str(JObject o, string key)
        {
            var t = o[key] as JValue;
            if (t == null || t.Type == JTokenType.Null || t.Value == null) return null;
            string s;
            if (t.Type == JTokenType.Boolean) s = (bool)t.Value ? "true" : "false";
            else if (t.Type == JTokenType.Float || t.Type == JTokenType.Integer) s = Convert.ToString(t.Value, CultureInfo.InvariantCulture);
            else s = t.Value.ToString();
            s = s.Trim();
            return s.Length == 0 || s == "null" ? null : s;
        }

        private static double ToDouble(string s)
        {
            double d;
            return s != null && double.TryParse(s.Replace(',', '.'), NumberStyles.Float, CultureInfo.InvariantCulture, out d) ? d : 0;
        }

        private static long ToLong(string s)
        {
            return (long)ToDouble(s);
        }

        private static string DecodeB64(string s)
        {
            if (string.IsNullOrWhiteSpace(s)) return "";
            try
            {
                return Encoding.UTF8.GetString(Convert.FromBase64String(s.Trim())).Trim();
            }
            catch (FormatException)
            {
                return s;
            }
        }

        /// <summary>Reads the details block of get_vod_info / get_series_info (providers use different names).</summary>
        internal static MediaDetails DetailsOf(JObject o)
        {
            string duration = null;
            if (Str(o, "duration") != null) duration = FormatDuration(Str(o, "duration"));
            if (duration == null && ToLong(Str(o, "duration_secs")) > 0) duration = FormatMinutes(ToLong(Str(o, "duration_secs")) / 60);
            if (duration == null && ToLong(Str(o, "episode_run_time")) > 0) duration = FormatMinutes(ToLong(Str(o, "episode_run_time"))) + " / episode";
            var rating = RatingOf(o);
            return new MediaDetails
            {
                Plot = Str(o, "plot") ?? Str(o, "description"),
                Director = Str(o, "director"),
                Cast = Str(o, "cast") ?? Str(o, "actors"),
                Genre = Str(o, "genre"),
                ReleaseDate = Str(o, "releasedate") ?? Str(o, "releaseDate") ?? Str(o, "release_date") ?? Str(o, "year"),
                Duration = duration,
                Rating = rating == null ? (double?)null : ToDouble(rating),
                Backdrop = FirstStr(o["backdrop_path"]),
                Poster = Str(o, "movie_image") ?? Str(o, "cover_big") ?? Str(o, "cover"),
                Trailer = Str(o, "youtube_trailer") ?? Str(o, "trailer"),
                Country = Str(o, "country"),
            };
        }

        /// <summary>Rating out of 10: "rating", or "rating_5based" doubled. Null when missing or 0.</summary>
        public static string RatingOf(JObject o)
        {
            var r = ToDouble(Str(o, "rating"));
            var r5 = ToDouble(Str(o, "rating_5based"));
            double value;
            if (r > 0) value = r > 10 ? r / 10 : r;
            else if (r5 > 0) value = r5 * 2;
            else return null;
            var s = value.ToString("0.0", CultureInfo.InvariantCulture);
            return s.EndsWith(".0") ? s.Substring(0, s.Length - 2) : s;
        }

        private static readonly Regex YearInName = new Regex(@"(?:\(|\[|\s|-|\|)((?:19|20)\d{2})(?:\)|\]|\s|$)", RegexOptions.Compiled);
        private static readonly Regex AnyYear = new Regex(@"(19|20)\d{2}", RegexOptions.Compiled);

        /// <summary>Year of production: "year" / release date fields, otherwise "(2026)" in the title.</summary>
        public static int? YearOf(JObject o, string name)
        {
            foreach (var k in new[] { "year", "releaseDate", "release_date", "releasedate" })
            {
                var v = Str(o, k);
                if (v == null) continue;
                var m = AnyYear.Match(v);
                if (m.Success) return int.Parse(m.Value, CultureInfo.InvariantCulture);
            }
            return YearFromName(name);
        }

        public static int? YearFromName(string name)
        {
            if (string.IsNullOrEmpty(name)) return null;
            var ms = YearInName.Matches(name);
            if (ms.Count == 0) return null;
            return int.Parse(ms[ms.Count - 1].Groups[1].Value, CultureInfo.InvariantCulture);
        }

        private static string FirstStr(JToken e)
        {
            var v = e as JValue;
            if (v != null)
            {
                var s = v.Value == null ? null : v.Value.ToString();
                return string.IsNullOrWhiteSpace(s) || s == "null" ? null : s;
            }
            var arr = e as JArray;
            if (arr != null)
            {
                foreach (var x in arr)
                {
                    var xv = x as JValue;
                    var s = xv == null || xv.Value == null ? null : xv.Value.ToString();
                    if (!string.IsNullOrWhiteSpace(s)) return s;
                }
            }
            return null;
        }

        /// <summary>"01:24:00" -> "1h 24m"; "84 min" -> "1h 24m".</summary>
        public static string FormatDuration(string raw)
        {
            if (raw == null) return null;
            var t = raw.Trim();
            if (t.Contains(":"))
            {
                var parts = t.Split(':').Select(x => { int n; return int.TryParse(x.Trim(), out n) ? n : -1; }).Where(n => n >= 0).ToList();
                if (parts.Count >= 2)
                {
                    long minutes = parts[0] * 60 + parts[1];
                    return minutes > 0 ? FormatMinutes(minutes) : null;
                }
            }
            var m = Regex.Match(t, @"\d+");
            if (!m.Success) return t.Length == 0 ? null : t;
            long mins = long.Parse(m.Value, CultureInfo.InvariantCulture);
            return mins > 0 ? FormatMinutes(mins) : null;
        }

        public static string FormatMinutes(long m)
        {
            return m >= 60 ? (m / 60) + "h " + (m % 60).ToString("00") + "m" : m + "m";
        }

        /// <summary>The programme on air now and the one after it.</summary>
        public static void NowNext(List<EpgEntry> list, long nowMs, out EpgEntry current, out EpgEntry next)
        {
            current = list == null ? null : list.FirstOrDefault(x => nowMs >= x.Start && nowMs < x.End);
            var after = current != null ? current.End : nowMs;
            next = list == null ? null : list.FirstOrDefault(x => x.Start >= after);
        }

        public static long NowMs()
        {
            return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }
    }
}
