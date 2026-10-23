using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Nova.Core.Tests
{
    /// <summary>Answers API calls with prepared JSON, chosen by the "action" in the address.</summary>
    internal class FakeServer : HttpMessageHandler
    {
        public readonly Dictionary<string, string> Answers = new Dictionary<string, string>();
        public readonly List<string> Requests = new List<string>();
        public string LastUserAgent;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var url = request.RequestUri.AbsoluteUri; // exactly as sent (ToString() would un-escape it)
            Requests.Add(url);
            IEnumerable<string> ua;
            if (request.Headers.TryGetValues("User-Agent", out ua)) LastUserAgent = string.Join(" ", ua);
            var q = request.RequestUri.Query;
            var action = "";
            foreach (var part in q.TrimStart('?').Split('&'))
                if (part.StartsWith("action=")) action = part.Substring(7);
            string body;
            if (!Answers.TryGetValue(action, out body))
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json"),
            });
        }
    }

    internal static partial class Program
    {
        private static int _passed, _failed;

        private static void Check(bool ok, string what)
        {
            if (ok) { _passed++; }
            else { _failed++; Console.WriteLine("FAIL: " + what); }
        }

        private static void Eq<T>(T expected, T actual, string what)
        {
            Check(EqualityComparer<T>.Default.Equals(expected, actual), what + " — expected <" + expected + "> got <" + actual + ">");
        }

        private static readonly Account Acc = new Account { Id = "a1", Name = "Main", Server = "http://host.tv:8080/", Username = "user name", Password = "p@ss" };

        private static int Main()
        {
            Run("Server addresses", ServerAddresses);
            Run("Pasted links", PastedLinks);
            Run("Login", () => Login().GetAwaiter().GetResult());
            Run("Lists", () => Lists().GetAwaiter().GetResult());
            Run("Series info", SeriesInfoParsing);
            Run("Film info", FilmInfo);
            Run("Guide", Guide);
            Run("Ratings, years, durations", Helpers);
            Run("Storage", Storage);
            Run("Catalog and search", () => CatalogSearch().GetAwaiter().GetResult());
            Run("Backup protection (Android sample)", Crypto);
            Run("Backup (Android format)", BackupRoundTrip);
            Run("Accounts list", AccountsListTest);
            Run("Catch-up, M3U, order", Misc);
            Run("Age & word filter", FilterTests);
            Run("Similar channels", SimilarTests);
            Run("Filter and lists backups (box keys)", FilterBackupTests);
            Run("Search index file (.novaindex)", IndexFileTests);
            Run("Playback report", ReportTests);
            RunStreamingTests();
            Console.WriteLine();
            Console.WriteLine("Passed: " + _passed + "   Failed: " + _failed);
            return _failed == 0 ? 0 : 1;
        }

        private static void Run(string name, Action test)
        {
            Console.WriteLine("== " + name);
            try { test(); }
            catch (Exception e) { _failed++; Console.WriteLine("FAIL: " + name + " threw " + e); }
        }

        private static void Crypto()
        {
            // Made exactly like the Android app does it (PBKDF2-SHA256 120 000 rounds, AES-256-GCM).
            const string android = "NOVAENC1:AAECAwQFBgcICQoLDA0OD2RlZmdoaWprbG1ub6NBlGgolgRZ2MzGmSi8SVhnWMaM5+ybC5dbeXynoQCJNvTyIE5mBQ+ulRIkvKmlwgGOK5Q=";
            Check(NovaCrypto.IsProtected(android), "protected file recognised");
            Eq("{\"app\":\"NovaIPTV\",\"note\":\"عربي\"}", NovaCrypto.Decrypt(android, "pässwörd"), "Android-protected file opens on Windows");
            var wrong = false;
            try { NovaCrypto.Decrypt(android, "nope"); } catch (NovaException e) { wrong = e.Message == "Wrong password"; }
            Check(wrong, "wrong password reported");
            var mine = NovaCrypto.Encrypt("hello عربي", "pw");
            Eq("hello عربي", NovaCrypto.Decrypt(mine, "pw"), "Windows round trip");
            Check(mine.StartsWith("NOVAENC1:"), "same prefix as Android");
        }

        private static void BackupRoundTrip()
        {
            var dir = Path.Combine(Path.GetTempPath(), "nova-bak-" + Guid.NewGuid().ToString("N"));
            try
            {
                // A backup as the Android app writes it.
                var android = new JObject
                {
                    ["app"] = "NovaIPTV", ["version"] = 1, ["created"] = 1760000000000L,
                    ["prefs"] = new JObject
                    {
                        ["accounts"] = new JObject { ["t"] = "s", ["v"] = "[{\"id\":\"a-1\",\"name\":\"Main\",\"server\":\"http://h:80\",\"username\":\"u\",\"password\":\"p\",\"fromList\":true}]" },
                        ["favorites_v2"] = new JObject { ["t"] = "s", ["v"] = "[{\"type\":\"live\",\"id\":\"9\",\"name\":\"BBC\",\"savedAt\":5,\"accountId\":\"a-1\"}]" },
                        ["org"] = new JObject { ["t"] = "s", ["v"] = "{\"hiddenGroups\":[\"live|a-1|3\"],\"groupOrder\":{\"live\":[\"a-1|2\",\"a-1|1\"]}}" },
                        ["pin_hash"] = new JObject { ["t"] = "s", ["v"] = Store.Sha256Hex("salt1234") },
                        ["pin_salt"] = new JObject { ["t"] = "s", ["v"] = "salt" },
                        ["pos_movie_a-1_77"] = new JObject { ["t"] = "l", ["v"] = 123456 },
                        ["cushion_seconds"] = new JObject { ["t"] = "i", ["v"] = 12 },
                        ["sort_movie"] = new JObject { ["t"] = "s", ["v"] = "year" },
                        ["hdr_site.com"] = new JObject { ["t"] = "s", ["v"] = "Referer: https://site.com/\nUser-Agent: Browser" },
                        ["tunneling"] = new JObject { ["t"] = "b", ["v"] = true },
                        ["user_agent"] = new JObject { ["t"] = "s", ["v"] = "NovaIPTV/1.0 (Linux; Android)" },
                    },
                };
                var store = new Store(dir, Path.Combine(dir, "cache"));
                var c = Backup.Read(android.ToString(), store.Settings);
                Check(c.FromAndroid, "recognised as an Android backup");
                Eq(1, c.Accounts.Count, "account read");
                Check(c.Accounts[0].FromList, "accounts-link flag read");
                Eq(1, c.Favorites.Count, "favorite read");
                Check(c.State.Org.HiddenGroups.Contains("live|a-1|3"), "hidden group read");
                Eq("a-1|2", c.State.Org.GroupOrder["live"][0], "group order read");
                Eq(123456L, c.State.Positions["movie_a-1_77"], "resume position read");
                Eq(12, c.Settings.CushionSeconds, "setting read");
                Eq("year", c.Settings.SortMovie, "sorting read");
                Eq(Store.DefaultUserAgent, c.Settings.UserAgent, "Android's own user agent not copied to Windows");
                Eq("https://site.com/", c.State.LinkHeaders["site.com"]["Referer"], "website headers read");
                Check(c.State.AndroidExtras.ContainsKey("tunneling"), "Android-only setting kept");
                store.ReplaceAll(c.Accounts, c.Settings, c.Favorites, c.State);
                Check(store.CheckPin("1234"), "PIN from Android works");

                // And back to Android.
                var back = JObject.Parse(Backup.Export(store));
                var prefs = (JObject)back["prefs"];
                Eq("NovaIPTV", (string)back["app"], "export app name");
                Eq(true, (bool)prefs["tunneling"]["v"], "Android-only setting goes back unchanged");
                Eq(123456L, (long)prefs["pos_movie_a-1_77"]["v"], "resume position written");
                Eq("l", (string)prefs["pos_movie_a-1_77"]["t"], "position type");
                Eq("i", (string)prefs["cushion_seconds"]["t"], "number type");
                var accounts = JArray.Parse((string)prefs["accounts"]["v"]);
                Eq("a-1", (string)accounts[0]["id"], "account id kept");
                Check(prefs["user_agent"] == null, "Windows user agent not sent to the box");
                Check(prefs["win_settings"] != null, "Windows settings included");

                // Windows → Windows keeps Windows settings.
                var s2 = store.Settings.Copy(); s2.FilmBufferMb = 4096; store.SaveSettings(s2);
                var again = Backup.Read(Backup.Export(store), new Settings());
                Eq(4096, again.Settings.FilmBufferMb, "Windows setting restored");
                Check(!again.FromAndroid, "recognised as a PC backup");
                var bad = false;
                try { Backup.Read("{\"app\":\"Other\"}", null); } catch (NovaException) { bad = true; }
                Check(bad, "other files refused");
            }
            finally { try { Directory.Delete(dir, true); } catch { } }
        }

        private static void FilterTests()
        {
            var f = new AgeFilter();
            f.Configure(new FilterSettings()); // the box's default: NC-17 + words
            Eq(5, f.Level, "default level NC-17");
            Check(f.Blocked("movie", "a|1", "Love Actually", null), "romance word hides by default");
            Eq("word: love", f.Reason("movie", "a|1", "Love Actually", null), "reason names the word");
            Check(!f.Blocked("movie", "a|2", "The Matrix", null), "ordinary title shown");
            Check(f.Blocked("movie", "a|3", "|FR| Amoür Fou (2010)", null), "accents and tags: amour → amoür");
            Check(!f.Blocked("movie", "a|4", "Glover", null), "whole words only (love ≠ Glover)");
            Check(f.Blocked("movie", "a|5", "XXX Party", null), "adult always hidden");
            var s = new FilterSettings { Level = 2, WordsOn = false };
            f.Configure(s);
            Check(f.Blocked("movie", "a|6", "Zombie Night", "Horreur"), "horror above PG hidden by the name rule");
            Check(!f.Blocked("movie", "a|7", "Toy Story", "Kids"), "kids group is G");
            Check(f.LiveBlocked(new LiveChannel { AccountId = "a", StreamId = "9", Name = "Adult XXX", CategoryId = "1" }, "Adults"), "adult channel hidden");
            Check(f.GroupBlocked("live", "a|1", "XXX Adult"), "adult group hidden");
            s.Allowed.Add("movie|a|6");
            f.Configure(s);
            Check(!f.Blocked("movie", "a|6", "Zombie Night", "Horreur"), "exception: allowed anyway");
            f.Configure(new FilterSettings { Level = 0, WordsOn = false });
            Check(!f.Active && !f.Blocked("movie", "a|5", "XXX Party", null), "both OFF: nothing hidden");
            var c = AgeFilter.CleanName("FR_Le Roi Lion_4K (2019) VF", null);
            Eq("le roi lion", c.Item1, "clean name");
            Eq(2019, c.Item2 ?? 0, "year from the name");
        }

        private static void SimilarTests()
        {
            Eq("tf1", ChannelMatch.Key("|FR| TF1 HD"), "tags and quality removed");
            Eq("tf1", ChannelMatch.Key("FR_TF1 FHD HEVC"), "underscore tag");
            Eq("tf1 1", ChannelMatch.Key("FR: TF1 +1"), "+1 kept as a number");
            Eq("fr", ChannelMatch.CountryOf("|FR| TF1 HD"), "country");
            Check(ChannelMatch.CountryOf("|4K| TF1") == null, "4K is not a country");
            Eq(3, ChannelMatch.QualityOf("TF1 4K"), "4K");
            Eq(1, ChannelMatch.QualityOf("TF1 HD"), "HD");
            var watched = new LiveChannel { AccountId = "a", StreamId = "1", Name = "|FR| TF1 4K", CategoryId = "uhd" };
            var all = new List<LiveChannel>
            {
                watched,
                new LiveChannel { AccountId = "a", StreamId = "2", Name = "FR: TF1 HD", CategoryId = "hd" },
                new LiveChannel { AccountId = "a", StreamId = "3", Name = "FR: TF1 FHD", CategoryId = "fhd" },
                new LiveChannel { AccountId = "a", StreamId = "4", Name = "|BE| TF1 HD", CategoryId = "be" },
                new LiveChannel { AccountId = "a", StreamId = "5", Name = "FR: France 2 HD", CategoryId = "hd" },
            };
            var sim = ChannelMatch.OnSameServer(watched, all, new Dictionary<string, string> { { "fhd", "France FHD" } });
            Eq(2, sim.Count, "same channel, same country only");
            Eq("3", sim[0].Channel.StreamId, "lighter version closest first (FHD)");
            Eq("France FHD", sim[0].Group, "group name");
            var other = ChannelMatch.SameElsewhere(watched, all.Skip(1).ToList());
            Eq("3", other.StreamId, "same channel elsewhere: closest quality");
        }

        private static void FilterBackupTests()
        {
            var dir = Path.Combine(Path.GetTempPath(), "nova-fbak-" + Guid.NewGuid().ToString("N"));
            try
            {
                var android = new JObject
                {
                    ["app"] = "NovaIPTV", ["version"] = 1, ["kind"] = "all",
                    ["web"] = new JObject { ["bookmarks"] = "x" },
                    ["prefs"] = new JObject
                    {
                        ["age_level"] = new JObject { ["t"] = "i", ["v"] = 3 },
                        ["age_source"] = new JObject { ["t"] = "s", ["v"] = "tmdb" },
                        ["age_allowed"] = new JObject { ["t"] = "s", ["v"] = "movie|a|1\nseries|a|2" },
                        ["age_word_groups_on"] = new JObject { ["t"] = "s", ["v"] = "adult,own" },
                        ["age_words_own"] = new JObject { ["t"] = "s", ["v"] = "dragon\nwitch" },
                        ["live_disk_minutes"] = new JObject { ["t"] = "i", ["v"] = 10 },
                        ["boost_k_live|a|9"] = new JObject { ["t"] = "i", ["v"] = 150 },
                    },
                };
                var store = new Store(dir, Path.Combine(dir, "cache"));
                var c = Backup.Read(android.ToString(), store.Settings);
                Eq(3, c.State.Filter.Level, "age level read");
                Eq("tmdb", c.State.Filter.Source, "age source read");
                Eq(2, c.State.Filter.Allowed.Count, "exceptions read");
                Check(c.State.Filter.WordGroupsOn.SequenceEqual(new[] { "adult", "own" }), "word groups read");
                Eq("witch", c.State.Filter.Words["own"][1], "own words read");
                Eq(10, c.Settings.LiveDiskMinutes, "live buffer length read");
                Eq(150, c.State.BoostFor["live|a|9"], "volume boost per channel read");
                Check(c.State.AndroidWeb != null, "box browser part kept");
                Check(!c.State.AndroidExtras.ContainsKey("age_level"), "filter not left in the extras");
                store.ReplaceAll(c.Accounts, c.Settings, c.Favorites, c.State);
                var back = JObject.Parse(Backup.Export(store));
                Eq(3, (int)back["prefs"]["age_level"]["v"], "age level written back");
                Eq("dragon\nwitch", (string)back["prefs"]["age_words_own"]["v"], "own words written back");
                Check(back["web"] != null, "browser part goes back with everything");
                Check(JObject.Parse(Backup.Export(store, "iptv"))["web"] == null, "IPTV-only backup without the browser");

                // Lists only.
                store.SetPin(null);
                var lists = Backup.ExportLists(store);
                var r = Backup.ReadLists(lists);
                Check(r.Item1 != null && r.Item2 != null, "lists backup reads back");
                var bad = false;
                try { Backup.ReadLists(Backup.Export(store)); } catch (NovaException) { bad = true; }
                Check(bad, "a full backup is not a lists backup");
            }
            finally { try { Directory.Delete(dir, true); } catch { } }
        }

        private static void IndexFileTests()
        {
            var dir = Path.Combine(Path.GetTempPath(), "nova-idx-" + Guid.NewGuid().ToString("N"));
            try
            {
                var store = new Store(Path.Combine(dir, "data"), Path.Combine(dir, "cache"));
                store.SaveAccount(new Account { Id = "x1", Name = "Main", Server = "http://S4.Example.net:8000/", Username = "u", Password = "p" });
                var catalog = new Catalog(new XtreamApi(() => "UA", new FakeServer()), store);
                var folder = Path.Combine(dir, "Index");
                catalog.IndexFolder = () => folder;
                Directory.CreateDirectory(folder);
                var acc = store.Accounts[0];
                File.WriteAllText(Path.Combine(folder, "x1-live.json"), Newtonsoft.Json.JsonConvert.SerializeObject(new[] { new LiveChannel { AccountId = "x1", StreamId = "1", Name = "TF1 HD", CategoryId = "2", Num = 1 } }));
                File.WriteAllText(Path.Combine(folder, "x1-movie.json"), Newtonsoft.Json.JsonConvert.SerializeObject(new[] { new Movie { AccountId = "x1", StreamId = "7", Name = "Film\tTab", CategoryId = "3", Extension = "mkv", Year = 2001, Tmdb = "603" } }));
                File.WriteAllText(Path.Combine(folder, "x1-series.json"), Newtonsoft.Json.JsonConvert.SerializeObject(new[] { new Series { AccountId = "x1", SeriesId = "9", Name = "Show", CategoryId = "4" } }));
                Eq("s4.example.net:8000", Catalog.HostOf(acc.Server), "host of a server");
                var path = Path.Combine(dir, "out.novaindex");
                Eq(3, catalog.ExportIndex(path, store.Accounts, "2.0"), "three items exported");
                long at;
                var sections = Catalog.ReadIndexFile(path, out at);
                Eq(1, sections.Count, "one server section");
                Eq("s4.example.net:8000", sections[0].Host, "linked by host, no login inside");
                Eq("603", sections[0].Movies[0].Tmdb, "film database number kept");
                Eq("Film Tab", sections[0].Movies[0].Name, "tabs cleaned");
                Check(!File.ReadAllText(path).Contains("u:p"), "no password");
                foreach (var f in Directory.GetFiles(folder)) File.Delete(f);
                var res = catalog.ImportIndex(path);
                Eq(1, res.Item1.Count, "imported to the account with the same host");
                Check(File.Exists(Path.Combine(folder, "x1-movie.json")), "index files written");
                var bad = false;
                File.WriteAllText(Path.Combine(dir, "bad.novaindex"), "hello");
                try { Catalog.ReadIndexFile(Path.Combine(dir, "bad.novaindex"), out at); } catch (NovaException) { bad = true; }
                Check(bad, "other files refused");
            }
            finally { try { Directory.Delete(dir, true); } catch { } }
        }

        private static void ReportTests()
        {
            var dir = Path.Combine(Path.GetTempPath(), "nova-log-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(dir);
            try
            {
                var log = new PlaybackLog(dir) { LogsFolder = () => Path.Combine(dir, "Logs") };
                var s = new PlaybackLog.Session { Title = "TF1 HD", Kind = "live", Server = "Main", Group = "France" };
                s.StartMs = 2300; s.NeedMbit = 6; s.Arrival(4); s.Fail("SLOW", "line too slow");
                log.End(s);
                var f = new PlaybackLog.Session { Title = "Dead channel", Kind = "live", Server = "Main" };
                f.Fail("FAIL", "no signal");
                log.End(f);
                var text = log.Report(7, "2.0 (build 9)", "test", new[] { "12:00|TF1|froze|jumped to live" });
                Check(text.Contains("TF1 HD") && text.Contains("Dead channel"), "both plays listed");
                Check(text.Contains("Failed most") && text.Contains("no signal"), "failures summed up");
                Check(text.Contains("needs 6.0 / got 4.0 Mbit/s"), "line speed shown");
                Check(log.Glance(7).StartsWith("Played 2"), "glance: " + log.Glance(7));
                var path = log.SaveReport(text);
                Check(File.Exists(path) && path.Contains("Logs"), "saved in Logs");
                log.Problem("2.0", "Error: test");
                Check((log.LastProblem() ?? "").Contains("Error: test"), "last problem kept");
            }
            finally { try { Directory.Delete(dir, true); } catch { } }
        }

        private static void AccountsListTest()
        {
            var list = AccountsList.Parse("# my accounts\nMain | host.tv:8080 | joe | 123\nSecond | http://b.tv/get.php?username=x&password=y\nhttp://c.tv:81/get.php?username=k&password=l\nmain | dup | a | b\n");
            Eq(3, list.Count, "three accounts, duplicate name ignored");
            Eq("http://host.tv:8080", list[0].Server, "server normalised");
            Eq("y", list[1].Password, "link form");
            Eq("c.tv:81", list[2].Name, "name from the link");
            var before = new List<Account>
            {
                new Account { Id = "1", Name = "Main", Server = "http://host.tv:8080", Username = "joe", Password = "old", FromList = true },
                new Account { Id = "2", Name = "Gone", Server = "http://g", Username = "u", Password = "p", FromList = true },
                new Account { Id = "3", Name = "Mine", Server = "http://m", Username = "u", Password = "p" },
            };
            string summary;
            var after = AccountsList.Apply(before, list, out summary);
            Eq("1", after[0].Id, "same name keeps its id (favorites stay)");
            Eq("123", after[0].Password, "password updated");
            Check(after.All(a => a.Id != "2"), "account that left the list removed");
            Check(after.Any(a => a.Id == "3"), "account typed by hand kept");
            Eq(4, after.Count, "two new accounts added");
            Check(summary.Contains("password updated") && summary.Contains("removed"), "summary: " + summary);
            var text = AccountsList.Write(after);
            Eq(after.Count, AccountsList.Parse(text).Count, "saved list reads back");
        }

        private static void Misc()
        {
            Func<string, string> b64 = x => Convert.ToBase64String(Encoding.UTF8.GetBytes(x));
            var now = 2000000L * 1000;
            var root = JObject.Parse("{\"epg_listings\":[" +
                "{\"title\":\"" + b64("Old news") + "\",\"start\":\"2026-10-08 20:00:00\",\"start_timestamp\":\"1000000\",\"stop_timestamp\":\"1003600\",\"has_archive\":1}," +
                "{\"title\":\"" + b64("Not kept") + "\",\"start\":\"2026-10-08 21:00:00\",\"start_timestamp\":\"1003600\",\"stop_timestamp\":\"1007200\",\"has_archive\":0}," +
                "{\"title\":\"" + b64("Future") + "\",\"start\":\"2026-10-09 21:00:00\",\"start_timestamp\":\"3000000\",\"stop_timestamp\":\"3003600\"}]}");
            var cu = XtreamApi.ParseCatchup(root, now);
            Eq(1, cu.Count, "only past, archived programmes");
            Eq("2026-10-08:20-00", cu[0].ServerStart, "server start format");
            Eq(60, cu[0].DurationMinutes, "duration");
            Eq("http://host.tv:8080/timeshift/user%20name/p%40ss/60/2026-10-08:20-00/42.ts", XtreamApi.CatchupUrl(Acc, "42", cu[0]), "timeshift address");

            var m3u = M3uPlaylist.Parse("#EXTM3U\n#EXTINF:-1 tvg-logo=\"l.png\" group-title=\"News\",BBC One\nhttp://x/1.ts\n#EXTINF:-1,Local\nrel/2.m3u8\n", "http://site.com/list/all.m3u");
            Eq(2, m3u.Count, "playlist entries");
            Eq("BBC One", m3u[0].Name, "entry name");
            Eq("l.png", m3u[0].Logo, "entry logo");
            Eq("http://site.com/list/rel/2.m3u8", m3u[1].Url, "relative address resolved");

            var order = Org.ApplyOrder(new List<string> { "a", "b", "c", "d" }, new List<string> { "c", "a" }, x => x);
            Check(order.SequenceEqual(new[] { "c", "a", "b", "d" }), "saved order first, others keep their place");
            Check(Org.Move(new List<string> { "a", "b", "c" }, "c", -1).SequenceEqual(new[] { "a", "c", "b" }), "move up");
            Check(Org.MoveTo(new List<string> { "a", "b", "c" }, "c", true).SequenceEqual(new[] { "c", "a", "b" }), "move to top");
        }

        private static void ServerAddresses()
        {
            Eq("http://host.tv:8080", XtreamApi.NormalizeServer("host.tv:8080/"), "adds http, drops slash");
            Eq("http://host.tv:80", XtreamApi.NormalizeServer("http://host.tv:80/player_api.php?username=x"), "drops api page and query");
            Eq("https://h.tv", XtreamApi.NormalizeServer(" https://h.tv/get.php "), "https kept, get.php dropped");
            Eq("http://host.tv:8080/live/user%20name/p%40ss/42.ts", XtreamApi.LiveUrl(Acc, "42", "ts"), "live address escapes login");
            Eq("http://host.tv:8080/movie/user%20name/p%40ss/7.mkv", XtreamApi.MovieUrl(Acc, "7", "mkv"), "film address");
            Eq("http://host.tv:8080/series/user%20name/p%40ss/9.mp4", XtreamApi.EpisodeUrl(Acc, "9", "mp4"), "episode address");
        }

        private static void PastedLinks()
        {
            string s, u, p;
            Check(XtreamApi.TryParseLink("http://abc.com:8080/get.php?username=joe&password=123&type=m3u_plus", out s, out u, out p), "m3u link recognised");
            Eq("http://abc.com:8080", s, "server from link");
            Eq("joe", u, "user from link");
            Eq("123", p, "password from link");
            Check(XtreamApi.TryParseLink("http://abc.com/get.php?username=a&password=b", out s, out u, out p) && s == "http://abc.com", "default port hidden");
            Check(!XtreamApi.TryParseLink("http://abc.com:8080", out s, out u, out p), "plain server is not a link");
        }

        private static async Task Login()
        {
            var fake = new FakeServer();
            var api = new XtreamApi(() => "UA-test", fake);
            fake.Answers[""] = "{\"user_info\":{\"auth\":1,\"status\":\"Active\",\"exp_date\":\"1893456000\",\"max_connections\":\"1\",\"active_cons\":0,\"is_trial\":\"0\"}}";
            var info = await api.Authenticate(Acc);
            Eq("Active", info.Status, "status");
            Eq(1893456000L, info.ExpDate ?? 0, "expiry");
            Eq("1", info.MaxConnections, "max connections");
            Eq("0", info.ActiveConnections, "active connections (number sent as number)");
            Eq("UA-test", fake.LastUserAgent, "user agent sent");
            Check(fake.Requests[0].Contains("username=user%20name") && fake.Requests[0].Contains("password=p%40ss"), "login escaped in API address");

            fake.Answers[""] = "{\"user_info\":{\"auth\":0}}";
            var wrong = false;
            try { await api.Authenticate(Acc); } catch (NovaException e) { wrong = e.Message.Contains("Wrong username"); }
            Check(wrong, "wrong password reported");

            fake.Answers[""] = "<html>not xtream</html>";
            var bad = false;
            try { await api.Authenticate(Acc); } catch (NovaException) { bad = true; }
            Check(bad, "non-JSON answer reported as a readable error");

            fake.Answers[""] = "{\"user_info\":{\"auth\":\"1\",\"exp_date\":null,\"status\":\"Active\"}}";
            info = await api.Authenticate(Acc);
            Check(info.ExpDate == null, "unlimited account has no expiry");
        }

        private static async Task Lists()
        {
            var fake = new FakeServer();
            var api = new XtreamApi(() => "UA", fake);
            fake.Answers["get_live_categories"] = "[{\"category_id\":\"1\",\"category_name\":\"News\"},{\"category_id\":2,\"category_name\":null},{\"category_name\":\"no id\"}]";
            var cats = await api.Categories(Acc, ContentType.Live);
            Eq(2, cats.Count, "categories without id skipped");
            Eq("2", cats[1].Id, "numeric id read as text");
            Eq("Unnamed", cats[1].Name, "missing name");
            Eq("a1|1", cats[0].Key, "category key has account");

            fake.Answers["get_live_streams"] = "[{\"num\":\"3\",\"name\":\"BBC\",\"stream_id\":101,\"stream_icon\":\"\",\"category_id\":\"1\",\"tv_archive\":1,\"added\":\"1700000000\"}," +
                "{\"num\":4.0,\"name\":\"CNN\",\"stream_id\":\"102\",\"stream_icon\":\"http://x/y.png\",\"category_id\":\"1\",\"tv_archive\":\"0\",\"added\":null}]";
            var ch = await api.LiveStreams(Acc, "1");
            Eq(2, ch.Count, "two channels");
            Eq(3, ch[0].Num, "number from text");
            Eq(4, ch[1].Num, "number from decimal");
            Check(ch[0].Icon == null, "empty icon is null");
            Check(ch[0].HasArchive && !ch[1].HasArchive, "catch-up flag");
            Eq(1700000000L, ch[0].Added, "added time");
            Check(fake.Requests.Last().Contains("category_id=1"), "group asked for");

            // Some servers send an object map instead of an array, or nothing at all.
            fake.Answers["get_vod_streams"] = "{\"0\":{\"stream_id\":\"7\",\"name\":\"Film (2019)\",\"rating\":\"7.5\",\"container_extension\":\"mkv\"},\"1\":{\"stream_id\":\"8\",\"name\":\"Other\",\"rating_5based\":4}}";
            var films = await api.Movies(Acc, null);
            Eq(2, films.Count, "object map accepted");
            Eq("mkv", films[0].Extension, "container");
            Eq("7.5", films[0].Rating, "rating");
            Eq("8", films[1].Rating, "rating from 5-star scale");
            Eq(2019, films[0].Year ?? 0, "year from name");
            Eq("mp4", films[1].Extension, "default container");

            fake.Answers["get_series"] = "null";
            Eq(0, (await api.SeriesList(Acc, null)).Count, "null list = empty");
            fake.Answers["get_series"] = "[]";
            Eq(0, (await api.SeriesList(Acc, null)).Count, "empty list");
            fake.Answers["get_series"] = "[{\"series_id\":5,\"name\":\"Show\",\"cover\":\"c.jpg\",\"plot\":\"p\",\"rating\":\"85\",\"last_modified\":\"1600000000\",\"releaseDate\":\"2011-04-17\"}]";
            var series = await api.SeriesList(Acc, null);
            Eq("8.5", series[0].Rating, "rating out of 100 scaled");
            Eq(2011, series[0].Year ?? 0, "year from release date");
            Eq(1600000000L, series[0].Added, "last modified as added");
        }

        private static void SeriesInfoParsing()
        {
            var s = new Series { AccountId = "a1", SeriesId = "5", Name = "Show" };
            var root = JObject.Parse("{\"info\":{\"cover\":\"cov.jpg\",\"plot\":\"Plot!\",\"genre\":\"Drama\",\"cast\":\"A, B\",\"director\":\"D\",\"episode_run_time\":\"45\",\"backdrop_path\":[\"bd.jpg\"],\"youtube_trailer\":\"abc\"}," +
                "\"episodes\":{\"2\":[{\"id\":\"21\",\"episode_num\":\"2\",\"title\":\"S2E2\",\"container_extension\":\"mkv\"},{\"id\":\"20\",\"episode_num\":1,\"title\":\"S2E1\",\"info\":{\"plot\":\"ep plot\",\"movie_image\":\"e.jpg\"}}]," +
                "\"1\":[{\"id\":\"10\",\"episode_num\":\"1\",\"title\":\"S1E1\"}]}}");
            var info = XtreamApi.ParseSeriesInfo(s, root);
            Check(info.Seasons.SequenceEqual(new[] { 1, 2 }), "seasons sorted");
            Eq("20", info.Episodes[2][0].Id, "episodes sorted by number");
            Eq("ep plot", info.Episodes[2][0].Plot, "episode plot");
            Eq("mkv", info.Episodes[2][1].Extension, "episode container");
            Eq("cov.jpg", info.Series.Cover, "cover filled from info");
            Eq("45m / episode", info.Details.Duration, "run time");
            Eq("bd.jpg", info.Details.Backdrop, "backdrop from array");
            Eq("https://www.youtube.com/watch?v=abc", info.Details.TrailerUrl, "trailer address");

            // Array of seasons form.
            var root2 = JObject.Parse("{\"episodes\":[[{\"id\":\"1\",\"season\":\"3\",\"episode_num\":\"1\"}]]}");
            var info2 = XtreamApi.ParseSeriesInfo(new Series { AccountId = "a1", SeriesId = "6", Name = "X", Plot = "pp" }, root2);
            Check(info2.Seasons.SequenceEqual(new[] { 3 }), "season from episode");
            Eq("pp", info2.Plot, "plot falls back to list plot");
        }

        private static void FilmInfo()
        {
            var d = XtreamApi.DetailsOf(JObject.Parse("{\"plot\":\"P\",\"director\":\"Dir\",\"actors\":\"X, Y\",\"genre\":\"Action\",\"releasedate\":\"2020-01-02\",\"duration\":\"01:24:00\",\"rating\":\"6,8\",\"movie_image\":\"m.jpg\",\"backdrop_path\":\"b.jpg\",\"trailer\":\"https://youtu.be/q\"}"));
            Eq("P", d.Plot, "plot");
            Eq("X, Y", d.Cast, "actors as cast");
            Eq("1h 24m", d.Duration, "hh:mm:ss duration");
            Eq(6.8, d.Rating ?? 0, "comma decimal rating");
            Eq("b.jpg", d.Backdrop, "backdrop as text");
            Eq("https://youtu.be/q", d.TrailerUrl, "full trailer address kept");
            var d2 = XtreamApi.DetailsOf(JObject.Parse("{\"duration_secs\":5400}"));
            Eq("1h 30m", d2.Duration, "duration from seconds");
        }

        private static void Guide()
        {
            Func<string, string> b64 = s => Convert.ToBase64String(Encoding.UTF8.GetBytes(s));
            var root = JObject.Parse("{\"epg_listings\":[{\"title\":\"" + b64("Later") + "\",\"description\":\"" + b64("d2") + "\",\"start_timestamp\":\"2000\",\"stop_timestamp\":\"3000\"}," +
                "{\"title\":\"" + b64("Now — Arabic عربي") + "\",\"description\":\"\",\"start_timestamp\":1000,\"stop_timestamp\":2000},{\"title\":\"x\"}]}");
            var list = XtreamApi.ParseEpg(root);
            Eq(2, list.Count, "entries without times skipped");
            Eq("Now — Arabic عربي", list[0].Title, "base64 UTF-8 title, sorted");
            EpgEntry now, next;
            XtreamApi.NowNext(list, 1500 * 1000L, out now, out next);
            Eq("Now — Arabic عربي", now == null ? null : now.Title, "now");
            Eq("Later", next == null ? null : next.Title, "next");
            Eq(0.5, list[0].Progress(1500 * 1000L), "progress");
            var plain = XtreamApi.ParseEpg(JObject.Parse("{\"epg_listings\":[{\"title\":\"not base64!\",\"start_timestamp\":1,\"stop_timestamp\":2}]}"));
            Eq("not base64!", plain[0].Title, "plain text title kept");
        }

        private static void Helpers()
        {
            Eq("1h 05m", XtreamApi.FormatMinutes(65), "minutes format");
            Eq("45m", XtreamApi.FormatMinutes(45), "short minutes");
            Eq("2h 00m", XtreamApi.FormatDuration("120 min"), "minutes text");
            Eq(1999, XtreamApi.YearFromName("Matrix [1999] HD") ?? 0, "year in brackets");
            Check(XtreamApi.YearFromName("Blade Runner 2049x") == null, "no year glued to text");
            Eq(2049, XtreamApi.YearFromName("Blade Runner 2049") ?? 0, "year at end");
        }

        private static void Storage()
        {
            var dir = Path.Combine(Path.GetTempPath(), "nova-test-" + Guid.NewGuid().ToString("N"));
            try
            {
                var st = new Store(dir, Path.Combine(dir, "cache"));
                Eq(0, st.Accounts.Count, "starts empty");
                var a = new Account { Name = "One", Server = "http://s", Username = "u", Password = "p" };
                st.SaveAccount(a);
                Check(!string.IsNullOrEmpty(a.Id), "id given");
                var fav = SavedItem.Of(new LiveChannel { AccountId = a.Id, StreamId = "9", Name = "Ch" });
                Check(st.ToggleFavorite(fav), "favorite added");
                var s = st.Settings.Copy(); s.LiveFormat = "m3u8"; st.SaveSettings(s);
                st.SaveAccount(a); // second save creates the .bak copy

                var again = new Store(dir, Path.Combine(dir, "cache"));
                Eq(1, again.Accounts.Count, "account kept");
                Eq("m3u8", again.Settings.LiveFormat, "setting kept");
                Check(again.IsFavorite(fav), "favorite kept");
                Eq(1, again.FavoritesOf(ItemType.Live).Count, "favorites by type");
                var json = File.ReadAllText(Path.Combine(dir, "favorites.json"));
                Check(json.Contains("\"accountId\"") && json.Contains("\"savedAt\""), "favorites use the Android field names");

                File.WriteAllText(Path.Combine(dir, "accounts.json"), "{ broken");
                var recovered = new Store(dir, Path.Combine(dir, "cache"));
                Eq(1, recovered.Accounts.Count, "damaged file recovered from .bak");

                recovered.RemoveAccount(a.Id);
                Eq(0, recovered.Favorites.Count, "removing an account removes its favorites");
            }
            finally
            {
                try { Directory.Delete(dir, true); } catch { }
            }
        }

        private static async Task CatalogSearch()
        {
            var dir = Path.Combine(Path.GetTempPath(), "nova-test-" + Guid.NewGuid().ToString("N"));
            try
            {
                var st = new Store(dir, Path.Combine(dir, "cache"));
                st.SaveAccount(new Account { Id = "a1", Name = "One", Server = "http://s1", Username = "u", Password = "p" });
                var fake = new FakeServer();
                var cat = new Catalog(new XtreamApi(() => "UA", fake), st);
                fake.Answers["get_live_categories"] = "[{\"category_id\":\"1\",\"category_name\":\"News\"}]";
                fake.Answers["get_live_streams"] = "[{\"stream_id\":1,\"name\":\"BBC News\"},{\"stream_id\":2,\"name\":\"Sky News HD\"},{\"stream_id\":3,\"name\":\"Cartoon\"}]";
                fake.Answers["get_vod_streams"] = "[{\"stream_id\":5,\"name\":\"The News Movie\"}]";
                fake.Answers["get_series"] = "[]";

                var sections = await cat.Sections(ContentType.Live);
                Eq(1, sections.Count, "one section per account");
                Eq("News", sections[0].Groups[0].Name, "group loaded");
                var before = fake.Requests.Count;
                await cat.Sections(ContentType.Live);
                Eq(before, fake.Requests.Count, "groups cached");

                var res = await cat.Search("news", 50, CancellationToken.None);
                Eq(2, res.Channels.Count, "channels found");
                Eq("BBC News", res.Channels[0].Name, "sorted by name");
                Eq(1, res.Movies.Count, "films found");
                var res2 = await cat.Search("sky hd", 50, CancellationToken.None);
                Eq(1, res2.Channels.Count, "all words must match");

                fake.Answers.Remove("get_series");
                cat.Clear();
                var res3 = await cat.Search("news", 50, CancellationToken.None);
                Eq(1, res3.Errors.Count, "a failing list is reported, others still found");
                Eq(2, res3.Channels.Count, "channels still found");

                fake.Answers.Remove("get_vod_categories");
                var films = await cat.Sections(ContentType.Movie);
                Check(films[0].Error != null, "group error kept per account");
            }
            finally
            {
                try { Directory.Delete(dir, true); } catch { }
            }
        }
    }
}
