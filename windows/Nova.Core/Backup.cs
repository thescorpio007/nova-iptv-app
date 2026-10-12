using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace Nova.Core
{
    /// <summary>
    /// .bak backups in the Android app's format, so one file works on the box and on the PC:
    /// { "app": "NovaIPTV", "version": 1, "created": ms, "prefs": { key: { "t": "s|i|l|f|b|ss", "v": value } } }.
    /// Windows writes the Android keys it shares (accounts, favorites, recent, order, hidden, PIN, resume positions,
    /// settings with the same meaning) plus its own Windows settings ("win_settings"), and keeps every Android
    /// setting it doesn't use, so a backup goes back to the box unchanged.
    /// </summary>
    public static class Backup
    {
        private const string AndroidDefaultAgent = "NovaIPTV/1.0 (Linux; Android)";

        private static JObject S(string v) { return new JObject { ["t"] = "s", ["v"] = v }; }
        private static JObject I(int v) { return new JObject { ["t"] = "i", ["v"] = v }; }
        private static JObject L(long v) { return new JObject { ["t"] = "l", ["v"] = v }; }
        private static JObject B(bool v) { return new JObject { ["t"] = "b", ["v"] = v }; }

        private static readonly JsonSerializerSettings Json = new JsonSerializerSettings
        {
            NullValueHandling = NullValueHandling.Ignore,
            ObjectCreationHandling = ObjectCreationHandling.Replace,
        };

        // ------------------------------------------------------------------ export

        public static string Export(Store store)
        {
            var s = store.Settings;
            var st = store.State;
            var prefs = new JObject();
            // Android settings Windows doesn't use come first; shared ones below overwrite them.
            foreach (var kv in st.AndroidExtras) prefs[kv.Key] = kv.Value.DeepClone();

            prefs["accounts"] = S(JsonConvert.SerializeObject(store.Accounts.Select(a => new
            {
                id = a.Id, name = a.Name ?? a.DisplayName, server = a.Server, username = a.Username, password = a.Password, fromList = a.FromList,
            })));
            prefs["favorites_v2"] = S(JsonConvert.SerializeObject(store.Favorites, Json));
            prefs["recent_v2"] = S(JsonConvert.SerializeObject(st.Recent, Json));
            prefs["org"] = S(JsonConvert.SerializeObject(st.Org));
            prefs["lists_v2"] = B(true);
            if (store.HasPin) { prefs["pin_hash"] = S(st.PinHash); prefs["pin_salt"] = S(st.PinSalt); }
            else { prefs.Remove("pin_hash"); prefs.Remove("pin_salt"); }
            foreach (var kv in st.Positions) prefs["pos_" + kv.Key] = L(kv.Value);

            prefs["sort_live"] = S(s.SortLive);
            prefs["sort_movie"] = S(s.SortMovie);
            prefs["sort_series"] = S(s.SortSeries);
            prefs["live_format"] = S(s.LiveFormat);
            prefs["cushion_seconds"] = I(s.CushionSeconds);
            prefs["live_disk_mb"] = I(s.LiveBufferMb);
            prefs["auto_skip"] = B(s.AutoSkip);
            prefs["skip_timeout"] = I(s.DeadSeconds);
            prefs["watchdog_mode"] = S(s.WatchdogMode);
            prefs["watchdog_other_account"] = B(s.WatchdogOtherAccount);
            prefs["subtitle_size"] = I(s.SubtitleSize);
            prefs["subtitle_color"] = S(s.SubtitleColor);
            prefs["subtitle_background"] = B(s.SubtitleBackground);
            prefs["live_logos"] = B(s.LiveLogos);
            prefs["poster_size"] = S(s.PosterSize);
            prefs["poster_titles"] = B(s.PosterTitles);
            prefs["poster_cols_tv"] = I(s.PosterColumns);
            prefs["tv_arrows"] = B(s.TvArrows);
            prefs["open_group_on_focus"] = B(s.OpenGroupOnFocus);
            prefs["clock_position"] = S(s.ClockPosition);
            prefs["live_row_size"] = S(s.LiveRowSize);
            prefs["live_now_line"] = B(s.LiveNowLine);
            prefs["overlay_transparency"] = I(s.OverlayTransparency);
            prefs["double_back_exit"] = B(s.DoubleBackExit);
            prefs["auto_play_last"] = B(s.AutoPlayLast);
            prefs["require_vpn"] = B(s.RequireVpn);
            prefs["number_keys"] = B(s.NumberKeys);
            prefs["dl_conn_servers"] = I(s.DlConnServers);
            prefs["dl_conn_links"] = I(s.DlConnLinks);
            prefs["dl_speed_servers"] = I(s.DlSpeedServers);
            prefs["dl_speed_links"] = I(s.DlSpeedLinks);
            if (s.UserAgent != Store.DefaultUserAgent) prefs["user_agent"] = S(s.UserAgent);

            prefs["recent_links"] = S(string.Join("\n", st.RecentLinks.Take(15)));
            foreach (var kv in st.LinkHeaders)
                prefs["hdr_" + kv.Key] = S(string.Join("\n", kv.Value.Select(h => h.Key + ": " + h.Value)));
            if (st.AccountsLink != null) prefs["accounts_link"] = S(st.AccountsLink);
            if (st.AccountsLinkPassword != null) prefs["accounts_link_pw"] = S(st.AccountsLinkPassword);
            if (st.AccountsLinkChecked > 0) prefs["accounts_link_checked"] = L(st.AccountsLinkChecked);
            if (st.AccountsLinkNote != null) prefs["accounts_link_note"] = S(st.AccountsLinkNote);
            if (st.LastBackupLink != null) prefs["last_backup_link"] = S(st.LastBackupLink);
            if (st.LastBackupLinkAt > 0) prefs["last_backup_link_at"] = L(st.LastBackupLinkAt);
            prefs["search_kind"] = S(st.SearchKind);
            if (st.SearchAccount != null) prefs["search_account"] = S(st.SearchAccount); else prefs.Remove("search_account");

            // Windows-only settings (the Android app keeps them untouched).
            var win = s.Copy();
            win.WindowWidth = 0; win.WindowHeight = 0;
            prefs["win_settings"] = S(JsonConvert.SerializeObject(win));
            prefs["win_key_map"] = S(JsonConvert.SerializeObject(st.KeyMap));

            var root = new JObject
            {
                ["app"] = "NovaIPTV",
                ["version"] = 1,
                ["created"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                ["from"] = "windows",
                ["prefs"] = prefs,
            };
            return root.ToString(Formatting.Indented);
        }

        // ------------------------------------------------------------------ import

        /// <summary>What a backup contains, read without changing anything.</summary>
        public class Contents
        {
            public List<Account> Accounts;
            public Settings Settings;
            public List<SavedItem> Favorites;
            public UserState State;
            public bool FromAndroid;
            public DateTime? Created;

            public string Summary
            {
                get
                {
                    return Accounts.Count + (Accounts.Count == 1 ? " account" : " accounts") + ", " + Favorites.Count + " favorites, " +
                           (State.Org.HiddenGroups.Count + State.Org.HiddenItems.Count) + " hidden items" +
                           (FromAndroid ? " · made on Android" : "") + (Created.HasValue ? " · " + Created.Value.ToString("d MMM yyyy HH:mm") : "");
                }
            }
        }

        /// <summary>Reads a backup. Throws NovaException when it isn't a Nova backup.</summary>
        public static Contents Read(string text, Settings current)
        {
            JObject root;
            try { root = JObject.Parse(text); }
            catch { throw new NovaException("This file is not a Nova IPTV backup."); }
            if ((string)root["app"] != "NovaIPTV") throw new NovaException("This file is not a Nova IPTV backup.");
            var prefs = root["prefs"] as JObject;
            if (prefs == null) throw new NovaException("The backup is empty.");

            var c = new Contents
            {
                FromAndroid = (string)root["from"] != "windows",
                Created = root["created"] != null ? DateTimeOffset.FromUnixTimeMilliseconds((long)root["created"]).LocalDateTime : (DateTime?)null,
                State = new UserState().Fix(),
            };
            var used = new HashSet<string>();
            Func<string, JToken> v = key =>
            {
                var e = prefs[key] as JObject;
                if (e == null) return null;
                used.Add(key);
                return e["v"];
            };
            Func<string, string> str = key => { var t = v(key); return t == null || t.Type == JTokenType.Null ? null : (string)t; };
            Func<string, int?> num = key => { var t = v(key); return t == null ? (int?)null : (int)(double)t; };
            Func<string, bool?> flag = key => { var t = v(key); return t == null ? (bool?)null : (bool)t; };
            Func<string, long?> lng = key => { var t = v(key); return t == null ? (long?)null : (long)(double)t; };

            // Windows settings first (from a PC backup), then the shared ones on top.
            var s = current != null ? current.Copy() : new Settings();
            var winText = str("win_settings");
            if (winText != null)
            {
                try
                {
                    var keepW = s.WindowWidth; var keepH = s.WindowHeight; var keepM = s.WindowMaximized;
                    s = JsonConvert.DeserializeObject<Settings>(winText) ?? s;
                    s.WindowWidth = keepW; s.WindowHeight = keepH; s.WindowMaximized = keepM;
                }
                catch { }
            }
            var keyMap = str("win_key_map");
            if (keyMap != null)
            {
                try { c.State.KeyMap = JsonConvert.DeserializeObject<Dictionary<string, List<string>>>(keyMap) ?? c.State.KeyMap; } catch { }
            }

            // Accounts, favorites, recent, organisation.
            c.Accounts = new List<Account>();
            var acc = str("accounts");
            if (acc != null)
            {
                try { c.Accounts = JsonConvert.DeserializeObject<List<Account>>(acc) ?? c.Accounts; }
                catch { throw new NovaException("The accounts in this backup can't be read."); }
            }
            c.Accounts.RemoveAll(a => a == null || string.IsNullOrEmpty(a.Id));
            c.Favorites = ReadList(str("favorites_v2"));
            c.State.Recent = ReadList(str("recent_v2"));
            var org = str("org");
            if (org != null) { try { c.State.Org = (JsonConvert.DeserializeObject<OrgData>(org) ?? new OrgData()).Fix(); } catch { } }
            c.State.PinHash = str("pin_hash");
            c.State.PinSalt = str("pin_salt");

            foreach (var p in prefs.Properties().Where(p => p.Name.StartsWith("pos_")).ToList())
            {
                var ms = lng(p.Name);
                if (ms.HasValue && ms.Value > 0) c.State.Positions[p.Name.Substring(4)] = ms.Value;
            }
            foreach (var p in prefs.Properties().Where(p => p.Name.StartsWith("hdr_")).ToList())
            {
                var lines = str(p.Name) ?? "";
                var d = new Dictionary<string, string>();
                foreach (var l in lines.Split('\n'))
                {
                    var colon = l.IndexOf(':');
                    if (colon > 0 && l.Substring(colon + 1).Trim().Length > 0) d[l.Substring(0, colon).Trim()] = l.Substring(colon + 1).Trim();
                }
                if (d.Count > 0) c.State.LinkHeaders[p.Name.Substring(4)] = d;
            }

            // Shared settings.
            s.SortLive = str("sort_live") ?? s.SortLive;
            s.SortMovie = str("sort_movie") ?? s.SortMovie;
            s.SortSeries = str("sort_series") ?? s.SortSeries;
            s.LiveFormat = str("live_format") ?? s.LiveFormat;
            s.CushionSeconds = num("cushion_seconds") ?? s.CushionSeconds;
            s.LiveBufferMb = num("live_disk_mb") ?? s.LiveBufferMb;
            s.AutoSkip = flag("auto_skip") ?? s.AutoSkip;
            s.DeadSeconds = num("skip_timeout") ?? s.DeadSeconds;
            s.WatchdogMode = str("watchdog_mode") ?? s.WatchdogMode;
            s.WatchdogOtherAccount = flag("watchdog_other_account") ?? s.WatchdogOtherAccount;
            s.SubtitleSize = num("subtitle_size") ?? s.SubtitleSize;
            s.SubtitleColor = str("subtitle_color") ?? s.SubtitleColor;
            s.SubtitleBackground = flag("subtitle_background") ?? s.SubtitleBackground;
            s.LiveLogos = flag("live_logos") ?? s.LiveLogos;
            s.PosterSize = str("poster_size") ?? s.PosterSize;
            s.PosterTitles = flag("poster_titles") ?? s.PosterTitles;
            s.PosterColumns = num("poster_cols_tv") ?? s.PosterColumns;
            s.TvArrows = flag("tv_arrows") ?? s.TvArrows;
            s.OpenGroupOnFocus = flag("open_group_on_focus") ?? s.OpenGroupOnFocus;
            s.ClockPosition = str("clock_position") ?? s.ClockPosition;
            s.LiveRowSize = str("live_row_size") ?? s.LiveRowSize;
            s.LiveNowLine = flag("live_now_line") ?? s.LiveNowLine;
            s.OverlayTransparency = num("overlay_transparency") ?? s.OverlayTransparency;
            s.DoubleBackExit = flag("double_back_exit") ?? s.DoubleBackExit;
            s.AutoPlayLast = flag("auto_play_last") ?? s.AutoPlayLast;
            s.RequireVpn = flag("require_vpn") ?? s.RequireVpn;
            s.NumberKeys = flag("number_keys") ?? s.NumberKeys;
            s.DlConnServers = num("dl_conn_servers") ?? s.DlConnServers;
            s.DlConnLinks = num("dl_conn_links") ?? s.DlConnLinks;
            s.DlSpeedServers = num("dl_speed_servers") ?? s.DlSpeedServers;
            s.DlSpeedLinks = num("dl_speed_links") ?? s.DlSpeedLinks;
            var ua = str("user_agent");
            if (!string.IsNullOrWhiteSpace(ua) && ua != AndroidDefaultAgent) s.UserAgent = ua;
            c.Settings = s;

            var links = str("recent_links");
            if (links != null) c.State.RecentLinks = links.Split('\n').Where(l => l.Trim().Length > 0).ToList();
            c.State.AccountsLink = str("accounts_link");
            c.State.AccountsLinkPassword = str("accounts_link_pw");
            c.State.AccountsLinkChecked = lng("accounts_link_checked") ?? 0;
            c.State.AccountsLinkNote = str("accounts_link_note");
            c.State.LastBackupLink = str("last_backup_link");
            c.State.LastBackupLinkAt = lng("last_backup_link_at") ?? 0;
            c.State.SearchKind = str("search_kind") ?? "all";
            c.State.SearchAccount = str("search_account");
            used.Add("lists_v2");

            // Everything else (Android-only settings) is kept as it is for the way back.
            foreach (var p in prefs.Properties())
                if (!used.Contains(p.Name) && p.Value is JObject) c.State.AndroidExtras[p.Name] = (JObject)p.Value.DeepClone();
            return c;
        }

        private static List<SavedItem> ReadList(string json)
        {
            if (json == null) return new List<SavedItem>();
            try
            {
                var list = JsonConvert.DeserializeObject<List<SavedItem>>(json) ?? new List<SavedItem>();
                list.RemoveAll(i => i == null || i.Id == null);
                foreach (var i in list) if (i.AccountId == null) i.AccountId = "";
                return list;
            }
            catch { return new List<SavedItem>(); }
        }

        /// <summary>Opens a protected backup (password needed) or a plain one.</summary>
        public static string Unlock(string text, string password)
        {
            if (!NovaCrypto.IsProtected(text)) return text;
            if (string.IsNullOrEmpty(password)) throw new NovaException("This backup is protected: enter its password.");
            return NovaCrypto.Decrypt(text, password);
        }

        public static string FileName(bool isProtected)
        {
            return "NovaIPTV-" + DateTime.Now.ToString("yyyy-MM-dd-HHmm", CultureInfo.InvariantCulture) + (isProtected ? "-protected" : "") + ".bak";
        }
    }
}
