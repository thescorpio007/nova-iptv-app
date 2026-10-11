using System;
using System.IO;
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

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
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
            Img.Init(Path.Combine(Store.CacheFolder, "images"), () => Store.Settings.UserAgent);
            Hub = new StreamHub(() => Store.Settings, () => KnownFolders.Buffer(Store.Settings));
            Downloads = new DownloadManager(Hub, () => Store.Settings, id => Catalog.MaxConnections(id), Store.DataFolder);
            Recordings = new RecordingManager(Hub, Downloads, () => Store.Settings);
            var ignored = Catalog.CheckAccounts();
            Store.AccountsChanged += () => { var again = Catalog.CheckAccounts(); };

            try
            {
                // Full name: "Core" alone would mean the Nova.Core namespace here.
                LibVLCSharp.Shared.Core.Initialize(Path.Combine(AppFolder, "libvlc", "win-x86"));
                Vlc = new LibVLC("--no-video-title-show", "--no-snapshot-preview", "--no-stats", "--quiet");
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
