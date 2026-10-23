using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Nova.Core
{
    /// <summary>
    /// Playback report (same as the box): one record for every channel, film and episode played — start time, quality,
    /// line speed against what it needs, cushion, interruptions, watchdog actions, result. Kept 7 days.
    /// Never holds usernames, passwords or stream addresses: servers appear by their account name.
    /// Settings › System › Last problem › Playback report (saved as a .txt in Downloads\NovaIPTV\Logs).
    /// Also remembers why Nova closed by itself (Last problem).
    /// </summary>
    public sealed class PlaybackLog
    {
        private const long KeepMs = 7L * 24 * 3600000;
        private readonly string _file;
        private readonly object _lock = new object();

        /// <summary>Downloads\NovaIPTV\Logs.</summary>
        public Func<string> LogsFolder = () => null;

        public PlaybackLog(string dataFolder)
        {
            _file = Path.Combine(dataFolder, "playback-log.jsonl");
        }

        /// <summary>One playback in progress (filled by the player, written when it ends).</summary>
        public sealed class Session
        {
            public string Title, Kind, Server, Group, Key;
            public readonly long StartedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            public string Mode = "", Container = "", Video = "", Decoder = "";
            public long StartMs = -1;
            public double NeedMbit;
            private double _arrivalSum;
            private int _arrivalN;
            public int CushionStart = -1, CushionMax, CushionMin = int.MaxValue;
            public int Rebuffers;
            public long RebufferMs;
            public readonly List<string> Actions = new List<string>();
            public string Result = "OK";
            public string Note = "";

            public void Arrival(double mbit) { if (mbit > 0) { _arrivalSum += mbit; _arrivalN++; } }
            public double AvgArrival { get { return _arrivalN == 0 ? 0 : _arrivalSum / _arrivalN; } }

            public void Cushion(int sec)
            {
                if (sec < 0) return;
                if (CushionStart < 0) CushionStart = sec;
                if (sec > CushionMax) CushionMax = sec;
                if (sec < CushionMin) CushionMin = sec;
            }

            public void Action(string a) { if (Actions.Count < 12) Actions.Add(a); }
            public void Fail(string r, string why) { if (Result == "OK" || Result == "SLOW") { Result = r; Note = why; } }
        }

        public void End(Session s)
        {
            if (s == null) return;
            var dur = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - s.StartedAt;
            // A channel skipped through in under 3 s without trouble is not worth a line.
            if (s.Result == "OK" && s.StartMs < 0 && dur < 3000) return;
            if (s.Result == "OK" && s.StartMs < 0) s.Fail("FAIL", "no picture (left after " + dur / 1000 + " s)");
            var o = new JObject
            {
                ["at"] = s.StartedAt, ["dur"] = dur, ["t"] = s.Title, ["k"] = s.Kind, ["srv"] = s.Server, ["grp"] = s.Group ?? "",
                ["mode"] = s.Mode, ["cont"] = s.Container, ["vid"] = s.Video, ["dec"] = s.Decoder, ["start"] = s.StartMs,
                ["need"] = s.NeedMbit, ["got"] = s.AvgArrival, ["c0"] = s.CushionStart, ["cmax"] = s.CushionMax,
                ["cmin"] = s.CushionMin == int.MaxValue ? -1 : s.CushionMin, ["rb"] = s.Rebuffers, ["rbms"] = s.RebufferMs,
                ["act"] = string.Join(" · ", s.Actions), ["res"] = s.Result, ["note"] = s.Note,
            };
            try
            {
                lock (_lock)
                {
                    File.AppendAllText(_file, o.ToString(Formatting.None) + "\n", new UTF8Encoding(false));
                    if (new FileInfo(_file).Length > 1500000) Prune();
                }
            }
            catch { }
        }

        private void Prune()
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var keep = File.ReadAllLines(_file).Where(l => { try { return now - (long)JObject.Parse(l)["at"] < KeepMs; } catch { return false; } }).ToList();
            File.WriteAllText(_file, string.Join("\n", keep.Skip(Math.Max(0, keep.Count - 4000))) + "\n", new UTF8Encoding(false));
        }

        private List<JObject> Records(int days)
        {
            var list = new List<JObject>();
            try
            {
                if (!File.Exists(_file)) return list;
                var from = days <= 1 ? new DateTimeOffset(DateTime.Today).ToUnixTimeMilliseconds()
                    : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - days * 24L * 3600000;
                string[] lines;
                lock (_lock) lines = File.ReadAllLines(_file);
                foreach (var l in lines)
                {
                    try { var o = JObject.Parse(l); if ((long)o["at"] >= from) list.Add(o); } catch { }
                }
            }
            catch { }
            return list;
        }

        public void Clear() { try { lock (_lock) File.Delete(_file); } catch { } }

        private static string S(JObject o, string k) { var t = o[k]; return t == null ? "" : (string)t ?? ""; }
        private static double D(JObject o, string k) { var t = o[k]; return t == null ? 0 : (double)t; }
        private static long L(JObject o, string k) { var t = o[k]; return t == null ? 0 : (long)t; }

        private static string F(double v) { return v.ToString("0.0", CultureInfo.InvariantCulture); }

        /// <summary>"Played 64 · OK 55 · failed 6 · can't decode here 3 · average start 2.3 s" + one line per server.</summary>
        public string Glance(int days = 7)
        {
            var r = Records(days);
            if (r.Count == 0) return "Nothing played yet in this period.";
            var ok = r.Count(o => S(o, "res") == "OK" || S(o, "res") == "SLOW");
            var failed = r.Count(o => S(o, "res") == "FAIL");
            var device = r.Count(o => S(o, "res") == "DEVICE");
            var starts = r.Select(o => L(o, "start")).Where(x => x >= 0).ToList();
            var avg = starts.Count == 0 ? "–" : F(starts.Average() / 1000.0) + " s";
            return "Played " + r.Count + " · OK " + ok + " · failed " + failed + " · can't decode here " + device + " · average start " + avg + "\n" +
                   string.Join("\n", ServerLines(r));
        }

        private static IEnumerable<string> ServerLines(List<JObject> r)
        {
            return r.GroupBy(o => S(o, "srv")).Select(g =>
            {
                var speeds = g.Select(o => D(o, "got")).Where(x => x > 0).ToList();
                var hours = g.Sum(o => L(o, "dur")) / 3600000.0;
                var rb = g.Sum(o => L(o, "rb"));
                return g.Key + ": " + (speeds.Count == 0 ? "speed –" : F(speeds.Average()) + " Mbit/s avg") +
                       (hours > 0.05 ? ", " + F(rb / hours) + " rebuffers / hour" : "") + ", " + g.Count() + " played";
            });
        }

        /// <summary>The full text report (period: 1 = today, 3 or 7 days).</summary>
        public string Report(int days, string version, string settings, IEnumerable<string> watchdog)
        {
            var r = Records(days);
            var sb = new StringBuilder();
            sb.AppendLine("NOVA IPTV PLAYBACK REPORT · " + (days <= 1 ? "today" : "last " + days + " days") + " · made " + DateTime.Now.ToString("dd/MM/yyyy HH:mm"));
            sb.AppendLine("Nova " + version + " · Windows " + Environment.OSVersion.Version + (Environment.Is64BitOperatingSystem ? " 64-bit" : " 32-bit"));
            sb.AppendLine("Settings: " + settings);
            sb.AppendLine("──────────────── SUMMARY ────────────────");
            if (r.Count == 0) sb.AppendLine("Nothing played in this period.");
            else
            {
                sb.AppendLine(Glance(days).Split('\n')[0]);
                var fails = r.Where(o => S(o, "res") == "FAIL").GroupBy(o => S(o, "t")).OrderByDescending(g => g.Count()).Take(15)
                    .Select(g => g.Key + " (" + g.Count() + "× " + S(g.First(), "note") + ")").ToList();
                if (fails.Count > 0) sb.AppendLine("Failed most : " + string.Join("\n              ", fails));
                var dev = r.Where(o => S(o, "res") == "DEVICE").GroupBy(o => S(o, "t"))
                    .Select(g => g.Key + " (" + g.Count() + "× " + S(g.First(), "note") + ")").ToList();
                if (dev.Count > 0) sb.AppendLine("Can't decode: " + string.Join("\n              ", dev));
                var slow = r.Where(o => S(o, "res") == "SLOW" || S(o, "act").Contains("slow")).Select(o => S(o, "t")).Distinct().Take(8).ToList();
                if (slow.Count > 0) sb.AppendLine("Line too slow: " + string.Join(" · ", slow));
                foreach (var l in ServerLines(r)) sb.AppendLine("Server      : " + l);
            }
            sb.AppendLine("──────────────── PLAYBACK (newest first) ─");
            // The same failure several times in a row is one block "×6".
            var blocks = new List<Tuple<JObject, int>>();
            foreach (var o in r.OrderByDescending(x => L(x, "at")))
            {
                var last = blocks.LastOrDefault();
                if (last != null && S(o, "res") != "OK" && S(last.Item1, "t") == S(o, "t") && S(last.Item1, "res") == S(o, "res") &&
                    L(last.Item1, "at") - L(o, "at") < 15 * 60000)
                    blocks[blocks.Count - 1] = Tuple.Create(last.Item1, last.Item2 + 1);
                else blocks.Add(Tuple.Create(o, 1));
            }
            foreach (var b in blocks)
            {
                var o = b.Item1;
                var res = (S(o, "res") + (b.Item2 > 1 ? " ×" + b.Item2 : "")).PadRight(6);
                var grp = S(o, "grp").Length > 0 ? " · " + S(o, "grp") : "";
                sb.AppendLine(DateTimeOffset.FromUnixTimeMilliseconds(L(o, "at")).LocalDateTime.ToString("dd/MM HH:mm") + " " + res + " " + S(o, "t") + " · " + S(o, "srv") + " · " + S(o, "k") + grp);
                var tech = new[] { S(o, "mode"), S(o, "cont"), S(o, "vid"), S(o, "dec") }.Where(x => x.Length > 0).ToList();
                if (tech.Count > 0) sb.AppendLine("   " + string.Join(" · ", tech));
                var parts = new List<string>();
                var start = L(o, "start");
                parts.Add(start >= 0 ? "start " + F(start / 1000.0) + " s" : "never started");
                if (D(o, "need") > 0 || D(o, "got") > 0) parts.Add("needs " + F(D(o, "need")) + " / got " + F(D(o, "got")) + " Mbit/s");
                parts.Add("watched " + Dur(L(o, "dur")));
                sb.AppendLine("   " + string.Join(" · ", parts));
                var line3 = new List<string>();
                if (L(o, "c0") >= 0) line3.Add("cushion " + L(o, "c0") + " s→" + L(o, "cmax") + " s (low " + Math.Max(0, L(o, "cmin")) + " s)");
                line3.Add("rebuffers " + L(o, "rb") + (L(o, "rb") > 0 ? " (" + L(o, "rbms") / 1000 + " s)" : ""));
                if (S(o, "act").Length > 0) line3.Add(S(o, "act"));
                sb.AppendLine("   " + string.Join(" · ", line3));
                if (S(o, "note").Length > 0) sb.AppendLine("   → " + S(o, "note"));
            }
            sb.AppendLine("──────────────── WATCHDOG / HEALTH ──────");
            var health = (watchdog ?? Enumerable.Empty<string>()).Take(80).ToList();
            if (health.Count == 0) sb.AppendLine("none");
            foreach (var h in health) sb.AppendLine(h.Replace('|', ' '));
            sb.AppendLine("──────────────── APP ────────────────────");
            var problems = ProblemFiles(days);
            if (problems.Count == 0) sb.AppendLine("No crash or forced close in this period.");
            foreach (var f in problems.Take(10))
            {
                try
                {
                    var lines = File.ReadAllLines(f);
                    sb.AppendLine(string.Join(" | ", lines.Take(4)));
                    foreach (var l in lines.Skip(4).Where(x => x.Contains("at Nova") || x.Contains("Exception")).Take(8)) sb.AppendLine("   " + l.Trim());
                }
                catch { }
            }
            return sb.ToString();
        }

        private static string Dur(long ms)
        {
            var m = ms / 60000;
            return m >= 60 ? m / 60 + " h " + m % 60 + " min" : m > 0 ? m + " min" : ms / 1000 + " s";
        }

        /// <summary>Writes the report to Downloads\NovaIPTV\Logs\nova-report-&lt;date&gt;.txt.</summary>
        public string SaveReport(string text)
        {
            var dir = LogsFolder();
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, "nova-report-" + DateTime.Now.ToString("yyyy-MM-dd-HHmm", CultureInfo.InvariantCulture) + ".txt");
            File.WriteAllText(path, text, new UTF8Encoding(true));
            return path;
        }

        // ------------------------------------------------------------------ last problem

        private List<string> ProblemFiles(int days)
        {
            try
            {
                var dir = LogsFolder();
                if (dir == null || !Directory.Exists(dir)) return new List<string>();
                var from = days <= 1 ? DateTime.Today : DateTime.Now.AddDays(-days);
                return new DirectoryInfo(dir).GetFiles("problem-*.txt").Where(f => f.LastWriteTime >= from)
                    .OrderByDescending(f => f.LastWriteTime).Select(f => f.FullName).ToList();
            }
            catch { return new List<string>(); }
        }

        /// <summary>Remembers why Nova closed or failed (shown in Settings › System › Last problem).</summary>
        public void Problem(string version, string text)
        {
            try
            {
                var full = "Nova IPTV " + version + " · " + DateTime.Now.ToString("dd/MM/yyyy HH:mm:ss") + "\n" + text;
                var dir = LogsFolder();
                if (dir == null) return;
                Directory.CreateDirectory(dir);
                File.WriteAllText(Path.Combine(dir, "problem-" + DateTime.Now.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture) + ".txt"),
                    full.Length > 12000 ? full.Substring(0, 12000) : full, new UTF8Encoding(true));
            }
            catch { }
        }

        /// <summary>The newest "problem" file's text, or null.</summary>
        public string LastProblem()
        {
            var f = ProblemFiles(3650).FirstOrDefault();
            try { return f == null ? null : File.ReadAllText(f); } catch { return null; }
        }

        public void ClearProblems()
        {
            foreach (var f in ProblemFiles(3650)) try { File.Delete(f); } catch { }
        }
    }
}
