using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using Org.BouncyCastle.Crypto.Engines;
using Org.BouncyCastle.Crypto.Modes;
using Org.BouncyCastle.Crypto.Parameters;

namespace Nova.Core
{
    /// <summary>
    /// Password protection for backups and accounts lists, identical to the Android app:
    /// AES-256-GCM with a key from the password (PBKDF2-SHA256, 120 000 rounds).
    /// A protected file is text: "NOVAENC1:" + Base64(salt[16] + iv[12] + ciphertext + tag[16]).
    /// </summary>
    public static class NovaCrypto
    {
        private const string Prefix = "NOVAENC1:";
        private const int Rounds = 120000;

        public static bool IsProtected(string text)
        {
            return text != null && text.TrimStart().StartsWith(Prefix, StringComparison.Ordinal);
        }

        private static byte[] Key(string password, byte[] salt)
        {
            using (var kdf = new Rfc2898DeriveBytes(Encoding.UTF8.GetBytes(password), salt, Rounds, HashAlgorithmName.SHA256))
                return kdf.GetBytes(32);
        }

        public static string Encrypt(string text, string password)
        {
            var salt = new byte[16];
            var iv = new byte[12];
            using (var rnd = RandomNumberGenerator.Create()) { rnd.GetBytes(salt); rnd.GetBytes(iv); }
            var gcm = new GcmBlockCipher(new AesEngine());
            gcm.Init(true, new AeadParameters(new KeyParameter(Key(password, salt)), 128, iv));
            var plain = Encoding.UTF8.GetBytes(text);
            var cipher = new byte[gcm.GetOutputSize(plain.Length)];
            var n = gcm.ProcessBytes(plain, 0, plain.Length, cipher, 0);
            gcm.DoFinal(cipher, n);
            var all = new byte[salt.Length + iv.Length + cipher.Length];
            Buffer.BlockCopy(salt, 0, all, 0, 16);
            Buffer.BlockCopy(iv, 0, all, 16, 12);
            Buffer.BlockCopy(cipher, 0, all, 28, cipher.Length);
            return Prefix + Convert.ToBase64String(all);
        }

        /// <summary>Throws NovaException("Wrong password") when the password doesn't open it.</summary>
        public static string Decrypt(string text, string password)
        {
            byte[] raw;
            try { raw = Convert.FromBase64String(text.Trim().Substring(Prefix.Length).Trim()); }
            catch { throw new NovaException("This protected file is damaged"); }
            if (raw.Length < 29) throw new NovaException("This protected file is damaged");
            var salt = raw.Take(16).ToArray();
            var iv = raw.Skip(16).Take(12).ToArray();
            var cipher = raw.Skip(28).ToArray();
            try
            {
                var gcm = new GcmBlockCipher(new AesEngine());
                gcm.Init(false, new AeadParameters(new KeyParameter(Key(password ?? "", salt)), 128, iv));
                var plain = new byte[gcm.GetOutputSize(cipher.Length)];
                var n = gcm.ProcessBytes(cipher, 0, cipher.Length, plain, 0);
                n += gcm.DoFinal(plain, n);
                return Encoding.UTF8.GetString(plain, 0, n);
            }
            catch (Exception)
            {
                throw new NovaException("Wrong password");
            }
        }
    }

    /// <summary>Downloads a small text file (backup, accounts list) from any web link, including share pages.</summary>
    public static class LinkFetcher
    {
        private const int MaxBytes = 20 * 1024 * 1024;

        private static string B64Url(string s)
        {
            return Convert.ToBase64String(Encoding.UTF8.GetBytes(s)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        }

        /// <summary>Links to try: the share page's direct download first, then the link itself.</summary>
        public static List<string> Candidates(string link)
        {
            var url = link.Trim();
            var lower = url.ToLowerInvariant();
            var outList = new List<string>();
            if (lower.Contains("1drv.ms") || lower.Contains("onedrive.live.com") || lower.Contains("sharepoint.com"))
            {
                outList.Add("https://api.onedrive.com/v1.0/shares/u!" + B64Url(url) + "/root/content");
                outList.Add(url.Contains("?") ? url + "&download=1" : url + "?download=1");
            }
            else if (lower.Contains("drive.google.com") || lower.Contains("docs.google.com"))
            {
                var m = Regex.Match(url, @"/d/([\w-]{10,})");
                if (!m.Success) m = Regex.Match(url, @"[?&]id=([\w-]{10,})");
                if (m.Success) outList.Add("https://drive.google.com/uc?export=download&id=" + m.Groups[1].Value);
            }
            else if (lower.Contains("dropbox.com"))
            {
                if (url.Contains("dl=0")) outList.Add(url.Replace("dl=0", "dl=1"));
                else if (url.Contains("dl=1")) outList.Add(url);
                else outList.Add(url.Contains("?") ? url + "&dl=1" : url + "?dl=1");
            }
            outList.Add(url);
            return outList.Distinct().ToList();
        }

        private static bool IsOneDrive(string link)
        {
            var l = link.ToLowerInvariant();
            return l.Contains("1drv.ms") || l.Contains("onedrive.live.com");
        }

        private static HttpClient NewClient()
        {
            return new HttpClient(new HttpClientHandler { AllowAutoRedirect = true, AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate })
            { Timeout = TimeSpan.FromSeconds(40) };
        }

        /// <summary>Personal OneDrive ("1drv.ms/u/c/…"): guest token like OneDrive's own page, then the direct address.</summary>
        private static async Task<string> OneDriveDirect(HttpClient http, string link, string userAgent)
        {
            var tokenReq = new HttpRequestMessage(HttpMethod.Post, "https://api-badgerp.svc.ms/v1.0/token")
            {
                Content = new StringContent("{\"appId\":\"5cbed6ac-a083-4e14-b191-b4ba07653de2\"}", Encoding.UTF8, "application/json"),
            };
            tokenReq.Headers.TryAddWithoutValidation("User-Agent", userAgent);
            string token;
            using (var r = await http.SendAsync(tokenReq).ConfigureAwait(false))
            {
                if (!r.IsSuccessStatusCode) throw new IOException("OneDrive guest access answered HTTP " + (int)r.StatusCode);
                token = (string)JObject.Parse(await r.Content.ReadAsStringAsync().ConfigureAwait(false))["token"];
            }
            var meta = new HttpRequestMessage(HttpMethod.Get, "https://my.microsoftpersonalcontent.com/_api/v2.0/shares/u!" + B64Url(link.Trim()) +
                "/driveitem?$select=id,name,size,@content.downloadUrl");
            meta.Headers.TryAddWithoutValidation("User-Agent", userAgent);
            meta.Headers.TryAddWithoutValidation("Authorization", "Badger " + token);
            meta.Headers.TryAddWithoutValidation("Prefer", "autoredeem");
            using (var r = await http.SendAsync(meta).ConfigureAwait(false))
            {
                if (r.StatusCode == HttpStatusCode.NotFound) throw new IOException("OneDrive says this file doesn't exist (or isn't shared)");
                if (!r.IsSuccessStatusCode) throw new IOException("OneDrive answered HTTP " + (int)r.StatusCode);
                var json = JObject.Parse(await r.Content.ReadAsStringAsync().ConfigureAwait(false));
                if (json["folder"] != null) throw new IOException("this is a link to a folder: share the file itself");
                var u = (string)json["@content.downloadUrl"];
                if (string.IsNullOrWhiteSpace(u)) throw new IOException("OneDrive gave no download address");
                return u;
            }
        }

        /// <summary>Downloads the text. Throws NovaException with a clear message.</summary>
        public static async Task<string> FetchText(string link, string userAgent)
        {
            var t = (link ?? "").Trim();
            if (!t.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !t.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
                throw new NovaException("This is not a web link (it must start with http:// or https://)");
            Exception last = null;
            var tries = new List<string>();
            using (var http = NewClient())
            {
                if (IsOneDrive(t))
                {
                    try { tries.Add(await OneDriveDirect(http, t, userAgent).ConfigureAwait(false)); }
                    catch (Exception e) { last = e; }
                }
                tries.AddRange(Candidates(t));
                foreach (var u in tries.Distinct())
                {
                    try
                    {
                        var req = new HttpRequestMessage(HttpMethod.Get, u);
                        req.Headers.TryAddWithoutValidation("User-Agent", userAgent);
                        using (var r = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false))
                        {
                            if (!r.IsSuccessStatusCode) throw new IOException("the server answered HTTP " + (int)r.StatusCode);
                            using (var s = await r.Content.ReadAsStreamAsync().ConfigureAwait(false))
                            using (var ms = new MemoryStream())
                            {
                                var buf = new byte[64 * 1024];
                                int n;
                                while ((n = s.Read(buf, 0, buf.Length)) > 0)
                                {
                                    ms.Write(buf, 0, n);
                                    if (ms.Length > MaxBytes) throw new IOException("the file is too big");
                                }
                                var text = Encoding.UTF8.GetString(ms.ToArray()).TrimStart('﻿');
                                if (text.TrimStart().StartsWith("<")) throw new IOException("the link opens a web page, not the file");
                                return text;
                            }
                        }
                    }
                    catch (Exception e) { last = e; }
                }
            }
            throw new NovaException("Could not download the file: " + (last != null ? last.Message : "unknown error") +
                ". Check that the link is shared as “anyone with the link”.");
        }
    }

    /// <summary>One account read from your accounts list.</summary>
    public class ListedAccount
    {
        public string Name { get; set; }
        public string Server { get; set; }
        public string Username { get; set; }
        public string Password { get; set; }
    }

    /// <summary>
    /// Accounts list (same format as Android), a plain text file you keep up to date:
    ///   Name | server | username | password
    ///   Name | http://server:port/get.php?username=…&amp;password=…
    ///   # lines starting with # are notes
    /// </summary>
    public static class AccountsList
    {
        public static List<ListedAccount> Parse(string text)
        {
            var result = new List<ListedAccount>();
            foreach (var raw in (text ?? "").Split('\n'))
            {
                var line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;
                var parts = line.Split('|').Select(p => p.Trim()).ToArray();
                ListedAccount a = null;
                string srv, u, p;
                if (parts.Length >= 4 && parts[1].Length > 0 && parts[2].Length > 0 && parts[3].Length > 0)
                    a = new ListedAccount { Name = parts[0].Length > 0 ? parts[0] : parts[1], Server = XtreamApi.NormalizeServer(parts[1]), Username = parts[2], Password = parts[3] };
                else if (parts.Length >= 2 && XtreamApi.TryParseLink(parts[1], out srv, out u, out p))
                    a = new ListedAccount { Name = parts[0].Length > 0 ? parts[0] : srv, Server = XtreamApi.NormalizeServer(srv), Username = u, Password = p };
                else if (parts.Length == 1 && XtreamApi.TryParseLink(parts[0], out srv, out u, out p))
                {
                    var host = srv.Substring(srv.IndexOf("://", StringComparison.Ordinal) + 3);
                    a = new ListedAccount { Name = host, Server = XtreamApi.NormalizeServer(srv), Username = u, Password = p };
                }
                if (a != null && result.All(x => !string.Equals(x.Name, a.Name, StringComparison.OrdinalIgnoreCase))) result.Add(a);
            }
            return result;
        }

        public static string Write(IEnumerable<Account> accounts)
        {
            return "# Nova IPTV accounts — one per line: Name | server | username | password\n" +
                   string.Join("\n", accounts.Select(a => a.DisplayName + " | " + a.Server + " | " + a.Username + " | " + a.Password)) + "\n";
        }

        /// <summary>
        /// Applies the list (same rules as Android): same name = same account, updated in place;
        /// accounts typed by hand stay; accounts that came from the list and left it are removed.
        /// Returns the new list and a short summary.
        /// </summary>
        public static List<Account> Apply(List<Account> before, List<ListedAccount> list, out string summary)
        {
            var changes = new List<string>();
            var result = new List<Account>();
            foreach (var l in list)
            {
                var old = before.FirstOrDefault(b => string.Equals(b.DisplayName, l.Name, StringComparison.OrdinalIgnoreCase) ||
                                                     string.Equals(b.Name, l.Name, StringComparison.OrdinalIgnoreCase));
                if (old == null)
                {
                    result.Add(new Account { Id = Store.NewId(), Name = l.Name, Server = l.Server, Username = l.Username, Password = l.Password, FromList = true });
                    changes.Add(l.Name + ": added");
                }
                else
                {
                    var n = old.Copy();
                    n.Server = l.Server; n.Username = l.Username; n.Password = l.Password; n.FromList = true;
                    if (n.Server != old.Server) changes.Add(l.Name + ": server address updated");
                    else if (n.Username != old.Username) changes.Add(l.Name + ": username updated");
                    else if (n.Password != old.Password) changes.Add(l.Name + ": password updated");
                    result.Add(n);
                }
            }
            var listed = new HashSet<string>(list.Select(l => l.Name.ToLowerInvariant()));
            foreach (var a in before)
            {
                if (listed.Contains((a.Name ?? "").ToLowerInvariant()) || listed.Contains(a.DisplayName.ToLowerInvariant())) continue;
                if (a.FromList) changes.Add(a.DisplayName + ": removed (no longer in your list)");
                else result.Add(a);
            }
            // Keep the original order, new ones at the end.
            var ordered = before.Select(b => result.FirstOrDefault(r => r.Id == b.Id)).Where(x => x != null).ToList();
            ordered.AddRange(result.Where(r => before.All(b => b.Id != r.Id)));
            summary = changes.Count == 0 ? "Up to date" : string.Join(" · ", changes);
            return ordered;
        }
    }
}

namespace Nova.Core
{
    /// <summary>One entry of an M3U playlist (a list of channels or videos).</summary>
    public class M3uEntry
    {
        public string Name { get; set; }
        public string Url { get; set; }
        public string Logo { get; set; }
        public string Group { get; set; }
    }

    public static class M3uPlaylist
    {
        public static List<M3uEntry> Parse(string text, string baseUrl)
        {
            var list = new List<M3uEntry>();
            Uri baseUri;
            Uri.TryCreate(baseUrl ?? "", UriKind.Absolute, out baseUri);
            M3uEntry pending = null;
            foreach (var raw in (text ?? "").Replace("\r", "").Split('\n'))
            {
                var line = raw.Trim();
                if (line.Length == 0) continue;
                if (line.StartsWith("#EXTINF", StringComparison.OrdinalIgnoreCase))
                {
                    var comma = line.LastIndexOf(',');
                    pending = new M3uEntry
                    {
                        Name = comma >= 0 ? line.Substring(comma + 1).Trim() : "Video",
                        Logo = AttrOf(line, "tvg-logo"),
                        Group = AttrOf(line, "group-title"),
                    };
                }
                else if (!line.StartsWith("#"))
                {
                    Uri u;
                    var url = baseUri != null && Uri.TryCreate(baseUri, line, out u) ? u.AbsoluteUri : line;
                    var e = pending ?? new M3uEntry { Name = System.IO.Path.GetFileName(line.Split('?')[0]) };
                    e.Url = url;
                    if (string.IsNullOrWhiteSpace(e.Name)) e.Name = "Video " + (list.Count + 1);
                    list.Add(e);
                    pending = null;
                }
            }
            return list;
        }

        private static string AttrOf(string line, string name)
        {
            var m = System.Text.RegularExpressions.Regex.Match(line, name + "=\"([^\"]*)\"", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
            return m.Success && m.Groups[1].Value.Length > 0 ? m.Groups[1].Value : null;
        }
    }
}
