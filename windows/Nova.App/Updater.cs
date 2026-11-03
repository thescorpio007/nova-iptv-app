using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;

namespace Nova.App
{
    /// <summary>
    /// Updates from GitHub Releases (tags windows-build-N). Before anything runs:
    ///  1. SHA256SUMS.txt must carry a valid signature (SHA256SUMS.sig) made with Nova's own signing key (the Android key, built in here),
    ///  2. the downloaded Setup.exe (or Portable.zip) must match its SHA-256 line.
    /// Checked once a day and from Settings › Check for updates; the user clicks once to install.
    /// </summary>
    public static class Updater
    {
        private const string Repo = "thescorpio007/nova-iptv-app";
        private const string TagPrefix = "windows-build-";
        // Public half of Nova's signing key (RSA 2048, exponent 65537). The private half never leaves GitHub's secrets.
        private const string Modulus = "20P0edgKRjQQteFuPAp8DpsYWaH2nR48cwZmvqaNsfQZUVjQMQKW0KkxsiZR4UWA9dV6DlUp1BFBZnUuE/dar8vNWJV9DgdK2LL/NZb7rJa/qpm+5ZVbVpHv7lRnfXhO4oKU8fOHl3oAGu39+Lf5KAdNqK7BB9kIQSoEkRyn5vFs/AA6LPlO4041lkWAVp6Yo2AtPXWmLV41i9LtIbat/COHO0vzqrdqngPeZIerL++psauBWNpeYypStbcbzqT8CJ/B1N7xrF/ni2LHmVEGh3Zgk6yoxiMVKr1tGNvFuUKPFIRbTBvAPijikw+r6OCK8BmHj+ltVadkeilO8wdGrQ==";

        public class Ready { public int Build; public string File; public string Notes; }

        private static string StateFile { get { return Path.Combine(App.Store.DataFolder, "update.json"); } }
        private static string Folder { get { return Path.Combine(App.Store.CacheFolder, "update"); } }
        public static bool Portable { get { return File.Exists(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "portable.txt")); } }

        public static int Current { get { return Assembly.GetExecutingAssembly().GetName().Version.Build; } }

        private static JObject Load() { try { return JObject.Parse(File.ReadAllText(StateFile)); } catch { return new JObject(); } }
        private static void Save(JObject o) { try { File.WriteAllText(StateFile, o.ToString()); } catch { } }

        public static string Status { get { return (string)Load()["status"] ?? ""; } }

        public static Ready Waiting()
        {
            var o = Load();
            var b = (int?)o["readyBuild"] ?? 0; var f = (string)o["readyFile"] ?? "";
            if (b <= Current || !File.Exists(f)) return null;
            return new Ready { Build = b, File = f, Notes = (string)o["readyNotes"] ?? "" };
        }

        private static string Done(string s) { var o = Load(); o["status"] = s; Save(o); return s; }

        public static bool Due { get { return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - ((long?)Load()["lastCheck"] ?? 0) > 24L * 3600 * 1000; } }

        private static WebClient Client()
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            var w = new WebClient();
            w.Headers[HttpRequestHeader.UserAgent] = "NovaIPTV-Windows-updater";
            return w;
        }

        private static readonly object Gate = new object();
        private static bool _busy;

        /// <summary>Checks GitHub, downloads and verifies a newer build. Returns a short status line.</summary>
        public static Task<string> Check(Action<string> progress = null)
        {
            return Task.Run(() =>
            {
                lock (Gate) { if (_busy) return "Already checking…"; _busy = true; }
                try
                {
                    var st = Load(); st["lastCheck"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); Save(st);
                    if (progress != null) progress("Checking GitHub…");
                    string json;
                    using (var w = Client()) { w.Headers[HttpRequestHeader.Accept] = "application/vnd.github+json"; json = w.DownloadString("https://api.github.com/repos/" + Repo + "/releases?per_page=40"); }
                    var want = Portable ? "-Portable.zip" : "-Setup.exe";
                    JObject best = null; int bestBuild = 0;
                    foreach (JObject r in JArray.Parse(json))
                    {
                        if ((bool?)r["draft"] == true || (bool?)r["prerelease"] == true) continue;
                        var tag = (string)r["tag_name"] ?? "";
                        int b;
                        if (!tag.StartsWith(TagPrefix) || !int.TryParse(tag.Substring(TagPrefix.Length), out b)) continue;
                        var names = ((JArray)r["assets"]).Select(a => (string)a["name"]).ToList();
                        if (!names.Contains("SHA256SUMS.txt") || !names.Contains("SHA256SUMS.sig") || !names.Any(n => n.EndsWith(want))) continue;
                        if (b > bestBuild) { bestBuild = b; best = r; }
                    }
                    if (best == null || bestBuild <= Current) return Done("✓ Up to date (build " + Current + ")");
                    var ready = Waiting();
                    if (ready != null && ready.Build == bestBuild) return Done("Build " + bestBuild + " is ready to install");
                    var urls = ((JArray)best["assets"]).ToDictionary(a => (string)a["name"], a => (string)a["browser_download_url"]);
                    if (progress != null) progress("Checking the signature of build " + bestBuild + "…");
                    byte[] sums, sig;
                    using (var w = Client()) { sums = w.DownloadData(urls["SHA256SUMS.txt"]); sig = w.DownloadData(urls["SHA256SUMS.sig"]); }
                    if (!SignatureOk(sums, sig)) return Done("⚠ Build " + bestBuild + " refused: the checksum file is not signed with Nova's key");
                    var file = urls.Keys.First(n => n.EndsWith(want));
                    var line = Encoding.UTF8.GetString(sums).Split('\n').Select(l => l.Trim())
                        .FirstOrDefault(l => l.Split(new[] { ' ', '\t' }, 2, StringSplitOptions.RemoveEmptyEntries).Skip(1).FirstOrDefault()?.TrimStart('*').Trim() == file);
                    if (line == null) return Done("⚠ Build " + bestBuild + " refused: the file is not in the checksum list");
                    var expected = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries)[0].ToLowerInvariant();
                    Directory.CreateDirectory(Folder);
                    foreach (var old in Directory.GetFiles(Folder)) try { File.Delete(old); } catch { }
                    var path = Path.Combine(Folder, file);
                    if (progress != null) progress("Downloading build " + bestBuild + "…");
                    using (var w = Client()) w.DownloadFile(urls[file], path);
                    string got;
                    using (var s = File.OpenRead(path)) using (var h = SHA256.Create()) got = BitConverter.ToString(h.ComputeHash(s)).Replace("-", "").ToLowerInvariant();
                    if (got != expected) { File.Delete(path); return Done("⚠ Build " + bestBuild + " refused: the download does not match its SHA-256 (deleted)"); }
                    var o = Load(); o["readyBuild"] = bestBuild; o["readyFile"] = path; o["readyNotes"] = ((string)best["body"] ?? "").Trim(); Save(o);
                    return Done("✓ Build " + bestBuild + " downloaded and verified (signature ✓ · SHA-256 ✓)");
                }
                catch (Exception e) { return Done("⚠ Update check failed: " + e.Message); }
                finally { lock (Gate) _busy = false; }
            });
        }

        private static bool SignatureOk(byte[] data, byte[] sig)
        {
            try
            {
                using (var rsa = new RSACryptoServiceProvider())
                {
                    rsa.ImportParameters(new RSAParameters { Modulus = Convert.FromBase64String(Modulus), Exponent = new byte[] { 1, 0, 1 } });
                    return rsa.VerifyData(data, CryptoConfig.MapNameToOID("SHA256"), sig);
                }
            }
            catch { return false; }
        }

        /// <summary>Runs the verified installer (or replaces the portable files) and closes Nova; it starts again by itself.</summary>
        public static string Install()
        {
            var r = Waiting();
            if (r == null) return "Nothing to install";
            var app = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
            var exe = Path.Combine(app, "Nova.exe");
            try
            {
                if (!Portable)
                {
                    // Inno Setup: silent, closes Nova if needed, and starts it again at the end (as the normal user).
                    Process.Start(new ProcessStartInfo(r.File, "/SILENT /SP- /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS")
                        { UseShellExecute = true, Verb = "runas" });
                }
                else
                {
                    var tmp = Path.Combine(Path.GetTempPath(), "NovaIPTV-update-" + r.Build);
                    if (Directory.Exists(tmp)) Directory.Delete(tmp, true);
                    System.IO.Compression.ZipFile.ExtractToDirectory(r.File, tmp);
                    var src = Directory.GetFiles(tmp, "Nova.exe", SearchOption.AllDirectories).Select(Path.GetDirectoryName).First();
                    var cmd = Path.Combine(Path.GetTempPath(), "NovaIPTV-update.cmd");
                    File.WriteAllText(cmd,
                        "@echo off\r\n" +
                        ":wait\r\ntasklist /FI \"PID eq " + Process.GetCurrentProcess().Id + "\" | find \"" + Process.GetCurrentProcess().Id + "\" >nul && (timeout /t 1 >nul & goto wait)\r\n" +
                        "robocopy \"" + src + "\" \"" + app + "\" /E /XD data /XF portable.txt /NFL /NDL /NJH /NJS >nul\r\n" +
                        "start \"\" \"" + exe + "\"\r\n" +
                        "rmdir /S /Q \"" + tmp + "\"\r\n", Encoding.ASCII);
                    Process.Start(new ProcessStartInfo("cmd.exe", "/c \"" + cmd + "\"") { CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden, UseShellExecute = false });
                }
            }
            catch (Exception e) { return "Could not start the update: " + e.Message; }
            System.Windows.Application.Current.Shutdown();
            return null;
        }

        /// <summary>Main window is up: once a day check, then offer a waiting build (once per build).</summary>
        public static async void AtStart(MainWindow w)
        {
            try
            {
                if (Due) await Check();
                Offer(w, false);
                var t = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromHours(6) };
                t.Tick += async (s, e) => { if (Due) { await Check(); Offer(w, false); } };
                t.Start();
            }
            catch { }
        }

        public static void Offer(MainWindow w, bool always)
        {
            var r = Waiting();
            if (r == null) return;
            var o = Load();
            if (!always && ((int?)o["offered"] ?? 0) == r.Build) return;
            o["offered"] = r.Build; Save(o);
            var msg = "Nova IPTV for Windows build " + Current + " → build " + r.Build + "\n\n" +
                "✓ Checksum file signed with Nova's own key\n✓ SHA-256 of the download matches\n\n" +
                (r.Notes.Length > 0 ? r.Notes.Substring(0, Math.Min(600, r.Notes.Length)) + "\n\n" : "") +
                (Portable ? "Nova closes, its files are replaced (your data folder stays) and it opens again." : "Nova closes, Windows asks to allow the installer, and Nova opens again.");
            if (NovaDialog.Ask(w, "⬆ Update ready", msg, "Install build " + r.Build, "Later"))
            {
                var err = Install();
                if (err != null) NovaDialog.Info(w, "Update", err);
            }
        }
    }
}
