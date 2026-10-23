using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Nova.Core
{
    /// <summary>
    /// Parental age filter with the USA ratings: OFF · G · PG · PG-13 · R · NC-17 (the highest rating allowed), and a
    /// separate word filter. Same rules as the box: films use the MPA rating, series the US TV rating
    /// (TV-Y/Y7/G = G, TV-PG = PG, TV-14 = PG-13, TV-MA = R). Ratings come from the TMDB film database (by the film's
    /// TMDB number, or by its cleaned name + year) or from simple genre / name rules. Kept in Downloads\NovaIPTV\Filter.
    /// </summary>
    public sealed class AgeFilter
    {
        public static readonly string[] Levels = { "OFF", "G", "PG", "PG-13", "R", "NC-17" };
        public static readonly string[] LevelNotes =
        {
            "No age filter",
            "General audiences — all ages",
            "Parental guidance suggested",
            "Not for under 13",
            "Restricted — under 17 with a parent",
            "Adults only (only 18+ / X is hidden)",
        };
        public const int Adult = 6;

        /// <summary>The built-in film database key (set by the build), used when you don't type your own.</summary>
        public static string BuiltInKey = ReadBuiltInKey();

        private static string ReadBuiltInKey()
        {
            try
            {
                foreach (var a in typeof(AgeFilter).Assembly.GetCustomAttributes(typeof(System.Reflection.AssemblyMetadataAttribute), false))
                {
                    var m = (System.Reflection.AssemblyMetadataAttribute)a;
                    if (m.Key == "TmdbKey" && !string.IsNullOrWhiteSpace(m.Value)) return m.Value.Trim();
                }
            }
            catch { }
            return "";
        }

        // ------------------------------------------------------------------ words

        public sealed class WordGroup
        {
            public string Id, Title;
            public bool DefaultOn;
            public string[] Defaults;
        }

        public static readonly WordGroup[] WordGroups =
        {
            new WordGroup { Id = "romance", Title = "💋 Romance & kissing", DefaultOn = true, Defaults = new[] {
                "kiss", "kisses", "kissing", "love", "lover", "lovers", "romance", "romantic", "passion", "desire", "seduction", "seduce",
                "temptation", "flirt", "crush", "valentine", "honeymoon", "affair", "mistress", "unfaithful", "adultery",
                "amour", "amours", "amoureux", "amoureuse", "amant", "amante", "baiser", "bisou", "désir", "séduction", "séducteur",
                "tentation", "passionnément", "saint-valentin", "lune de miel", "liaison", "maîtresse", "infidèle", "adultère", "coup de foudre" } },
            new WordGroup { Id = "adult", Title = "🔞 Sexual & adult", DefaultOn = true, Defaults = new[] {
                "sex", "sexy", "sexual", "nude", "naked", "erotic", "lust", "sensual", "seductive", "stripper", "striptease", "escort",
                "call girl", "gigolo", "prostitute", "lingerie", "fetish", "bdsm", "kamasutra", "fifty shades", "playboy", "xxx", "porn",
                "18+", "uncensored", "unrated", "sexe", "sexuel", "sexuelle", "nu", "nue", "nues", "érotique", "sensuel", "sensuelle",
                "désirs", "prostituée", "fétiche", "cinquante nuances", "interdit aux mineurs", "non censuré", "-18" } },
            new WordGroup { Id = "horror", Title = "🩸 Horror & violence", DefaultOn = false, Defaults = new[] {
                "horror", "blood", "bloody", "killer", "kill", "massacre", "slasher", "gore", "zombie", "demon", "devil", "exorcist",
                "possession", "haunted", "evil", "cannibal", "chainsaw", "psycho", "nightmare", "horreur", "sang", "sanglant", "tueur",
                "tueuse", "démon", "diable", "exorciste", "hanté", "hantée", "maléfique", "cannibale", "cauchemar", "terreur", "épouvante" } },
            new WordGroup { Id = "drugs", Title = "💊 Drugs & crime", DefaultOn = false, Defaults = new[] {
                "drug", "drugs", "cocaine", "heroin", "narcos", "cartel", "gangster", "mafia", "drogue", "cocaïne", "héroïne", "gang", "braquage" } },
            new WordGroup { Id = "own", Title = "✏️ My own words", DefaultOn = true, Defaults = new string[0] },
        };

        public static List<string> WordsOf(FilterSettings f, WordGroup g)
        {
            List<string> w;
            return f.Words != null && f.Words.TryGetValue(g.Id, out w) && w != null ? w : g.Defaults.ToList();
        }

        public static bool GroupOn(FilterSettings f, WordGroup g)
        {
            return f.WordGroupsOn != null ? f.WordGroupsOn.Contains(g.Id) : g.DefaultOn;
        }

        private static readonly Regex Year = new Regex(@"\b(19[2-9]\d|20[0-4]\d)\b", RegexOptions.Compiled);
        private static readonly Regex Brackets = new Regex(@"\[[^\]]*\]", RegexOptions.Compiled);
        private static readonly Regex Parens = new Regex(@"\(([^)]*)\)", RegexOptions.Compiled);
        private static readonly Regex Spaces = new Regex(@"\s+", RegexOptions.Compiled);

        /// <summary>Lower case, no accents, "_" and "." as spaces: "FR_Amoür.Fou" → "fr amour fou".</summary>
        public static string Normalize(string text)
        {
            var t = (text ?? "").ToLowerInvariant();
            if (t.Any(c => c > 127))
            {
                var d = t.Normalize(NormalizationForm.FormD);
                var sb = new StringBuilder(d.Length);
                foreach (var c in d) if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) sb.Append(c);
                t = sb.ToString().Normalize(NormalizationForm.FormC);
            }
            return t.Replace('_', ' ').Replace('.', ' ').Replace('’', '\'');
        }

        private static readonly ConcurrentDictionary<string, Regex> PatternCache = new ConcurrentDictionary<string, Regex>();

        /// <summary>One word list → one pattern (whole words, with plurals: love → loves). Null when empty.</summary>
        public static Regex WordsPattern(IEnumerable<string> words)
        {
            var parts = words.Select(w => Normalize(w).Trim()).Where(w => w.Length > 0).Distinct()
                .Select(w => Regex.Escape(w).Replace(@"\ ", @"\s+")).ToList();
            if (parts.Count == 0) return null;
            var pattern = @"(?<![\p{L}\p{N}])(" + string.Join("|", parts) + @")(s|es|x)?(?![\p{L}\p{N}])";
            if (PatternCache.Count > 50) PatternCache.Clear();
            return PatternCache.GetOrAdd(pattern, p => new Regex(p, RegexOptions.Compiled | RegexOptions.CultureInvariant));
        }

        // ------------------------------------------------------------------ settings

        private volatile FilterSettings _f = new FilterSettings();
        private volatile List<Regex> _words = new List<Regex>();
        private HashSet<string> _allowed = new HashSet<string>();
        private HashSet<string> _allowedGroups = new HashSet<string>();

        public int Level { get { return _f.Level; } }
        public string Source { get { return _f.Source; } }
        public bool HideUnknown { get { return _f.HideUnknown; } }
        public string LiveMode { get { return _f.LiveMode; } }
        public bool WordsActive { get { return _words.Count > 0; } }
        /// <summary>One of the two filters (age, words) is on.</summary>
        public bool Active { get { return _f.Level != 0 || _words.Count > 0; } }
        public string EffectiveKey { get { return string.IsNullOrWhiteSpace(_f.TmdbKey) ? BuiltInKey : _f.TmdbKey.Trim(); } }

        /// <summary>The lists must be drawn again (settings, exceptions or ratings changed).</summary>
        public event Action Changed;

        private readonly ConcurrentDictionary<string, string> _reasonCache = new ConcurrentDictionary<string, string>();
        private readonly ConcurrentDictionary<string, int> _ruleCache = new ConcurrentDictionary<string, int>();
        private readonly ConcurrentDictionary<string, string> _wordCache = new ConcurrentDictionary<string, string>();

        public void Configure(FilterSettings f)
        {
            f = (f ?? new FilterSettings()).Fix();
            var words = f.WordsOn
                ? WordGroups.Where(g => GroupOn(f, g)).Select(g => WordsPattern(WordsOf(f, g))).Where(r => r != null).ToList()
                : new List<Regex>();
            _f = f;
            _allowed = new HashSet<string>(f.Allowed);
            _allowedGroups = new HashSet<string>(f.AllowedGroups);
            _words = words;
            _wordCache.Clear();
            Bump();
        }

        private void Bump()
        {
            _reasonCache.Clear();
            var h = Changed;
            if (h != null) try { h(); } catch { }
        }

        public bool IsAllowedAnyway(string code, string key) { return _allowed.Contains(code + "|" + key); }

        // ------------------------------------------------------------------ words & rules

        /// <summary>The first word of the switched-on groups found in the name, or null.</summary>
        public string WordIn(string name)
        {
            var words = _words;
            if (words.Count == 0 || name == null) return null;
            string cached;
            if (_wordCache.TryGetValue(name, out cached)) return cached.Length == 0 ? null : cached;
            var n = Normalize(name);
            string found = null;
            foreach (var re in words)
            {
                var m = re.Match(n);
                if (m.Success) { found = m.Groups[1].Value; break; }
            }
            if (_wordCache.Count < 200000) _wordCache[name] = found ?? "";
            return found;
        }

        private static readonly Regex AdultWords = new Regex(@"(?i)(\bxxx\b|\badult(e|es|s)?\b|\bporn\w*|\berotic\w*|érotique|\berotique\b|\bsexy\b|\b18\s*\+|\+\s*18\b|\bhot\s*club\b|\bplayboy\b|\bhustler\b|\bbrazzers\b|\bredlight\b|\bdorcel\b)", RegexOptions.Compiled);
        private static readonly Regex HorrorWords = new Regex(@"(?i)(horror|horreur|épouvante|epouvante|\bgore\b|slasher|terreur|\bzombie)", RegexOptions.Compiled);
        private static readonly Regex ToughWords = new Regex(@"(?i)(thriller|crime|criminal|policier|guerre|\bwar\b|\bmafia\b)", RegexOptions.Compiled);
        private static readonly Regex KidsWords = new Regex(@"(?i)(\bkids?\b|enfant|jeunesse|junior|cartoon|dessins?\s*anim|\bdisney|pixar|famil|\bbaby\b|\btoon)", RegexOptions.Compiled);

        /// <summary>Rating guessed from the name and the group: 0 when nothing is clear.</summary>
        public int RuleRating(string name, string group)
        {
            var text = (name ?? "") + " " + (group ?? "");
            int r;
            if (_ruleCache.TryGetValue(text, out r)) return r;
            if (AdultWords.IsMatch(text)) r = Adult;
            else if (HorrorWords.IsMatch(text)) r = 4;
            else if (group != null && KidsWords.IsMatch(group)) r = 1;
            else if (ToughWords.IsMatch(text)) r = 3;
            else r = 0;
            if (_ruleCache.Count < 200000) _ruleCache[text] = r;
            return r;
        }

        // ------------------------------------------------------------------ decision

        private readonly ConcurrentDictionary<string, int> _items = new ConcurrentDictionary<string, int>();
        private readonly ConcurrentDictionary<string, int> _answers = new ConcurrentDictionary<string, int>();
        private long _checkedAt;

        public int RatingOf(string code, string key, string name, string group)
        {
            var rule = RuleRating(name, group);
            if (rule == Adult) return Adult;
            if (_f.Source == "rules") return rule;
            int r;
            return _items.TryGetValue((code == "series" ? "series|" : "movie|") + key, out r) && r > 0 ? r : rule;
        }

        public static string RatingLabel(int r) { return r >= Adult ? "18+" : r >= 0 && r < Levels.Length ? Levels[r] : "?"; }

        /// <summary>Why this film / series is hidden ("R", "no age found", "word: kiss"…), or null when shown. Exceptions not counted.</summary>
        public string Reason(string code, string key, string name, string group)
        {
            var f = _f;
            var lv = f.Level;
            if (lv == 0 && _words.Count == 0) return null;
            if (code != "movie" && code != "series") return null;
            var ck = code + "|" + key + "|" + (group ?? "");
            string cached;
            if (_reasonCache.TryGetValue(ck, out cached)) return cached.Length == 0 ? null : cached;
            string reason;
            if (lv == 0)
            {
                var w0 = WordIn(name);
                reason = w0 != null ? "word: " + w0 : null;
            }
            else
            {
                var rule = RuleRating(name, group);
                var r = RatingOf(code, key, name, group);
                int saved;
                var hasSaved = _items.TryGetValue((code == "series" ? "series|" : "movie|") + key, out saved) && saved != 0;
                var fromRule = r == rule && (f.Source == "rules" || !hasSaved);
                if (r > lv) reason = RatingLabel(r) + (fromRule ? " · name rule" : "");
                else
                {
                    var w = WordIn(name);
                    if (w != null) reason = "word: " + w;
                    else if (r == 0 && f.HideUnknown) reason = "no age found";
                    else reason = null;
                }
            }
            if (_reasonCache.Count < 300000) _reasonCache[ck] = reason ?? "";
            return reason;
        }

        /// <summary>True when this film / series is hidden (above the level, a word, or unknown with "hide them"), unless allowed anyway.</summary>
        public bool Blocked(string code, string key, string name, string group)
        {
            if (_f.Level == 0 && _words.Count == 0) return false;
            if (_allowed.Contains(code + "|" + key)) return false;
            return Reason(code, key, name, group) != null;
        }

        public bool Blocked(Movie m, string group) { return Blocked("movie", m.Key, m.Name, group); }
        public bool Blocked(Series s, string group) { return Blocked("series", s.Key, s.Name, group); }

        /// <summary>Live channels: hidden by name (adult always; horror below R), or only the groups you allow.</summary>
        public bool LiveBlocked(LiveChannel c, string groupName)
        {
            return _f.Level != 0 && !_allowed.Contains("live|" + c.Key) && LiveReason(c.Name, groupName, c.AccountId + "|" + c.CategoryId) != null;
        }

        public string LiveReason(string name, string group, string groupKey)
        {
            var f = _f;
            if (f.Level == 0) return null;
            if (f.LiveMode == "allow" && groupKey != null) return _allowedGroups.Contains(groupKey) ? null : "group not allowed";
            var text = (name ?? "") + " " + (group ?? "");
            if (AdultWords.IsMatch(text)) return "name rule: adult";
            if (f.Level < 4 && HorrorWords.IsMatch(text)) return "name rule: horror";
            return null;
        }

        /// <summary>Groups: an adult group is hidden; with "only groups I allow", the other live groups too.</summary>
        public bool GroupBlocked(string code, string groupKey, string groupName)
        {
            var f = _f;
            if (f.Level == 0) return false;
            if (code == "live" && f.LiveMode == "allow") return !_allowedGroups.Contains(groupKey);
            if (AdultWords.IsMatch(groupName ?? "")) return f.Level < Adult;
            if (code == "live" && f.Level < 4 && HorrorWords.IsMatch(groupName ?? "")) return true;
            return false;
        }

        // ------------------------------------------------------------------ names

        private static readonly Regex TagPrefix = new Regex(@"^(\s*([|\[(][^|\])]{1,14}[|\])]|[A-Z]{2,5}\s*[-:|_]|[A-Z]{2,4}\s+-)\s*)+", RegexOptions.Compiled);
        private static readonly Regex Junk = new Regex(@"(?i)\b(4k|uhd|fhd|hd|sd|hq|1080p?|720p?|2160p?|480p|hevc|x264|x265|h\.?264|h\.?265|hdr|hdr10|10bit|web|webrip|web-dl|bluray|brrip|bdrip|dvdrip|remux|imax|3d|vf|vff|vfq|vfi|vo|vost|vostfr|multi|truefrench|french|subfrench|stfr|dual|ar|en|fr|eng|fre)\b", RegexOptions.Compiled);

        /// <summary>"FR_Le Roi Lion_4K (2019) VF" → ("le roi lion", 2019).</summary>
        public static Tuple<string, int?> CleanName(string raw, int? knownYear)
        {
            var s = TagPrefix.Replace(raw ?? "", "");
            s = s.Replace('_', ' ').Replace('.', ' ');
            int? year = knownYear;
            if (year == null)
            {
                var all = Year.Matches(s);
                int y;
                if (all.Count > 0 && int.TryParse(all[all.Count - 1].Value, out y)) year = y;
            }
            s = Brackets.Replace(s, " ");
            s = Parens.Replace(s, " ");
            s = Year.Replace(s, " ");
            s = Junk.Replace(s, " ");
            s = Spaces.Replace(s, " ").Trim(' ', '-', ':', '|', ',');
            return Tuple.Create(s.ToLowerInvariant(), year);
        }

        // ------------------------------------------------------------------ TMDB

        private static int UsMovie(string cert)
        {
            switch ((cert ?? "").Trim().ToUpperInvariant())
            {
                case "G": return 1;
                case "PG": return 2;
                case "PG-13": return 3;
                case "R": return 4;
                case "NC-17": return 5;
                case "X": case "XXX": return Adult;
                default: return 0;
            }
        }

        private static int UsTv(string cert)
        {
            switch ((cert ?? "").Trim().ToUpperInvariant())
            {
                case "TV-Y": case "TV-Y7": case "TV-Y7-FV": case "TV-G": return 1;
                case "TV-PG": return 2;
                case "TV-14": return 3;
                case "TV-MA": return 4;
                default: return 0;
            }
        }

        private sealed class KeyRefused : Exception { public KeyRefused() : base("the TMDB key is not accepted") { } }

        private sealed class Tmdb
        {
            private readonly string _key;
            public Tmdb(string key) { _key = key; }

            public JObject Get(string path, Dictionary<string, string> query)
            {
                var bearer = _key.Length > 40;
                var q = new Dictionary<string, string>(query ?? new Dictionary<string, string>());
                if (!bearer) q["api_key"] = _key;
                var url = "https://api.themoviedb.org/3" + path + (q.Count == 0 ? "" : "?" + string.Join("&", q.Select(kv => kv.Key + "=" + Uri.EscapeDataString(kv.Value))));
                for (int attempt = 0; attempt < 3; attempt++)
                {
                    var req = (HttpWebRequest)WebRequest.Create(url);
                    req.Timeout = 15000;
                    req.ReadWriteTimeout = 15000;
                    req.Accept = "application/json";
                    req.AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate;
                    if (bearer) req.Headers["Authorization"] = "Bearer " + _key;
                    try
                    {
                        using (var resp = (HttpWebResponse)req.GetResponse())
                        using (var r = new StreamReader(resp.GetResponseStream(), Encoding.UTF8))
                            return JObject.Parse(r.ReadToEnd());
                    }
                    catch (WebException e)
                    {
                        var hr = e.Response as HttpWebResponse;
                        if (hr == null) throw;
                        var code = (int)hr.StatusCode;
                        hr.Dispose();
                        if (code == 429) { Thread.Sleep(1500 * (attempt + 1)); continue; }
                        if (code == 401) throw new KeyRefused();
                        if (code == 404) return null;
                        throw new IOException("TMDB HTTP " + code);
                    }
                }
                return null;
            }

            public int MovieRating(string id)
            {
                var o = Get("/movie/" + id + "/release_dates", null);
                if (o == null) return -1;
                var res = o["results"] as JArray;
                if (res == null) return 0;
                foreach (var c in res.OfType<JObject>())
                {
                    if ((string)c["iso_3166_1"] != "US") continue;
                    var best = 0;
                    var dates = c["release_dates"] as JArray;
                    if (dates != null)
                        foreach (var d in dates.OfType<JObject>()) best = Math.Max(best, UsMovie((string)d["certification"]));
                    return best;
                }
                return 0;
            }

            public int TvRating(string id)
            {
                var o = Get("/tv/" + id + "/content_ratings", null);
                if (o == null) return -1;
                var res = o["results"] as JArray;
                if (res == null) return 0;
                foreach (var c in res.OfType<JObject>())
                    if ((string)c["iso_3166_1"] == "US") return UsTv((string)c["rating"]);
                return 0;
            }

            /// <summary>Finds the title by name (French or original) and year: its TMDB number, "adult", or null.</summary>
            public string Search(bool tv, string name, int? year)
            {
                if (name == null || name.Length < 2) return null;
                Func<bool, string, string> pick = (withYear, lang) =>
                {
                    var q = new Dictionary<string, string> { { "query", name }, { "language", lang }, { "include_adult", "true" } };
                    if (withYear && year != null) q[tv ? "first_air_date_year" : "year"] = year.Value.ToString(CultureInfo.InvariantCulture);
                    var o = Get(tv ? "/search/tv" : "/search/movie", q);
                    var res = o == null ? null : o["results"] as JArray;
                    if (res == null || res.Count == 0) return null;
                    JObject chosen = null;
                    foreach (var r in res.OfType<JObject>().Take(8))
                    {
                        var date = (string)r[tv ? "first_air_date" : "release_date"] ?? "";
                        int y;
                        var hasY = date.Length >= 4 && int.TryParse(date.Substring(0, 4), out y);
                        if (year == null || !hasY || Math.Abs(int.Parse(date.Substring(0, 4)) - year.Value) <= 1) { chosen = r; break; }
                    }
                    if (chosen == null) return null;
                    if (chosen["adult"] != null && chosen["adult"].Type == JTokenType.Boolean && (bool)chosen["adult"]) return "adult";
                    var id = chosen["id"] != null ? (long)chosen["id"] : 0;
                    return id > 0 ? id.ToString(CultureInfo.InvariantCulture) : null;
                };
                return pick(true, "fr-FR") ?? pick(true, "en-US") ?? (year != null ? pick(false, "fr-FR") : null);
            }
        }

        // ------------------------------------------------------------------ files (Downloads\NovaIPTV\Filter)

        /// <summary>The Filter folder.</summary>
        public Func<string> Folder = () => null;

        private string RatingsFile { get { var f = Folder(); return f == null ? null : Path.Combine(f, "ratings.gz"); } }

        private bool _loaded;

        public void Load()
        {
            if (_loaded) return;
            _loaded = true;
            try
            {
                var path = RatingsFile;
                if (path == null || !File.Exists(path)) return;
                using (var gz = new GZipStream(File.OpenRead(path), CompressionMode.Decompress))
                using (var r = new StreamReader(gz, Encoding.UTF8))
                {
                    string line;
                    while ((line = r.ReadLine()) != null)
                    {
                        var p = line.Split('\t');
                        int v;
                        if (p[0] == "AGES1" && p.Length > 1) long.TryParse(p[1], out _checkedAt);
                        else if (p[0] == "I" && p.Length >= 3) _items[p[1]] = int.TryParse(p[2], out v) ? v : 0;
                        else if (p[0] == "A" && p.Length >= 3) _answers[p[1]] = int.TryParse(p[2], out v) ? v : 0;
                    }
                }
                Bump();
            }
            catch { }
        }

        private readonly object _saveLock = new object();

        private void Save()
        {
            lock (_saveLock)
            {
                try
                {
                    var path = RatingsFile;
                    if (path == null) return;
                    Directory.CreateDirectory(Path.GetDirectoryName(path));
                    var tmp = path + ".tmp";
                    using (var gz = new GZipStream(File.Create(tmp), CompressionMode.Compress))
                    using (var w = new StreamWriter(gz, new UTF8Encoding(false)))
                    {
                        w.Write("AGES1\t" + _checkedAt + "\n");
                        foreach (var kv in _answers) w.Write("A\t" + kv.Key.Replace('\t', ' ') + "\t" + kv.Value + "\n");
                        foreach (var kv in _items) w.Write("I\t" + kv.Key + "\t" + kv.Value + "\n");
                    }
                    if (File.Exists(path)) File.Delete(path);
                    File.Move(tmp, path);
                }
                catch { }
            }
        }

        private const string Seal = "NovaIPTV-filter-seal-7Q2v";

        private static string Hash(string text)
        {
            using (var sha = SHA256.Create())
                return string.Concat(sha.ComputeHash(Encoding.UTF8.GetBytes(text)).Select(b => b.ToString("x2")));
        }

        /// <summary>Saves the filter settings and the PIN (sealed, so a change by hand is noticed) in the Filter folder.</summary>
        public void SaveSettings(FilterSettings f, string pinHash, string pinSalt)
        {
            try
            {
                var folder = Folder();
                if (folder == null) return;
                Directory.CreateDirectory(folder);
                var body = JsonConvert.SerializeObject(new JObject
                {
                    ["filter"] = JObject.FromObject(f),
                    ["pin_hash"] = pinHash,
                    ["pin_salt"] = pinSalt,
                }, Formatting.Indented);
                File.WriteAllText(Path.Combine(folder, "filter-settings.txt"), body + "\nseal\t" + Hash(Seal + body) + "\n", new UTF8Encoding(false));
            }
            catch { }
        }

        /// <summary>New install: the filter (and PIN) saved earlier on this PC, or null when missing, damaged or changed by hand.</summary>
        public Tuple<FilterSettings, string, string> SavedSettings()
        {
            try
            {
                var folder = Folder();
                var path = folder == null ? null : Path.Combine(folder, "filter-settings.txt");
                if (path == null || !File.Exists(path)) return null;
                var text = File.ReadAllText(path, Encoding.UTF8);
                var i = text.LastIndexOf("\nseal\t", StringComparison.Ordinal);
                if (i < 0) return null;
                var body = text.Substring(0, i);
                if (text.Substring(i + 6).Trim() != Hash(Seal + body)) return null;
                var o = JObject.Parse(body);
                var f = ((JObject)o["filter"]).ToObject<FilterSettings>().Fix();
                f.Set = true;
                return Tuple.Create(f, (string)o["pin_hash"], (string)o["pin_salt"]);
            }
            catch { return null; }
        }

        // ------------------------------------------------------------------ the whole index

        /// <summary>Every film, series and channel of one account, with the group names.</summary>
        public sealed class AccountIndex
        {
            public Account Account;
            public List<Movie> Movies = new List<Movie>();
            public List<Series> Series = new List<Series>();
            public List<LiveChannel> Live = new List<LiveChannel>();
            /// <summary>"movie|acc|cat" / "series|…" / "live|…" → group name.</summary>
            public Dictionary<string, string> Groups = new Dictionary<string, string>();
        }

        public sealed class HiddenRow
        {
            public string Code, Key, Name, AccountId, Reason;
            public string ExKey { get { return Code + "|" + Key; } }
        }

        private static string G(AccountIndex a, string code, string cat)
        {
            string n;
            return a.Groups.TryGetValue(code + "|" + a.Account.Id + "|" + cat, out n) ? n : null;
        }

        /// <summary>Everything the filters hide (exceptions included, so they can be ticked again).</summary>
        public Dictionary<string, List<HiddenRow>> HiddenList(IList<AccountIndex> index)
        {
            var films = new List<HiddenRow>(); var series = new List<HiddenRow>(); var live = new List<HiddenRow>();
            if (Active)
            {
                foreach (var a in index)
                {
                    foreach (var m in a.Movies)
                    {
                        var r = Reason("movie", m.Key, m.Name, G(a, "movie", m.CategoryId));
                        if (r != null) films.Add(new HiddenRow { Code = "movie", Key = m.Key, Name = m.Name, AccountId = a.Account.Id, Reason = r });
                    }
                    foreach (var s in a.Series)
                    {
                        var r = Reason("series", s.Key, s.Name, G(a, "series", s.CategoryId));
                        if (r != null) series.Add(new HiddenRow { Code = "series", Key = s.Key, Name = s.Name, AccountId = a.Account.Id, Reason = r });
                    }
                    foreach (var c in a.Live)
                    {
                        var gk = string.IsNullOrEmpty(c.CategoryId) ? null : a.Account.Id + "|" + c.CategoryId;
                        var r = LiveReason(c.Name, G(a, "live", c.CategoryId), gk);
                        if (r != null) live.Add(new HiddenRow { Code = "live", Key = c.Key, Name = c.Name, AccountId = a.Account.Id, Reason = r });
                    }
                }
            }
            Func<List<HiddenRow>, List<HiddenRow>> sort = l => l.OrderBy(x => Normalize(x.Name), StringComparer.Ordinal).ToList();
            return new Dictionary<string, List<HiddenRow>> { { "movie", sort(films) }, { "series", sort(series) }, { "live", sort(live) } };
        }

        /// <summary>hidden-list.txt in the Filter folder: every hidden film, series and channel with the reason.</summary>
        public void WriteHiddenList(IList<AccountIndex> index)
        {
            try
            {
                var folder = Folder();
                if (folder == null) return;
                Directory.CreateDirectory(folder);
                var lists = HiddenList(index);
                var sb = new StringBuilder("Nova IPTV — hidden by the age filter (" + Levels[Math.Max(0, Math.Min(5, Level))] + ") · " + DateTime.Now.ToString("dd/MM/yyyy HH:mm") + "\n");
                sb.Append("☑ hidden · ☐ allowed anyway (exception)\n");
                var names = index.ToDictionary(a => a.Account.Id, a => a.Account.DisplayName);
                foreach (var part in new[] { Tuple.Create("movie", "FILMS"), Tuple.Create("series", "SERIES"), Tuple.Create("live", "CHANNELS") })
                {
                    var rows = lists[part.Item1];
                    sb.Append("\n==== " + part.Item2 + " (" + rows.Count + ") ====\n");
                    foreach (var r in rows)
                    {
                        sb.Append(_allowed.Contains(r.ExKey) ? "☐ " : "☑ ").Append(r.Name).Append("  —  ").Append(r.Reason);
                        string an;
                        if (index.Count > 1 && names.TryGetValue(r.AccountId, out an)) sb.Append("  ·  ").Append(an);
                        sb.Append('\n');
                    }
                }
                File.WriteAllText(Path.Combine(folder, "hidden-list.txt"), sb.ToString(), new UTF8Encoding(true));
            }
            catch { }
        }

        private static string Ago(long t)
        {
            var m = (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - t) / 60000;
            return m < 1 ? "just now" : m < 60 ? m + " min ago" : m < 48 * 60 ? (m / 60) + " h ago" : (m / 1440) + " days ago";
        }

        private static string N(int n) { return n.ToString("N0", CultureInfo.CurrentCulture); }

        /// <summary>"✓ 5 230 films & series · Hidden by age: 1 146 (above PG) · …".</summary>
        public string Summary(IList<AccountIndex> index)
        {
            if (!Active) return "Age filter OFF · word filter OFF — nothing hidden by the filters";
            int total = 0, byAge = 0, byWords = 0, unknown = 0;
            Action<string, string, string> count = (code, key, name) =>
            {
                total++;
                if (Level != 0 && RatingOf(code, key, name, null) == 0) unknown++;
                if (_allowed.Contains(code + "|" + key)) return;
                var r = Reason(code, key, name, null);
                if (r == null) return;
                if (r.StartsWith("word:")) byWords++; else byAge++;
            };
            foreach (var a in index)
            {
                foreach (var m in a.Movies) count("movie", m.Key, m.Name);
                foreach (var s in a.Series) count("series", s.Key, s.Name);
            }
            if (total == 0) return "The search index is not ready yet — the filters start after it.";
            var age = Level == 0 ? "Age filter OFF" : "🔞 Hidden by age: " + N(byAge) + " (above " + Levels[Level] + (HideUnknown ? " or no age found" : "") + ") · " +
                N(unknown) + " without age" + (Source == "tmdb" && _checkedAt > 0 ? " · checked " + Ago(_checkedAt) : "");
            var words = WordsActive ? "🔤 hidden by words: " + N(byWords) : "word filter OFF";
            return "✓ " + N(total) + " films & series · " + age + " · " + words;
        }

        // ------------------------------------------------------------------ the TMDB check

        private int _found, _notFound, _failed;
        private string _lastError;
        private int _busy;
        public bool Busy { get { return _busy == 1; } }
        /// <summary>Progress / result of the check, for Settings.</summary>
        public string Status { get; private set; }
        public string Diagnosis { get; private set; }
        public event Action StatusChanged;

        private void SetStatus(string s)
        {
            Status = s;
            var h = StatusChanged;
            if (h != null) try { h(); } catch { }
        }

        private void Diag()
        {
            Diagnosis = "🔎 TMDB: " + N(_found) + " found · " + N(_notFound) + " not found · " + N(_failed) + " failed" +
                (_failed > 0 && _lastError != null ? " — " + _lastError : "") +
                (_failed > _found + _notFound && _failed > 20 ? " (your internet may block themoviedb.org: turn the VPN on)" : "");
        }

        private sealed class Job { public string Code, Key, Name, Tmdb; public int? Year; }

        /// <summary>Looks up the US rating of every film and series of the index that is not known yet (6 at a time).</summary>
        public async Task Check(IList<AccountIndex> index, CancellationToken ct)
        {
            Load();
            if (Level == 0 || Source != "tmdb") { SetStatus(Summary(index)); return; }
            var key = EffectiveKey;
            if (string.IsNullOrWhiteSpace(key)) { SetStatus("Add your free TMDB key to check the ages."); return; }
            if (Interlocked.Exchange(ref _busy, 1) == 1) return;
            try
            {
                var api = new Tmdb(key);
                var todo = new List<Job>();
                foreach (var a in index)
                {
                    foreach (var m in a.Movies) if (!_items.ContainsKey("movie|" + m.Key)) todo.Add(new Job { Code = "movie", Key = m.Key, Name = m.Name, Year = m.Year, Tmdb = m.Tmdb });
                    foreach (var s in a.Series) if (!_items.ContainsKey("series|" + s.Key)) todo.Add(new Job { Code = "series", Key = s.Key, Name = s.Name, Year = s.Year, Tmdb = s.Tmdb });
                }
                if (todo.Count == 0) { SetStatus(Summary(index)); return; }
                _found = _notFound = _failed = 0; _lastError = null;
                var done = 0;
                var lastBump = DateTime.UtcNow;
                var gate = new SemaphoreSlim(6);
                for (int start = 0; start < todo.Count && !ct.IsCancellationRequested; start += 200)
                {
                    var chunk = todo.Skip(start).Take(200).ToList();
                    var tasks = chunk.Select(j => Task.Run(async () =>
                    {
                        await gate.WaitAsync(ct).ConfigureAwait(false);
                        try
                        {
                            int? r = Lookup(api, j);
                            if (r == null) Interlocked.Increment(ref _failed);
                            else if (r.Value > 0) Interlocked.Increment(ref _found);
                            else Interlocked.Increment(ref _notFound);
                            if (r != null) _items[j.Code + "|" + j.Key] = r.Value;
                            var n = Interlocked.Increment(ref done);
                            if (n % 25 == 0) { Diag(); SetStatus("Checking ages… " + N(n) + " / " + N(todo.Count)); }
                        }
                        finally { gate.Release(); }
                    }, ct)).ToList();
                    await Task.WhenAll(tasks).ConfigureAwait(false);
                    _checkedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                    // Lists are redrawn at most every 20 s while checking.
                    if ((DateTime.UtcNow - lastBump).TotalSeconds > 20) { Save(); Bump(); lastBump = DateTime.UtcNow; }
                }
                Diag();
                SetStatus(Summary(index));
            }
            catch (KeyRefused e) { Diag(); SetStatus("Age check stopped: " + e.Message); }
            catch (OperationCanceledException) { }
            catch (Exception e) { Diag(); SetStatus("Age check stopped: " + e.Message); }
            finally
            {
                Save();
                Interlocked.Exchange(ref _busy, 0);
                Bump();
            }
        }

        /// <summary>The rating of one title (0 = none found), or null for a network problem (tried again next time).</summary>
        private int? Lookup(Tmdb api, Job j)
        {
            var tv = j.Code == "series";
            try
            {
                var rating = -1;
                if (!string.IsNullOrWhiteSpace(j.Tmdb) && j.Tmdb.All(char.IsDigit) && j.Tmdb != "0")
                {
                    var ak = (tv ? "t:" : "m:") + j.Tmdb;
                    int known;
                    if (_answers.TryGetValue(ak, out known)) rating = known;
                    else
                    {
                        rating = tv ? api.TvRating(j.Tmdb) : api.MovieRating(j.Tmdb);
                        if (rating >= 0) _answers[ak] = rating;
                    }
                }
                if (rating < 0)
                {
                    var clean = CleanName(j.Name, j.Year);
                    var nk = (tv ? "tn:" : "mn:") + clean.Item1 + "|" + (clean.Item2.HasValue ? clean.Item2.Value.ToString(CultureInfo.InvariantCulture) : "");
                    int known;
                    if (_answers.TryGetValue(nk, out known)) rating = known;
                    else
                    {
                        var found = api.Search(tv, clean.Item1, clean.Item2);
                        rating = found == null ? 0 : found == "adult" ? Adult : Math.Max(0, tv ? api.TvRating(found) : api.MovieRating(found));
                        _answers[nk] = rating;
                    }
                }
                return Math.Max(0, rating);
            }
            catch (KeyRefused) { throw; }
            catch (Exception e)
            {
                var we = e as WebException;
                _lastError = we != null && we.Status == WebExceptionStatus.NameResolutionFailure ? "no connection to TMDB (name not found)"
                    : we != null && we.Status == WebExceptionStatus.Timeout ? "no answer from TMDB (timeout)"
                    : we != null && we.Status == WebExceptionStatus.SecureChannelFailure ? "secure connection to TMDB refused"
                    : we != null && we.Status == WebExceptionStatus.ConnectFailure ? "no connection to TMDB"
                    : e.Message;
                return null;
            }
        }

        /// <summary>Forget the saved answers (e.g. after changing the key).</summary>
        public void Forget()
        {
            _items.Clear(); _answers.Clear(); _checkedAt = 0;
            try { var p = RatingsFile; if (p != null && File.Exists(p)) File.Delete(p); } catch { }
            Bump();
        }
    }
}
