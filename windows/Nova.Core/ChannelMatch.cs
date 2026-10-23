using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace Nova.Core
{
    /// <summary>
    /// "≈ Similar": finds the same channel under another name or group — "|FR| TF1 HD", "FR_TF1 FHD HEVC" and "|4K| TF1 4K"
    /// are all "tf1" — keeping to the same country (|FR|, FR-, FR_, [US]…). Same rules as the box.
    /// </summary>
    public static class ChannelMatch
    {
        // Leading tags: "|FR|", "[US]", "FR|", "FR:", "FR-KIDS:", "BE-NL|", "Ca -", "FR-TF1", "FR_TF1", "|4K| FR|"…
        private static readonly Regex Lead = new Regex(@"^(\s*(\|[^|]{1,16}\||\[[^\]]{1,16}\]|[A-Za-z]{2,6}(?:-[A-Za-z]{2,6})?\s*[|:]|[A-Za-z]{2,3}\s+-\s|[A-Za-z]{2,3}[-_](?=\S)))+", RegexOptions.Compiled);
        private static readonly Regex TailTag = new Regex(@"(\[[^\]]{0,16}\]|\([^)]{0,16}\))\s*$", RegexOptions.Compiled);
        private static readonly Regex Fancy = new Regex("[ɐ-˿ᴀ-ᶿ⁰-₟꜠-ꟿ]+", RegexOptions.Compiled);
        private static readonly Regex Split = new Regex(@"[^\p{L}\p{N}]+", RegexOptions.Compiled);
        private static readonly Regex Tokens = new Regex(@"[^\p{L}\p{N}+]+", RegexOptions.Compiled);
        private static readonly Regex Letters = new Regex("[^a-z]+", RegexOptions.Compiled);

        private static readonly HashSet<string> QualityWords = new HashSet<string>
        {
            "4k", "8k", "uhd", "fhd", "hd", "sd", "lq", "hq", "hevc", "h265", "h264", "x265", "raw", "backup", "hdr", "dv",
            "50fps", "60fps", "fps", "1080p", "720p", "2160p", "1080", "720", "2160", "multi", "vip", "ott", "ultra", "plus4k",
        };

        /// <summary>Words in the leading tags that are not a country / language ("|4K|", "|VIP|", "FR-KIDS"…).</summary>
        private static readonly HashSet<string> NotCountry = new HashSet<string>
        {
            "4k", "8k", "uhd", "fhd", "hd", "sd", "vip", "ott", "tv", "raw", "hevc", "kids", "enf", "bein", "the", "vod", "ppv", "new",
        };

        private static string Clean(string name)
        {
            var n = Fancy.Replace(name ?? "", " ").Replace(' ', ' ');
            for (int i = 0; i < 3; i++) n = TailTag.Replace(n, " ").TrimEnd();
            return n;
        }

        private static string NoAccents(string t)
        {
            if (!t.Any(c => c > 127)) return t;
            var d = t.Normalize(NormalizationForm.FormD);
            var sb = new StringBuilder(d.Length);
            foreach (var c in d) if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) sb.Append(c);
            return sb.ToString();
        }

        /// <summary>"|FR| TF1 HD", "FR_TF1 FHD HEVC", "|4K| TF1 4K" → "tf1". Numbers kept exactly ("tf1 1" for TF1 +1).</summary>
        public static string Key(string name)
        {
            var n = Lead.Replace(Clean(name), "");
            n = NoAccents(n);
            var toks = Tokens.Split(n.ToLowerInvariant()).Where(t => t.Trim().Length > 0).ToList();
            var outp = new List<string>();
            for (int i = 0; i < toks.Count; i++)
            {
                var t = toks[i];
                if (t == "full" && i + 1 < toks.Count && (toks[i + 1] == "hd" || toks[i + 1] == "fhd")) { i++; continue; }
                if (QualityWords.Contains(t) || t == "+") continue;
                var x = t.Replace("+", "");
                if (x.Trim().Length > 0) outp.Add(x);
            }
            return string.Join(" ", outp);
        }

        /// <summary>The country / language of the leading tag ("fr" for "|FR|", "FR-KIDS:", "FR_"), null when there is none.</summary>
        public static string CountryOf(string name)
        {
            var m = Lead.Match(Clean(name));
            if (!m.Success || m.Index != 0) return null;
            return Letters.Split(m.Value.ToLowerInvariant()).FirstOrDefault(w => w.Length >= 2 && w.Length <= 3 && !NotCountry.Contains(w));
        }

        private static bool SameCountry(string a, string b) { return a == null || b == null || a == b; }

        /// <summary>3 = 4K/UHD, 2 = FHD/1080, 1 = HD/720, 0 = SD, -1 = not written in the name.</summary>
        public static int QualityOf(string name)
        {
            name = name ?? "";
            if (name.Contains("ᵁᴴᴰ") || name.Contains("⁴ᴷ")) return 3;
            if (name.Contains("ᶠᴴᴰ") || name.Contains("ꜰʜᴅ")) return 2;
            var words = new HashSet<string>(Split.Split(name.ToLowerInvariant()));
            if (words.Overlaps(new[] { "4k", "8k", "uhd", "2160", "2160p" })) return 3;
            if (words.Overlaps(new[] { "fhd", "1080", "1080p" })) return 2;
            if (name.Contains("ᴴᴰ") || name.Contains("ʜᴅ") || words.Overlaps(new[] { "hd", "720", "720p" })) return 1;
            if (words.Overlaps(new[] { "sd", "lq" })) return 0;
            return -1;
        }

        public static string QualityLabel(int q) { return q == 3 ? "4K" : q == 2 ? "FHD" : q == 1 ? "HD" : q == 0 ? "SD" : ""; }

        public sealed class Similar
        {
            public LiveChannel Channel;
            public string Group;
            public int Quality;
            /// <summary>A lighter version (needs less speed than the one watched).</summary>
            public bool Lighter;
        }

        /// <summary>
        /// The same channel in the other groups of the same server, same country: lighter versions (FHD, HD, SD) first.
        /// </summary>
        public static List<Similar> OnSameServer(LiveChannel watched, IList<LiveChannel> all, IDictionary<string, string> groupNames)
        {
            var want = Key(watched.Name);
            if (want.Length == 0) return new List<Similar>();
            var wantCountry = CountryOf(watched.Name);
            var q0 = QualityOf(watched.Name);
            if (q0 < 0) q0 = 2;
            var found = new List<Similar>();
            var seen = new HashSet<string>();
            foreach (var c in all)
            {
                if (c.StreamId == watched.StreamId || !seen.Add(c.StreamId)) continue;
                if (Key(c.Name) != want || !SameCountry(CountryOf(c.Name), wantCountry)) continue;
                var q = QualityOf(c.Name);
                string g;
                found.Add(new Similar { Channel = c, Group = groupNames != null && groupNames.TryGetValue(c.CategoryId ?? "", out g) ? g : "", Quality = q, Lighter = q >= 0 && q < q0 });
            }
            return found.Where(s => s.Lighter).OrderByDescending(s => s.Quality)
                .Concat(found.Where(s => !s.Lighter).OrderBy(s => s.Quality < 0 ? q0 : s.Quality)).ToList();
        }

        /// <summary>The same channel (same cleaned name and country) in another account's list: the closest quality, or null.</summary>
        public static LiveChannel SameElsewhere(LiveChannel watched, IList<LiveChannel> other)
        {
            var want = Key(watched.Name);
            if (want.Length == 0) return null;
            var wantCountry = CountryOf(watched.Name);
            var q0 = QualityOf(watched.Name);
            if (q0 < 0) q0 = 2;
            return other.Where(c => Key(c.Name) == want && SameCountry(CountryOf(c.Name), wantCountry))
                .OrderBy(c => { var q = QualityOf(c.Name); return Math.Abs((q < 0 ? 2 : q) - q0); })
                .FirstOrDefault();
        }
    }
}
