using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using LibVLCSharp.Shared;
using Nova.Core;
using Nova.Core.Streaming;

namespace Nova.App
{
    /// <summary>Starts Nova: storage, Xtream client, VLC engine, main window.</summary>
    public partial class App : Application
    {
        public static Store Store { get; private set; }
        public static Catalog Catalog { get; private set; }
        public static LibVLC Vlc { get; private set; }
        /// <summary>The stream engine: disk buffers, watchdog, local video server.</summary>
        public static StreamHub Hub { get; private set; }
        public static DownloadManager Downloads { get; private set; }
        public static RecordingManager Recordings { get; private set; }
        public static string AppFolder { get; private set; }

        private static System.Threading.Mutex _single;
        private static string[] _args = new string[0];
        private const string PipeName = "NovaIPTV-open";

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            _args = e.Args ?? new string[0];
            // One Nova at a time: a second start (e.g. "Open with Nova") hands its file or link to the running one.
            bool first;
            _single = new System.Threading.Mutex(true, "NovaIPTV-single-instance", out first);
            if (!first)
            {
                try
                {
                    using (var pipe = new System.IO.Pipes.NamedPipeClientStream(".", PipeName, System.IO.Pipes.PipeDirection.Out))
                    {
                        pipe.Connect(3000);
                        var bytes = System.Text.Encoding.UTF8.GetBytes(string.Join("\n", _args.Length > 0 ? _args : new[] { "" }));
                        pipe.Write(bytes, 0, bytes.Length);
                    }
                }
                catch { }
                Shutdown(0);
                return;
            }
            StartPipe();
            AppFolder = AppDomain.CurrentDomain.BaseDirectory;
            DispatcherUnhandledException += OnUiError;
            AppDomain.CurrentDomain.UnhandledException += (s, a) => Log(a.ExceptionObject as Exception);
            TaskScheduler.UnobservedTaskException += (s, a) => { Log(a.Exception); a.SetObserved(); };

            try
            {
                Store = Store.Open(AppFolder);
            }
            catch (Exception ex)
            {
                MessageBox.Show("Nova can't open its settings folder:\n\n" + ex.Message, "Nova IPTV", MessageBoxButton.OK, MessageBoxImage.Error);
                Shutdown(1);
                return;
            }
            var api = new XtreamApi(() => Store.Settings.UserAgent);
            Catalog = new Catalog(api, Store);
            Catalog.IndexFolder = () => KnownFolders.Index(Store.Settings);
            // Website rules (Open link): remembered headers, retry as a browser, your servers untouched.
            HttpPolicy.UserAgent = () => Store.Settings.UserAgent;
            HttpPolicy.IsAccountHost = h => Store.IsAccountHost(h);
            HttpPolicy.SavedHeaders = h => Store.LinkHeaders(h);
            HttpPolicy.RememberHeaders = (h, d) => Current.Dispatcher.BeginInvoke(new Action(() => Store.SetLinkHeaders(h, d)));
            Img.Init(Path.Combine(Store.CacheFolder, "images"), () => Store.Settings.UserAgent);
            Hub = new StreamHub(() => Store.Settings, () => KnownFolders.Buffer(Store.Settings));
            Downloads = new DownloadManager(Hub, () => Store.Settings, id => Catalog.MaxConnections(id), Store.DataFolder);
            Recordings = new RecordingManager(Hub, Downloads, () => Store.Settings);
            Store.AccountsChanged += () => { var again = Catalog.CheckAccounts(); };

            try
            {
                // Full name: "Core" alone would mean the Nova.Core namespace here.
                LibVLCSharp.Shared.Core.Initialize(Path.Combine(AppFolder, "libvlc", "win-x86"));
                Vlc = new LibVLC(Playback.VlcOptions(false));
            }
            catch (Exception ex)
            {
                Log(ex);
                MessageBox.Show("The video engine (VLC) could not start, so videos won't play.\n\n" + ex.Message +
                    "\n\nReinstalling Nova usually fixes this.", "Nova IPTV", MessageBoxButton.OK, MessageBoxImage.Warning);
            }

            var main = new MainWindow();
            MainWindow = main;
            main.Show();
        }

        /// <summary>Receives files / links from a second start of Nova.</summary>
        private void StartPipe()
        {
            var t = new System.Threading.Thread(() =>
            {
                while (true)
                {
                    try
                    {
                        using (var server = new System.IO.Pipes.NamedPipeServerStream(PipeName, System.IO.Pipes.PipeDirection.In))
                        {
                            server.WaitForConnection();
                            using (var r = new StreamReader(server, System.Text.Encoding.UTF8))
                            {
                                var text = r.ReadToEnd();
                                Dispatcher.BeginInvoke(new Action(() =>
                                {
                                    var w = Home;
                                    if (w == null) return;
                                    if (w.WindowState == WindowState.Minimized) w.WindowState = WindowState.Normal;
                                    w.Activate();
                                    foreach (var a in text.Split('\n')) if (a.Trim().Length > 0) { w.OpenFromOutside(a.Trim()); break; }
                                }));
                            }
                        }
                    }
                    catch { System.Threading.Thread.Sleep(500); }
                }
            }) { IsBackground = true, Name = "Nova open-with" };
            t.Start();
        }

        /// <summary>After the window is up: start-up check, accounts link, search index, last channel, file to open.</summary>
        public static async void AfterStart()
        {
            var w = Home;
            if (w == null) return;
            if (_args.Length > 0 && _args[0].Trim().Length > 0) { w.OpenFromOutside(_args[0]); _args = new string[0]; }
            else if (Store.Accounts.Count == 0) { w.ShowWelcomeIfEmpty(); }
            else if (Store.Settings.AutoPlayLast) w.PlayLastChannel();

            // Accounts link: checked once a day (nothing changes if it can't be read).
            if (Store.State.AccountsLink != null &&
                (DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - Store.State.AccountsLinkChecked) > 20L * 3600 * 1000)
            {
                var note = await BackupUi.SyncAccountsLink();
                if (note != null && note != "Up to date") w.Toast("Accounts link: " + note);
            }

            // Start-up check: say which account can't sign in (often: VPN off).
            var problems = new List<string>();
            foreach (var a in Store.Accounts.ToList())
            {
                try { Catalog.Note(a.Id, await Catalog.Api.Authenticate(a)); }
                catch (Exception e) { problems.Add(a.DisplayName + ": " + e.Message); }
            }
            if (problems.Count > 0)
                w.Toast("⚠ " + string.Join("  ·  ", problems) + (Vpn.Active() == null ? "  — is your VPN on?" : ""));

            // The search index is refreshed quietly once a day.
            try { await Catalog.RefreshIndex(System.Threading.CancellationToken.None); } catch { }
        }

        protected override void OnExit(ExitEventArgs e)
        {
            try { if (Recordings != null) Recordings.Dispose(); } catch { }
            try { if (Downloads != null) Downloads.Dispose(); } catch { }
            try { if (Hub != null) Hub.Dispose(); } catch { }
            try { if (Vlc != null) Vlc.Dispose(); } catch { }
            base.OnExit(e);
        }

        private void OnUiError(object sender, DispatcherUnhandledExceptionEventArgs e)
        {
            Log(e.Exception);
            e.Handled = true;
            var w = MainWindow as MainWindow;
            if (w != null) w.Toast("Something went wrong: " + e.Exception.Message);
        }

        /// <summary>Keeps the last errors in error.log in the data folder, to help fix problems.</summary>
        public static void Log(Exception ex)
        {
            if (ex == null) return;
            try
            {
                var folder = Store != null ? Store.DataFolder : Path.GetTempPath();
                var path = Path.Combine(folder, "error.log");
                if (File.Exists(path) && new FileInfo(path).Length > 512 * 1024) File.Delete(path);
                File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + ex + Environment.NewLine + Environment.NewLine);
            }
            catch { }
        }

        public static MainWindow Home { get { return Current.MainWindow as MainWindow; } }
    }
}
